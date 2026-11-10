import Foundation

/// HLS 播放列表改写（等价于 PHP 的 m3u8.php）。
///
/// 播放列表本身是明文（分片仍是标准 AES-128，由 AVPlayer 自行解密）。
/// 但 KEY 与分片必须用 abab/{device} UA + Cookie 才能取，AVPlayer 无法自定义这些头，
/// 所以把 URL 全部改写到本机 /seg?u=<base64url> 由 LocalProxyServer 代取。
enum M3u8Rewriter {

    static func rewrite(playlist: String, playlistURL: String, localBase: String, vid: String) -> String {
        let base = URL(string: playlistURL)
        var out: [String] = []
        for raw in lines(playlist) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.hasPrefix("#") {
                if let inner = firstURIValue(in: line) {
                    let abs = absolutize(inner, base)
                    out.append(line.replacingOccurrences(of: inner, with: segURL(abs, localBase, vid)))
                } else {
                    out.append(line)
                }
            } else {
                out.append(segURL(absolutize(line, base), localBase, vid))
            }
        }
        return out.joined(separator: "\n") + "\n"
    }

    /// 播放列表里所有分片 / KEY 的绝对上游地址（供 /seg 拿到 4xx 时重新拉列表换新地址用）
    static func absoluteSegmentURLs(playlist: String, playlistURL: String) -> [String] {
        let base = URL(string: playlistURL)
        var urls: [String] = []
        for raw in lines(playlist) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if line.hasPrefix("#") {
                if let inner = firstURIValue(in: line) { urls.append(absolutize(inner, base)) }
            } else {
                urls.append(absolutize(line, base))
            }
        }
        return urls
    }

    private static func lines(_ playlist: String) -> [String] {
        playlist.replacingOccurrences(of: "\r", with: "")
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
    }

    /// 取 #EXT-X-KEY 行里的 URI="..."
    private static func firstURIValue(in line: String) -> String? {
        guard let r = line.range(of: "URI=\"") else { return nil }
        let rest = line[r.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return String(rest[..<end])
    }

    private static func segURL(_ absoluteURL: String, _ localBase: String, _ vid: String) -> String {
        let b = DirectConfig.trimTrailingSlash(localBase)
        // 附加 vid 便于 /seg 在签名过期（4xx）时重新拉播放列表换新地址重试一次
        return "\(b)/seg?u=\(DirectConfig.b64url(absoluteURL))&vid=\(DirectConfig.percentEncode(vid))"
    }

    /// 相对地址解析：优先按播放列表 host，KEY 的 "/" 开头形式按 mail.xxcjpt.com 补齐
    private static func absolutize(_ raw: String, _ base: URL?) -> String {
        if raw.hasPrefix("http://") || raw.hasPrefix("https://") { return raw }
        if raw.hasPrefix("//") { return "https:" + raw }
        if raw.hasPrefix("/") {
            if let host = base?.host?.lowercased(),
               DirectConfig.segmentHosts.contains(host),
               let scheme = base?.scheme {
                return "\(scheme)://\(host)" + raw
            }
            return DirectConfig.keyFallbackHost + raw
        }
        guard let b = base else { return raw }
        return URL(string: raw, relativeTo: b)?.absoluteURL.absoluteString ?? raw
    }
}
