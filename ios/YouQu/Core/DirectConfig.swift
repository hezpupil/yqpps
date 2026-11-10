import Foundation

/// APP 内置直连常量。
///
/// 【凭证边界 · 重要】
/// 上游账号密码不在这里、也不在客户端任何位置。客户端只向服务器 token.php 换取一次性
/// token + device；账号密码始终留在服务器 config.php。
/// 本文件里的 AES key 是「图片内容解混淆密钥」，不是账号凭证，与 token 无关。
enum DirectConfig {

    /// 上游业务站点兜底值；token.php 下发的 base_url 会覆盖它
    static let defaultUpstream = "https://sixth.xxcjpt.com"

    /// token.php 地址（服务端唯一的入口）
    static let tokenURL = "https://yq-app.yqnb.lol/token.php"

    /// 可选访问密钥，与服务器 config.php 的 token_access_key 对应；空表示不校验
    static let tokenAccessKey = ""

    /// 图片域名白名单（image.php 原始行为）
    static let imageHost = "beta.100njp.com"

    /// 流媒体域名白名单（KEY / 分片）
    static let segmentHosts: Set<String> = ["mail.xxcjpt.com", "dev.100njp.com"]

    /// KEY 相对地址时补齐的主机
    static let keyFallbackHost = "https://mail.xxcjpt.com"

    /// 图片混淆：AES-128-ECB / PKCS7，key 为 16 字节 ASCII
    static let imageAESKey = "976f97d638360cde"

    /// 图片回源 UA（不带 abab 后缀）
    static let iphoneUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /// 本机网关端口候选
    static let ports: [UInt16] = [19025, 19026, 19027, 19028, 19029]

    /// 设备激活云端接口
    static let actVerifyURL = "https://yq-app.yqnb.lol/act/api.php"

    /// 版本更新信息（与 Android SettingsRepository.DEFAULT_UPDATE_URL 一致）。
    /// 具体下载地址由这个接口返回，客户端不写死。
    static let updateURL = "https://yq-app.yqnb.lol/update.json"

    /// 激活接口固定请求令牌（防止接口被穷举调用）
    static let actToken = "yq-act-9f3K7bQ2mW"

    static let deviceCodeLength = 16   // 设备码 16 位 hex（展示成 XXXX-XXXX-XXXX-XXXX）
    static let activationCodeLength = 12 // 激活码 12 位字母数字

    /// 业务接口 UA：iPhone UA 尾部拼 " abab/{device}"
    static func apiUserAgent(_ device: String) -> String { "\(iphoneUA) abab/\(device)" }

    static func trimTrailingSlash(_ s: String) -> String {
        var r = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while r.hasSuffix("/") { r.removeLast() }
        return r
    }

    static func host(of urlString: String) -> String? {
        URL(string: urlString)?.host?.lowercased()
    }

    static func isHTTPS(_ urlString: String) -> Bool {
        urlString.lowercased().hasPrefix("https://")
    }

    static func percentEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }

    // MARK: - base64url（与 PHP b64url() 规则一致）

    static func b64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func b64urlDecode(_ s: String) -> String? {
        var t = s
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let pad = t.count % 4
        if pad != 0 { t += String(repeating: "=", count: 4 - pad) }
        guard let data = Data(base64Encoded: t, options: .ignoreUnknownCharacters) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
