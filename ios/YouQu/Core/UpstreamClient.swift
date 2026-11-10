import Foundation

enum UpstreamError: Error, LocalizedError {
    case badURL
    case http(Int)
    case failed

    var errorDescription: String? {
        switch self {
        case .badURL: return "上游地址非法"
        case .http(let c): return "上游 HTTP \(c)"
        case .failed: return "上游请求失败"
        }
    }
}

/// 直连上游站点的 HTTP 客户端（等价于 PHP 的 HttpClient.php + Decoder.php）。
/// 关键点（漏一个就会 403 / 解码失败）：
/// - 所有请求带 Cookie: device={device}
/// - User-Agent 尾部必须拼 " abab/{device}"
/// - Origin / Referer 指向上游站点自身
/// - 响应体是「倒序 Base64」，解码后 code==1 表示成功
final class UpstreamClient {
    static let shared = UpstreamClient()

    let tokens = TokenProvider.shared

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 60
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    // MARK: - 公共请求头（业务接口）

    private func commonHeaders(_ c: Credential) -> [String: String] {
        [
            "Cookie": "device=\(c.device)",
            "User-Agent": DirectConfig.apiUserAgent(c.device),
            "Origin": c.baseUrl,
            "Referer": "\(c.baseUrl)/",
            "Accept": "*/*",
            "Accept-Language": "zh-CN,zh-Hans;q=0.9"
        ]
    }

    private func apply(_ headers: [String: String], to req: inout URLRequest) {
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
    }

    // MARK: - 业务 GET / POST（multipart）

    func get(path: String, query: [String: String] = [:]) async throws -> DecodedResponse {
        try await execute(needAuth: false) { cred in
            var req = URLRequest(url: try buildURL(cred.baseUrl, path, query))
            req.httpMethod = "GET"
            return req
        }
    }

    /// fields 用有序数组，保证 multipart 字段顺序与 Android 的 LinkedHashMap 一致。
    func postMultipart(path: String,
                       fields: [(String, String)],
                       query: [String: String] = [:],
                       needAuth: Bool = false) async throws -> DecodedResponse {
        try await execute(needAuth: needAuth) { cred in
            var all = fields
            if needAuth { all.append(("token", cred.token)) }
            let boundary = "----WebKitFormBoundary" + randomBoundary()
            var req = URLRequest(url: try buildURL(cred.baseUrl, path, query))
            req.httpMethod = "POST"
            req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            req.httpBody = multipartBody(fields: all, boundary: boundary)
            return req
        }
    }

    /// 统一执行：注入公共头 → 发请求 → 解码 → 鉴权失效自动刷新 token 重试一次。
    private func execute(needAuth: Bool,
                         build: (Credential) throws -> URLRequest) async throws -> DecodedResponse {
        var cred = try await tokens.get()
        for attempt in 0..<2 {
            var req = try build(cred)
            apply(commonHeaders(cred), to: &req)
            do {
                let (data, response) = try await session.data(for: req)
                guard let http = response as? HTTPURLResponse else { throw UpstreamError.failed }
                if !(200..<300).contains(http.statusCode) {
                    if attempt == 0, (401...403).contains(http.statusCode) {
                        cred = try await tokens.get(forceRefresh: true)
                        continue
                    }
                    throw UpstreamError.http(http.statusCode)
                }
                let decoded = try ResponseDecoder.decode(data)
                if attempt == 0, decoded.authExpired || (needAuth && !decoded.success) {
                    cred = try await tokens.get(forceRefresh: true)
                    continue
                }
                return decoded
            } catch {
                if attempt == 1 { throw error }
                // 第一次出错沿用当前凭证再试一次
            }
        }
        throw UpstreamError.failed
    }

    // MARK: - 资源拉取（播放列表 / 分片 / 图片混淆体）

    /// 拉文本 / 二进制资源，带 abab UA 与 device Cookie；
    /// 显式 Accept-Encoding: identity 禁止透明 gzip，避免 Content-Length 与转发字节不一致。
    func fetchRaw(url: String, extraHeaders: [String: String] = [:]) async throws -> (HTTPURLResponse, Data) {
        guard let u = URL(string: url) else { throw UpstreamError.badURL }
        let cred = try await tokens.get()
        var req = URLRequest(url: u)
        req.httpMethod = "GET"
        req.setValue(DirectConfig.apiUserAgent(cred.device), forHTTPHeaderField: "User-Agent")
        req.setValue("device=\(cred.device)", forHTTPHeaderField: "Cookie")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        req.setValue("zh-CN,zh-Hans;q=0.9", forHTTPHeaderField: "Accept-Language")
        extraHeaders.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UpstreamError.failed }
        return (http, data)
    }

    /// 图片回源专用：UA 不带 abab，Referer 指向上游站点。
    func fetchImage(url: String) async throws -> (HTTPURLResponse, Data) {
        guard let u = URL(string: url) else { throw UpstreamError.badURL }
        var req = URLRequest(url: u)
        req.httpMethod = "GET"
        req.setValue(DirectConfig.iphoneUA, forHTTPHeaderField: "User-Agent")
        req.setValue("\(DirectConfig.defaultUpstream)/", forHTTPHeaderField: "Referer")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw UpstreamError.failed }
        return (http, data)
    }

    // MARK: - 工具

    private func buildURL(_ base: String, _ path: String, _ query: [String: String]) throws -> URL {
        guard var comps = URLComponents(string: base + path) else { throw UpstreamError.badURL }
        if !query.isEmpty {
            comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else { throw UpstreamError.badURL }
        return url
    }

    private func multipartBody(fields: [(String, String)], boundary: String) -> Data {
        var s = ""
        for (k, v) in fields {
            s += "--\(boundary)\r\n"
            s += "Content-Disposition: form-data; name=\"\(k)\"\r\n\r\n"
            s += "\(v)\r\n"
        }
        s += "--\(boundary)--\r\n"
        return Data(s.utf8)
    }

    private func randomBoundary() -> String {
        let pool = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        return String((0..<16).compactMap { _ in pool.randomElement() })
    }
}
