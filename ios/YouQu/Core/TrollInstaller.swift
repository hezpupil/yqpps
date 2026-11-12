import Foundation
import UIKit

/// TrollStore（巨魔）直装。
///
/// 官方 URL scheme（opa334/TrollStore README「URL Scheme」节，1.3+）：
///     apple-magnifier://install?url=<IPA 地址>
/// TrollStore 故意复用系统「放大器」的 scheme，而不是注册独有的 trollstore://，
/// 目的就是让越狱/巨魔检测无法识别它。
///
/// 实现依据（TrollStore/TSSceneDelegate.m）：
///     NSURLComponents* components = [NSURLComponents componentsWithURL:url ...];
///     if ([components.host isEqualToString:@"install"]) {
///         for (NSURLQueryItem* item in components.queryItems) {
///             if ([item.name isEqualToString:@"url"]) { URLStringToInstall = item.value; }
///         }
///         NSURL* URLToInstall = [NSURL URLWithString:URLStringToInstall];
///         [TSInstallationController handleAppInstallFromRemoteURL:URLToInstall ...];
///     }
/// queryItems 会自动做百分号解码，所以 IPA 地址必须先编码再拼接，
/// 否则 https:// 里的 `:` `/` 以及将来可能带的查询参数会破坏 URL 结构。
///
/// 【为什么不自动判断设备有没有巨魔】
/// 系统「放大器」本身也注册了 apple-magnifier，因此 canOpenURL 恒为 true，
/// 无法区分；TrollStore 官方也没有提供检测 API。未装巨魔的设备点了只会打开
/// 放大镜（无害但无效），所以按钮由用户在强制更新页自行选择，并配文字说明。
enum TrollInstaller {
    private static let scheme = "apple-magnifier"

    /// RFC 3986 unreserved：字母、数字、- . _ ~，其余一律百分号编码。
    private static let allowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    /// 由 update.json 下发的 IPA 直链构造巨魔安装 URL。地址不内置、不展示给用户。
    static func installURL(for ipaURL: String) -> URL? {
        let address = ipaURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard address.hasPrefix("https://") || address.hasPrefix("http://"),
              let encoded = address.addingPercentEncoding(withAllowedCharacters: allowed),
              let url = URL(string: "\(scheme)://install?url=\(encoded)") else {
            return nil
        }
        return url
    }

    /// 唤起 TrollStore 下载并安装 IPA；失败时什么都不做，
    /// 用户仍可回到强制更新页点「前往下载」。
    static func install(ipaURL: String) {
        guard let url = installURL(for: ipaURL) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }
}
