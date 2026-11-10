import Foundation

/// 服务端 update.json 里与本端有关的信息。
/// downloadURL 完全来自接口，客户端不内置任何下载地址；且只在点击按钮时使用，不展示给用户。
struct AppUpdateInfo {
    let versionName: String
    let versionCode: Int
    let downloadURL: String
    /// 更新日志：强制更新弹窗里唯一展示的内容
    let changelog: String
}

enum UpdateCheckResult {
    /// 有更新：地址由接口返回，交由 UI 弹窗确认后再用浏览器打开
    case available(AppUpdateInfo)
    case upToDate
    case failed(String)
}

/// 检查更新：拉 update.json → 比对本机版本 → 返回下载地址。
/// 与 Android UpdateRepository 读同一个接口、同一套字段（兼容驼峰 / 下划线）。
final class UpdateService {
    static let shared = UpdateService()

    private init() {}

    func check() async -> UpdateCheckResult {
        guard let url = URL(string: DirectConfig.updateURL) else {
            return .failed("更新地址配置无效")
        }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.timeoutInterval = 12

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                return .failed("更新服务返回异常")
            }
            guard let info = Self.parse(data) else {
                return .failed("更新信息格式不正确")
            }
            return Self.isNewer(info) ? .available(info) : .upToDate
        } catch {
            return .failed("检查更新失败，请检查网络")
        }
    }

    /// 解析 update.json，兼容顶层直出与 { ok, data } 包装。
    private static func parse(_ data: Data) -> AppUpdateInfo? {
        guard let json = try? JSONSerialization.jsonObject(with: data),
              let root = json as? [String: Any] else { return nil }
        let node = (root["data"] as? [String: Any]) ?? root

        let versionName = firstString(node, ["versionName", "version_name"]) ?? ""
        let versionCode = firstInt(node, ["versionCode", "version_code"]) ?? 0

        // iOS 专用地址优先；服务端未配置 iosUrl 时回退通用下载地址。
        // 无论如何，地址都由接口提供。
        let address = firstString(
            node,
            ["iosUrl", "ios_url", "ipaUrl", "ipa_url", "downloadUrl", "download_url",
             "apkUrl", "apk_url", "url"]
        )
        guard let address, address.hasPrefix("http://") || address.hasPrefix("https://") else {
            return nil
        }
        let changelog = firstString(
            node,
            ["changelog", "changeLog", "change_log", "updateLog", "desc", "description"]
        ) ?? ""

        return AppUpdateInfo(
            versionName: versionName,
            versionCode: versionCode,
            downloadURL: address,
            changelog: changelog
        )
    }

    /// 版本号优先按 versionName 逐段比较；本机 plist 读不到时按 versionCode 比较。
    /// 【注意】这里不能写死兜底版本号，否则每次升版本都会漏改、导致"永远判断为已最新"。
    private static func isNewer(_ info: AppUpdateInfo) -> Bool {
        let localName = Bundle.main.object(
            forInfoDictionaryKey: "CFBundleShortVersionString"
        ) as? String ?? ""
        let localCode = Int(
            Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        ) ?? 0

        if !info.versionName.isEmpty, !localName.isEmpty {
            return compareVersion(info.versionName, localName) == .orderedDescending
        }
        return info.versionCode > 0 && localCode > 0 && info.versionCode > localCode
    }

    /// 逐段比较，段数不足补 0，非数字段忽略。
    private static func compareVersion(_ a: String, _ b: String) -> ComparisonResult {
        let left = a.split(separator: ".").compactMap { Int(String($0)) }
        let right = b.split(separator: ".").compactMap { Int(String($0)) }
        guard !left.isEmpty, !right.isEmpty else { return .orderedSame }
        let count = max(left.count, right.count)
        for i in 0..<count {
            let l = i < left.count ? left[i] : 0
            let r = i < right.count ? right[i] : 0
            if l > r { return .orderedDescending }
            if l < r { return .orderedAscending }
        }
        return .orderedSame
    }

    private static func firstString(_ node: [String: Any], _ keys: [String]) -> String? {
        for key in keys {
            if let s = node[key] as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty {
                return s.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return nil
    }

    private static func firstInt(_ node: [String: Any], _ keys: [String]) -> Int? {
        for key in keys {
            if let n = node[key] as? NSNumber { return n.intValue }
            if let s = node[key] as? String, let v = Int(s) { return v }
        }
        return nil
    }
}
