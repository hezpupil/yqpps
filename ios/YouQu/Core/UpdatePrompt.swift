import Combine
import Foundation

/// 全 App 唯一的「发现新版本」状态。
///
/// 冷启动自动检查与设置页手动检查共用同一个入口，避免两处弹窗互相覆盖。
/// 自动检查与 Android `AppNavHost` 的 `check(fromUser = false)` 对齐：
/// 失败、已是最新版本一律静默，只有确实发现新版本才弹窗。
///
/// 【强制更新】一旦 present，本次 App 进程内不再清除 info：
/// ContentView 用 fullScreenCover 全屏呈现，且忽略所有关闭请求，
/// 点按钮只负责跳浏览器，回到 App 仍然是这个页面。
final class UpdatePrompt: ObservableObject {
    static let shared = UpdatePrompt()

    private init() {}

    /// 非空即弹窗。强制更新：不提供任何清除入口，只有进程重启后重新检查才可能变化。
    @Published var info: AppUpdateInfo?

    /// 一次 App 启动只自动检查一次：门禁重新核验、视图重建都不会重复请求。
    private var autoChecked = false

    func autoCheckIfNeeded() async {
        let shouldCheck = await MainActor.run { () -> Bool in
            if autoChecked { return false }
            autoChecked = true
            return true
        }
        guard shouldCheck else { return }

        let result = await UpdateService.shared.check()
        if case .available(let info) = result {
            await MainActor.run { present(info) }
        }
        // .upToDate / .failed：自动检查不打扰用户，也不写 message
    }

    /// 设置页手动检查发现新版本时调用，复用同一个弹窗。
    @MainActor
    func present(_ info: AppUpdateInfo) {
        // 地址无效时宁可不弹，也不给用户一个打不开的按钮
        guard URL(string: info.downloadURL) != nil else { return }
        self.info = info
    }
}
