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

/// 凭证提供者：向服务器 token.php 取 token + device，用 UserDefaults 缓存。
/// 失效（鉴权错误）时由 UpstreamClient 带 forceRefresh=true 强制服务器重新登录。
actor TokenProvider {
    static let shared = TokenProvider()

    private let defaults = UserDefaults.standard
    private var cached: Credential?

    private let kToken = "yq.direct.token"
    private let kDevice = "yq.direct.device"
    private let kBaseUrl = "yq.direct.base_url"
    private let kImageHost = "yq.direct.image_host"
    private let kStreamHosts = "yq.direct.stream_hosts"

    func get(forceRefresh: Bool = false) async throws -> Credential {
        if !forceRefresh {
            if let cached { return cached }
            if let c = load() {
                cached = c
                return c
            }
        }
        let c = try await fetch(forceRefresh: forceRefresh)
        cached = c
        save(c)
        return c
    }

    /// 清空缓存，下次取凭证会重新走网络
    func invalidate() {
        cached = nil
        clear()
    }

    // MARK: - 网络

    private func fetch(forceRefresh: Bool) async throws -> Credential {
        guard var comps = URLComponents(string: DirectConfig.tokenURL) else { throw TokenError.badURL }
        var items: [URLQueryItem] = []
        if forceRefresh { items.append(URLQueryItem(name: "refresh", value: "1")) }
        items.append(URLQueryItem(name: "_t", value: String(Int(Date().timeIntervalSince1970 * 1000))))
        if !DirectConfig.tokenAccessKey.isEmpty {
            items.append(URLQueryItem(name: "key", value: DirectConfig.tokenAccessKey))
        }
        comps.queryItems = items
        guard let url = comps.url else { throw TokenError.badURL }

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw TokenError.server("token.php 无 HTTP 响应") }
        guard (200..<300).contains(http.statusCode) else { throw TokenError.http(http.statusCode) }
        guard let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw TokenError.server("token.php 返回非法 JSON")
        }
        let ok = json["ok"] as? Bool ?? false
        guard ok else {
            throw TokenError.server((json["error"] as? String) ?? "token.php 返回失败")
        }
        let token = json["token"] as? String ?? ""
        let device = json["device"] as? String ?? ""
        guard !token.isEmpty, !device.isEmpty else {
            throw TokenError.server("token.php 未返回 token/device")
        }
        return Credential(
            token: token,
            device: device,
            baseUrl: DirectConfig.trimTrailingSlash(
                (json["base_url"] as? String) ?? DirectConfig.defaultUpstream
            ),
            imageHost: (json["image_host"] as? String) ?? DirectConfig.imageHost,
            streamHosts: (json["stream_hosts"] as? [String]) ?? []
        )
    }

    // MARK: - UserDefaults 缓存

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
