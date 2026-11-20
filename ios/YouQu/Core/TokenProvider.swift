import Foundation

enum TokenError: Error, LocalizedError {
    case badURL
    case http(Int)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .badURL: return "token.php 地址非法"
        case .http(let c): return "token.php HTTP \(c)"
        case .server(let m): return m
        }
    }
}

/// 服务器下发的一次性会话凭证。
/// 注意：这里绝不包含、也绝不存储上游账号密码。
struct Credential: Codable {
    let token: String
    let device: String
    let baseUrl: String
    let imageHost: String
    let streamHosts: [String]
}

/// 凭证提供者（凭证仓库模式）。
///
/// 服务器出口被上游 WAF 拦截，token.php 退化为纯 token 仓库（不访问上游）。
/// 闭环：
///   1. get() 先向仓库取缓存 token（可能是本机或其他设备之前回传的），有效直接用；
///   2. 仓库为空 / need_refresh → 调 credential 接口拿【加密的账号密码】+ device + base_url；
///   3. App 内存解密，用【手机自身网络出口】POST /java/v2/login 换 token；
///   4. 把新 token 通过 action=save 回传仓库，供后期 / 其他设备复用；
///   5. 鉴权失效（forceRefresh）→ 先 action=invalidate 上报旧 token，再走 2~4 重新登录。
///
/// 账号密码只存在于解密后的局部变量，用完即弃，绝不落盘、不进 UserDefaults。
/// 本地缓存的只是会话 token/device/baseUrl。
actor TokenProvider {
    static let shared = TokenProvider()

    private let defaults = UserDefaults.standard
    private var cached: Credential?

    private let kToken = "yq.direct.token"
    private let kDevice = "yq.direct.device"
    private let kBaseUrl = "yq.direct.base_url"
    private let kImageHost = "yq.direct.image_host"
    private let kStreamHosts = "yq.direct.stream_hosts"

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 40
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    func get(forceRefresh: Bool = false) async throws -> Credential {
        if !forceRefresh {
            if let cached { return cached }
            if let c = load() {
                cached = c
                return c
            }
        }

        var lastError = "未知错误"
        for base in tokenBaseURLs() {
            do {
                if forceRefresh {
                    // 鉴权失效：上报旧 token，让仓库标记 need_refresh；不再信任仓库缓存。
                    if let old = (cached ?? load())?.token {
                        try? await reportInvalid(base: base, token: old)
                    }
                    cached = nil
                    clear()
                } else if let repo = try? await fetchRepositoryToken(base: base) {
                    // 常规取用：优先复用仓库缓存 token（含其他设备回传的）。
                    cached = repo
                    save(repo)
                    return repo
                }

                // 仓库无有效 token / 强制刷新 → 手机端登录换新 token 并回传仓库。
                let credPayload = try await fetchCredential(base: base)
                let (username, password) = try decryptSecret(credPayload.cipher)
                let token = try await loginUpstream(
                    baseUrl: credPayload.baseUrl,
                    device: credPayload.device,
                    username: username,
                    password: password
                )
                try? await saveToken(base: base, token: token, device: credPayload.device)

                let c = Credential(
                    token: token,
                    device: credPayload.device,
                    baseUrl: credPayload.baseUrl,
                    imageHost: credPayload.imageHost,
                    streamHosts: credPayload.streamHosts
                )
                cached = c
                save(c)
                return c
            } catch {
                lastError = error.localizedDescription
            }
        }
        throw TokenError.server("无法获取访问凭证：\(lastError)（请确认服务器 token.php 已部署凭证仓库版本）")
    }

    /// 清空缓存，下次取凭证会重新走网络
    func invalidate() {
        cached = nil
        clear()
    }

    // MARK: - 地址

    private func tokenBaseURLs() -> [String] {
        var result: [String] = []
        // 主地址用 DirectConfig 固定值（iOS 端 token.php 不随 baseUrl 变化）
        result.append(DirectConfig.tokenURL)
        return result
    }

    // MARK: - 网络

    private struct CredPayload {
        let cipher: String
        let device: String
        let baseUrl: String
        let imageHost: String
        let streamHosts: [String]
    }

    /// 从仓库取缓存 token；need_refresh 或空 token 返回 nil
    private func fetchRepositoryToken(base: String) async throws -> Credential? {
        let json = try await callToken(base: base, action: "get")
        if json["need_refresh"] as? Bool == true { return nil }
        let token = (json["token"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if token.isEmpty { return nil }
        let device = (json["device"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawBase = (json["base_url"] as? String ?? DirectConfig.defaultUpstream)
        return Credential(
            token: token,
            device: device.isEmpty ? randomDevice() : device,
            baseUrl: DirectConfig.trimTrailingSlash(rawBase),
            imageHost: (json["image_host"] as? String) ?? DirectConfig.imageHost,
            streamHosts: (json["stream_hosts"] as? [String]) ?? []
        )
    }

    /// 请求 token.php?action=credential
    private func fetchCredential(base: String) async throws -> CredPayload {
        let json = try await callToken(base: base, action: "credential")
        let cipher = json["cipher"] as? String ?? ""
        if cipher.isEmpty {
            throw TokenError.server("token.php 未返回 cipher，请在服务器部署凭证仓库版本 token.php")
        }
        let device = (json["device"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawBase = (json["base_url"] as? String ?? DirectConfig.defaultUpstream)
        return CredPayload(
            cipher: cipher,
            device: device.isEmpty ? randomDevice() : device,
            baseUrl: DirectConfig.trimTrailingSlash(rawBase),
            imageHost: (json["image_host"] as? String) ?? DirectConfig.imageHost,
            streamHosts: (json["stream_hosts"] as? [String]) ?? []
        )
    }

    /// 上报旧 token 失效（尽力而为）
    private func reportInvalid(base: String, token: String) async throws {
        _ = try await callToken(base: base, action: "invalidate", extra: [URLQueryItem(name: "token", value: token)])
    }

    /// 回传新 token 到仓库（POST form）
    private func saveToken(base: String, token: String, device: String) async throws {
        guard var comps = URLComponents(string: base) else { throw TokenError.badURL }
        var items = [URLQueryItem(name: "action", value: "save"),
                     URLQueryItem(name: "_t", value: String(Int(Date().timeIntervalSince1970 * 1000)))]
        if !DirectConfig.tokenAccessKey.isEmpty {
            items.append(URLQueryItem(name: "key", value: DirectConfig.tokenAccessKey))
        }
        comps.queryItems = items
        guard let url = comps.url else { throw TokenError.badURL }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        let form = "token=\(DirectConfig.percentEncode(token))&device=\(DirectConfig.percentEncode(device))"
        req.setValue("application/x-www-form-urlencoded;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(form.utf8)
        req.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw TokenError.server("save 无 HTTP 响应") }
        guard (200..<300).contains(http.statusCode) else { throw TokenError.http(http.statusCode) }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw TokenError.server("save 返回非法 JSON")
        }
        guard json["ok"] as? Bool == true else {
            throw TokenError.server((json["error"] as? String) ?? "save 失败")
        }
    }

    /// 通用 token.php GET 调用，返回 JSON 对象
    private func callToken(base: String, action: String, extra: [URLQueryItem] = []) async throws -> [String: Any] {
        guard var comps = URLComponents(string: base) else { throw TokenError.badURL }
        var items = [URLQueryItem(name: "action", value: action),
                     URLQueryItem(name: "_t", value: String(Int(Date().timeIntervalSince1970 * 1000)))]
        if !DirectConfig.tokenAccessKey.isEmpty {
            items.append(URLQueryItem(name: "key", value: DirectConfig.tokenAccessKey))
        }
        items.append(contentsOf: extra)
        comps.queryItems = items
        guard let url = comps.url else { throw TokenError.badURL }

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw TokenError.server("token.php 无 HTTP 响应") }
        guard (200..<300).contains(http.statusCode) else { throw TokenError.http(http.statusCode) }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw TokenError.server("token.php 返回非法 JSON")
        }
        guard json["ok"] as? Bool == true else {
            throw TokenError.server((json["error"] as? String) ?? "token.php 返回失败")
        }
        return json
    }

    // MARK: - 设备激活

    /// 设备注册：GET /java/device/{渠道}/{device}?av1=&h265=，与抓包 11:42 的 200 请求一致。
    /// 尽力而为：失败不阻断登录主流程。
    private func activateDevice(baseUrl: String, device: String) async {
        let root = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(root)/java/device/\(DirectConfig.deviceChannel)/\(device)?av1=&h265=") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(DirectConfig.apiUserAgent(device), forHTTPHeaderField: "User-Agent")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh-Hans;q=0.9", forHTTPHeaderField: "Accept-Language")
        req.setValue("gzip, deflate", forHTTPHeaderField: "Accept-Encoding")
        _ = try? await session.data(for: req)
    }

    // MARK: - 凭证解密 / 上游登录

    /// cipher(base64) → AES-128-ECB 解密 → JSON {"u":..,"p":..}
    private func decryptSecret(_ cipherB64: String) throws -> (String, String) {
        guard let cipher = Data(base64Encoded: cipherB64, options: .ignoreUnknownCharacters),
              let plain = aes128ECBDecrypt(cipher, key: DirectConfig.credentialAESKey),
              let text = String(data: plain, encoding: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
              let u = obj["u"] as? String, let p = obj["p"] as? String,
              !u.isEmpty, !p.isEmpty else {
            throw TokenError.server("凭证解密失败（key 是否与服务器一致？）")
        }
        return (u, p)
    }

    /// 用【手机自身网络出口】POST /java/v2/login，响应是倒序 Base64 的 JSON，取 data.token
    private func loginUpstream(baseUrl: String, device: String, username: String, password: String) async throws -> String {
        // 关键：登录前先用 device 请求 /java/device/{渠道}/{device} 激活设备。
        // 抓包证明浏览器启动顺序为 设备激活 → /java/v2/login；未激活的 device 登录会被伪装成 404。
        await activateDevice(baseUrl: baseUrl, device: device)

        let root = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: root + "/java/v2/login") else {
            throw TokenError.badURL
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        let form = "username=\(DirectConfig.percentEncode(username))&password=\(DirectConfig.percentEncode(password))"
        req.setValue("application/x-www-form-urlencoded;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(form.utf8)
        req.setValue("device=\(device)", forHTTPHeaderField: "Cookie")
        req.setValue(DirectConfig.apiUserAgent(device), forHTTPHeaderField: "User-Agent")
        req.setValue(baseUrl, forHTTPHeaderField: "Origin")
        // 抓包的登录请求不带 Referer；去掉以贴近真实浏览器，避免 WAF 判定为非浏览器
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        req.setValue("zh-CN,zh-Hans;q=0.9", forHTTPHeaderField: "Accept-Language")

        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw TokenError.server("上游登录无响应") }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(120) ?? ""
            throw TokenError.server("上游登录 HTTP \(http.statusCode) · \(snippet)")
        }
        let decoded = try ResponseDecoder.decode(data)
        guard decoded.success else {
            throw TokenError.server("上游登录被拒 code=\(decoded.code) \(decoded.message)")
        }
        let token = decoded.string("token")
        guard !token.isEmpty else { throw TokenError.server("上游登录成功但未返回 token") }
        return token
    }

    /// AES-128-ECB / PKCS7（CommonCrypto，经桥接头暴露）
    private func aes128ECBDecrypt(_ data: Data, key: String) -> Data? {
        let keyData = key.data(using: .ascii)!
        let keyCount = keyData.count
        let inCount = data.count
        var outData = Data(count: inCount + kCCBlockSizeAES128)
        let outCapacity = outData.count
        var outLength: Int = 0
        let status: CCCryptorStatus = keyData.withUnsafeBytes { keyRaw -> CCCryptorStatus in
            data.withUnsafeBytes { inRaw -> CCCryptorStatus in
                outData.withUnsafeMutableBytes { outRaw -> CCCryptorStatus in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES128),
                        CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                        keyRaw.baseAddress, keyCount,
                        nil,
                        inRaw.baseAddress, inCount,
                        outRaw.baseAddress, outCapacity,
                        &outLength
                    )
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return Data(outData.prefix(outLength))
    }

    /// device 兜底：20 位小写字母数字
    private func randomDevice() -> String {
        let pool = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return String((0..<20).compactMap { _ in pool.randomElement() })
    }

    // MARK: - UserDefaults 缓存（只存会话凭证，不存账号密码）

    private func load() -> Credential? {
        guard let token = defaults.string(forKey: kToken),
              let device = defaults.string(forKey: kDevice),
              !token.isEmpty, !device.isEmpty else { return nil }
        return Credential(
            token: token,
            device: device,
            baseUrl: defaults.string(forKey: kBaseUrl) ?? DirectConfig.defaultUpstream,
            imageHost: defaults.string(forKey: kImageHost) ?? DirectConfig.imageHost,
            streamHosts: defaults.stringArray(forKey: kStreamHosts) ?? []
        )
    }

    private func save(_ c: Credential) {
        defaults.set(c.token, forKey: kToken)
        defaults.set(c.device, forKey: kDevice)
        defaults.set(c.baseUrl, forKey: kBaseUrl)
        defaults.set(c.imageHost, forKey: kImageHost)
        defaults.set(c.streamHosts, forKey: kStreamHosts)
    }

    private func clear() {
        [kToken, kDevice, kBaseUrl, kImageHost, kStreamHosts].forEach { defaults.removeObject(forKey: $0) }
    }
}
