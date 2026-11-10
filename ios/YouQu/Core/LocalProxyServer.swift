import Foundation
import Network

enum ProxyError: Error {
    case badRequest
    case connectionClosed
    case sendFailed
}

// MARK: - 极简 HTTP/1.1 请求 / 响应模型（仅本机回环使用）

private struct HTTPRequest {
    let method: String
    let path: String
    let query: [String: String]
    let headers: [String: String]

    var range: String? { headers["range"] }

    static func parse(_ head: Data) -> HTTPRequest? {
        guard let text = String(data: head, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let parts = first.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let target = String(parts[1])
        let qIndex = target.firstIndex(of: "?")
        let path = qIndex.map { String(target[..<$0]) } ?? target
        let queryRaw = qIndex.map { String(target[target.index(after: $0)...]) } ?? ""

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            if let c = line.firstIndex(of: ":") {
                let k = String(line[..<c]).lowercased()
                let v = String(line[line.index(after: c)...])
                    .trimmingCharacters(in: .whitespaces)
                headers[k] = v
            }
        }
        return HTTPRequest(method: String(parts[0]), path: path,
                           query: parseQuery(queryRaw), headers: headers)
    }

    private static func parseQuery(_ raw: String) -> [String: String] {
        var map: [String: String] = [:]
        guard !raw.isEmpty else { return map }
        for pair in raw.split(separator: "&") {
            let i = pair.firstIndex(of: "=") ?? pair.endIndex
            let k = decode(String(pair[..<i]))
            let v = i == pair.endIndex ? "" : decode(String(pair[pair.index(after: i)...]))
            if !k.isEmpty { map[k] = v }
        }
        return map
    }

    private static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s
    }
}

private struct HTTPResponse {
    let status: Int
    let reason: String
    let headers: [String: String]
    let body: Data
}

/// APP 内置本机代理：AVPlayer 无法自定义分片请求的 User-Agent / Cookie，
/// 所以在 127.0.0.1 起一个轻量 HTTP/1.1 服务，代取 KEY、分片与加密图片。
///
/// 路由：
///   /m3u8?vid=  取最新签名 src → 拉明文播放列表 → 改写 KEY/分片到 /seg
///   /seg?u=     带 abab UA / device Cookie 代取，透传 Range / 206 / 各头
///   /img?u=     图片回源 + AES-128-ECB 解密
///   /ping       健康检查
///
/// 账号密码不在这里：token 由服务器 token.php 下发（见 TokenProvider）。
final class LocalProxyServer {
    static let shared = LocalProxyServer()

    private let lock = NSLock()
    private var _port: UInt16?
    private var _started = false
    private var listener: NWListener?
    private let serverQueue = DispatchQueue(label: "com.youqu.vod.localproxy", qos: .userInitiated)

    private init() {}

    // MARK: - 对外地址

    var port: UInt16? {
        lock.lock(); defer { lock.unlock() }
        return _port
    }

    var localBase: String? { port.map { "http://127.0.0.1:\($0)" } }

    /// 监听端口是异步就绪的；未就绪时先用第一个候选端口兜底，保证 UI 能立即拼出 URL。
    private var effectiveBase: String {
        localBase ?? "http://127.0.0.1:\(DirectConfig.ports[0])"
    }

    /// 把上游图片字段包成本机 /img 地址（供 CoverImageLoader 懒加载使用）
    func imageURL(_ raw: String) -> String {
        "\(effectiveBase)/img?u=" + DirectConfig.b64url(normalizeImage(raw))
    }

    /// AVPlayer 加载的地址
    func m3u8URL(vid: String) -> String {
        "\(effectiveBase)/m3u8?vid=" +
            (vid.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? vid)
    }

    func pingURL() -> String { effectiveBase + "/ping" }

    private func normalizeImage(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.lowercased().hasPrefix("http://") || t.lowercased().hasPrefix("https://") {
            return t
        }
        return "https://\(DirectConfig.imageHost)/" +
            t.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    // MARK: - 启动 NWListener（端口候选 19025...19029）

    func start() {
        lock.lock()
        if _started { lock.unlock(); return }
        _started = true
        lock.unlock()
        tryBind(at: 0)
    }

    private func setPort(_ p: UInt16) {
        lock.lock(); _port = p; lock.unlock()
    }

    private func setListener(_ l: NWListener) {
        lock.lock(); listener = l; lock.unlock()
    }

    private func makeListener(port: UInt16) -> NWListener? {
        let params = NWParameters.tcp
        guard let ipv4 = IPv4Address("127.0.0.1"),
              let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(ipv4), port: nwPort)
        return try? NWListener(using: params)
    }

    private func tryBind(at index: Int) {
        guard index < DirectConfig.ports.count else { return }
        let p = DirectConfig.ports[index]
        guard let l = makeListener(port: p) else {
            tryBind(at: index + 1)
            return
        }
        // 乐观设置端口：让 UI 立刻能算出本机 URL；端口被占用时 .failed 会顺延到下一个
        setPort(p)
        l.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.setListener(l)
            case .failed(_):
                self?.tryBind(at: index + 1)
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] conn in self?.handle(conn) }
        l.start(queue: serverQueue)
    }

    // MARK: - 连接处理（一个连接处理一个请求，响应后关闭）

    private func handle(_ connection: NWConnection) {
        connection.start(queue: serverQueue)
        Task {
            defer { connection.cancel() }
            let req: HTTPRequest
            do {
                req = try await readRequest(connection)
            } catch {
                return // 客户端提前断开，直接关闭连接
            }
            let resp = await route(req)
            // 各路由内部已把异常转成 4xx/5xx 响应；这里只负责把字节写回。
            try? await writeResponse(resp, to: connection)
        }
    }

    private func readRequest(_ c: NWConnection) async throws -> HTTPRequest {
        var buf = [UInt8]()
        for _ in 0..<64 {
            let (chunk, complete) = try await receive(c)
            buf.append(contentsOf: [UInt8](chunk))
            if let end = headerEnd(in: buf) {
                let head = Data(buf.prefix(end))
                guard let req = HTTPRequest.parse(head) else { throw ProxyError.badRequest }
                return req
            }
            if complete { break }
        }
        throw ProxyError.badRequest
    }

    /// 找到 \r\n\r\n 的起始下标
    private func headerEnd(in bytes: [UInt8]) -> Int? {
        let marker: [UInt8] = [0x0D, 0x0A, 0x0D, 0x0A]
        guard bytes.count >= marker.count else { return nil }
        for i in 0...(bytes.count - marker.count) {
            if Array(bytes[i..<(i + marker.count)]) == marker { return i }
        }
        return nil
    }

    private func receive(_ c: NWConnection) async throws -> (Data, Bool) {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(Data, Bool), Error>) in
            c.receive(minimumIncompleteLength: 1, maximumLength: 65536) { data, _, isComplete, error in
                if error != nil {
                    cont.resume(throwing: ProxyError.connectionClosed)
                } else {
                    cont.resume(returning: (data ?? Data(), isComplete))
                }
            }
        }
    }

    private func send(_ data: Data, _ c: NWConnection, _ isComplete: Bool) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            c.send(content: data, contentContext: .defaultMessage,
                   isComplete: isComplete, completion: .contentProcessed { error in
                if error != nil {
                    cont.resume(throwing: ProxyError.sendFailed)
                } else {
                    cont.resume()
                }
            })
        }
    }

    /// 先写响应头，再把 body 按 64KB 分块流式写回；最后一块 isComplete=true 结束写侧，
    /// 配合 Connection: close 让播放器在上游无 Content-Length 时也能正确收尾。
    private func writeResponse(_ r: HTTPResponse, to c: NWConnection) async throws {
        var headers = r.headers
        if headers["Connection"] == nil { headers["Connection"] = "close" }
        var head = "HTTP/1.1 \(r.status) \(r.reason)\r\n"
        for (k, v) in headers { head += "\(k): \(v)\r\n" }
        head += "\r\n"

        let bytes = [UInt8](r.body)
        if bytes.isEmpty {
            try await send(Data(head.utf8), c, true)
            return
        }
        try await send(Data(head.utf8), c, false)
        let chunkSize = 64 * 1024
        var idx = 0
        while idx < bytes.count {
            let end = min(idx + chunkSize, bytes.count)
            let last = end == bytes.count
            try await send(Data(bytes[idx..<end]), c, last)
            idx = end
        }
    }

    // MARK: - 路由

    private func route(_ req: HTTPRequest) async -> HTTPResponse {
        switch req.path {
        case "/ping":
            return jsonResponse(["ok": true, "service": "local-gateway"])
        case "/img":
            return await handleImage(req)
        case "/m3u8":
            return await handleM3u8(req)
        case "/seg":
            return await handleSeg(req)
        default:
            return simple(404, "not found")
        }
    }

    // MARK: /m3u8

    private func handleM3u8(_ req: HTTPRequest) async -> HTTPResponse {
        let vid = req.query["vid"] ?? req.query["v"] ?? ""
        guard !vid.isEmpty else { return simple(400, "missing vid") }

        do {
            // 每次播放重新取 show：src 带一次性签名，会过期
            let src = try await DramaAPI.shared.show(vid: vid).video.src
            guard !src.isEmpty else { return simple(502, "no src in show response") }

            let (resp, data) = try await UpstreamClient.shared.fetchRaw(url: src)
            guard (200..<300).contains(resp.statusCode) else {
                return simple(502, "playlist upstream HTTP \(resp.statusCode)")
            }
            guard let text = String(data: data, encoding: .utf8),
                  text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("#EXTM3U") else {
                return simple(502, "invalid playlist")
            }

            let rewritten = M3u8Rewriter.rewrite(
                playlist: text, playlistURL: src, localBase: effectiveBase, vid: vid)
            let body = Data(rewritten.utf8)
            return HTTPResponse(status: 200, reason: "OK",
                                headers: ["Content-Type": "application/vnd.apple.mpegurl",
                                          "Content-Length": "\(body.count)",
                                          "Cache-Control": "no-store",
                                          "Access-Control-Allow-Origin": "*",
                                          "Connection": "close"],
                                body: body)
        } catch {
            return simple(502, "playlist fetch failed")
        }
    }

    // MARK: /seg

    private func handleSeg(_ req: HTTPRequest) async -> HTTPResponse {
        guard let encoded = req.query["u"],
              let url = DirectConfig.b64urlDecode(encoded), !url.isEmpty else {
            return simple(400, "missing u")
        }
        guard await allowedSegmentHost(url) else { return simple(403, "host not allowed") }

        var extra: [String: String] = [:]
        if let range = req.range { extra["Range"] = range }

        do {
            let (resp, data) = try await UpstreamClient.shared.fetchRaw(url: url, extraHeaders: extra)
            // 分片 4xx（签名过期）→ 重新拉一次播放列表换新地址再试一次
            if (400...499).contains(resp.statusCode),
               let vid = req.query["vid"], !vid.isEmpty,
               let newURL = try? await freshSegmentURL(for: url, vid: vid),
               let retry = try? await UpstreamClient.shared.fetchRaw(url: newURL, extraHeaders: extra) {
                return segmentResponse(retry.0, retry.1)
            }
            return segmentResponse(resp, data)
        } catch {
            return simple(502, "segment fetch failed")
        }
    }

    /// 重新调 show 拿最新 src → 拉播放列表 → 按分片文件名匹配出新的签名地址
    private func freshSegmentURL(for failingURL: String, vid: String) async throws -> String {
        let detail = try await DramaAPI.shared.show(vid: vid)
        let src = detail.video.src
        guard !src.isEmpty else { return failingURL }
        let (resp, data) = try await UpstreamClient.shared.fetchRaw(url: src)
        guard (200..<300).contains(resp.statusCode),
              let text = String(data: data, encoding: .utf8) else { return failingURL }
        let urls = M3u8Rewriter.absoluteSegmentURLs(playlist: text, playlistURL: src)
        let lastName = URL(string: failingURL)?.lastPathComponent
        if let match = urls.first(where: { URL(string: $0)?.lastPathComponent == lastName }) {
            return match
        }
        return failingURL // 兜底：用原地址再试一次
    }

    private func segmentResponse(_ resp: HTTPURLResponse, _ data: Data) -> HTTPResponse {
        var h: [String: String] = [:]
        h["Content-Type"] = resp.value(forHTTPHeaderField: "Content-Type")
            ?? "application/octet-stream"
        h["Accept-Ranges"] = "bytes"
        if let cr = resp.value(forHTTPHeaderField: "Content-Range") { h["Content-Range"] = cr }
        // 上游可能不返回 Content-Length（chunked）。已缓冲完整字节，因此补一个精确长度，
        // 配合 Connection: close 避免播放器一直等下一个响应（起播后卡住）。
        if let cl = resp.value(forHTTPHeaderField: "Content-Length"), Int(cl) == data.count {
            h["Content-Length"] = cl
        } else {
            h["Content-Length"] = "\(data.count)"
        }
        h["Access-Control-Allow-Origin"] = "*"
        h["Connection"] = "close"
        return HTTPResponse(status: resp.statusCode, reason: reason(resp.statusCode),
                            headers: h, body: data)
    }

    // MARK: /img

    private func handleImage(_ req: HTTPRequest) async -> HTTPResponse {
        guard let encoded = req.query["u"],
              let url = DirectConfig.b64urlDecode(encoded), !url.isEmpty else {
            return simple(400, "missing u")
        }
        guard await allowedImageHost(url) else { return simple(403, "host not allowed") }

        // 命中解密缓存就直接回包，不产生任何上游流量（列表来回滚动时效果最明显）
        if let c = ImageCache.shared.get(url) { return imageResponse(c) }

        // 懒加载回源闸门：限制并发，播放起播窗口内完全让路给 HLS 分片
        guard await ImageLoadGate.shared.acquire() else {
            return simple(502, "image load cancelled")
        }
        defer { ImageLoadGate.shared.release() }

        do {
            let (resp, data) = try await UpstreamClient.shared.fetchImage(url: url)
            guard (200..<300).contains(resp.statusCode) else {
                return simple(502, "image upstream HTTP \(resp.statusCode)")
            }
            guard let img = ImageDecryptor.decrypt(data) else {
                return simple(502, "image decrypt failed")
            }
            let c = CachedImage(mime: img.mime, bytes: img.bytes)
            ImageCache.shared.set(c, forKey: url)
            return imageResponse(c)
        } catch {
            return simple(502, "image fetch failed")
        }
    }

    private func imageResponse(_ c: CachedImage) -> HTTPResponse {
        HTTPResponse(status: 200, reason: "OK",
                     headers: ["Content-Type": c.mime,
                               "Content-Length": "\(c.bytes.count)",
                               "Cache-Control": "private, max-age=604800, immutable",
                               "X-Content-Type-Options": "nosniff",
                               "Access-Control-Allow-Origin": "*",
                               "Connection": "close"],
                     body: c.bytes)
    }

    // MARK: - host 白名单

    private func allowedSegmentHost(_ url: String) async -> Bool {
        guard DirectConfig.isHTTPS(url), let host = DirectConfig.host(of: url) else { return false }
        if DirectConfig.segmentHosts.contains(host) { return true }
        if host.hasSuffix(".100njp.com") || host.hasSuffix(".xxcjpt.com") { return true }
        if let cred = try? await TokenProvider.shared.get(), cred.streamHosts.contains(host) { return true }
        return false
    }

    private func allowedImageHost(_ url: String) async -> Bool {
        guard DirectConfig.isHTTPS(url), let host = DirectConfig.host(of: url) else { return false }
        if host == DirectConfig.imageHost { return true }
        if let cred = try? await TokenProvider.shared.get(), host == cred.imageHost { return true }
        return false
    }

    // MARK: - 响应工具

    private func simple(_ status: Int, _ message: String) -> HTTPResponse {
        let body = Data(message.utf8)
        return HTTPResponse(status: status, reason: reason(status),
                            headers: ["Content-Type": "text/plain; charset=utf-8",
                                      "Content-Length": "\(body.count)",
                                      "Connection": "close"],
                            body: body)
    }

    private func jsonResponse(_ obj: [String: Any]) -> HTTPResponse {
        let body = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data()
        return HTTPResponse(status: 200, reason: "OK",
                            headers: ["Content-Type": "application/json; charset=utf-8",
                                      "Content-Length": "\(body.count)",
                                      "Cache-Control": "no-store",
                                      "Access-Control-Allow-Origin": "*",
                                      "Connection": "close"],
                            body: body)
    }

    private func reason(_ code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 206: return "Partial Content"
        case 301: return "Moved Permanently"
        case 302: return "Found"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 416: return "Range Not Satisfiable"
        case 500: return "Internal Server Error"
        case 502: return "Bad Gateway"
        default: return (200..<300).contains(code) ? "OK" : "Error"
        }
    }
}
