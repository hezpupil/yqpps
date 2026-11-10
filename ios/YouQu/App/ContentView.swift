import SwiftUI
import UIKit

enum MainTab: Hashable {
    case home, hot, settings
}

/// 顶层：先过云端激活门禁，再进入主 TabView。
struct ContentView: View {
    @State private var gate: GateState = .checking
    /// 更新弹窗挂在最外层，启动自动检查与设置页手动检查都能触发
    @ObservedObject private var updatePrompt = UpdatePrompt.shared

    var body: some View {
        Group {
            switch gate {
            case .checking:
                GateLoadingView()
            case .allowed:
                MainTabs()
            case .activation(let message):
                ActivationView(gate: $gate, message: message) {
                    await runGate()
                }
            case .blockedNetwork:
                BlockedView(offline: true, message: "") {
                    await runGate()
                }
            case .blockedDisabled(let message):
                BlockedView(offline: false, message: message) {
                    await runGate()
                }
            }
        }
        .oledBackground()
        .task { await runGate() }
        // 全 App 唯一的更新弹窗：地址完全来自 update.json，点「前往下载」才交给浏览器
        .alert(
            "发现新版本",
            isPresented: Binding(
                get: { updatePrompt.info != nil },
                set: { shown in if !shown { updatePrompt.info = nil } }
            ),
            presenting: updatePrompt.info
        ) { info in
            Button("前往下载") {
                guard let url = URL(string: info.downloadURL) else { return }
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
            Button("稍后再说", role: .cancel) { }
        } message: { info in
            let label = info.versionName.isEmpty ? "新版本" : info.versionName
            Text("发现 \(label)\n下载地址：\(info.downloadURL)\n点击「前往下载」将在浏览器中打开。")
        }
    }

    /// 每次启动：上报设备信息 → 云端 status 核对。断网 / 不可达一律拦截（fail-closed）。
    @MainActor
    private func runGate() async {
        gate = .checking
        await ActivationService.shared.report()
        let status = await ActivationService.shared.checkStatus()
        gate = status
        // 门禁通过后自动检查一次更新，与 Android AppNavHost 的 fromUser=false 对齐。
        // 用独立 Task 起，不阻塞主界面显示；UpdatePrompt 保证一次启动只请求一次。
        if case .allowed = status {
            Task { await UpdatePrompt.shared.autoCheckIfNeeded() }
        }
    }
}

struct MainTabs: View {
    @State private var tab: MainTab = .home

    var body: some View {
        TabView(selection: $tab) {
            HomeView()
                .tabItem { Label("首页", systemImage: "house.fill") }
                .tag(MainTab.home)

            HotView()
                .tabItem { Label("热榜", systemImage: "flame.fill") }
                .tag(MainTab.hot)

            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape.fill") }
                .tag(MainTab.settings)
        }
        .accentColor(Theme.accent)
        // 进入主界面后向上游做一次设备激活上报（失败静默）
        .task { _ = await DramaAPI.shared.deviceActive() }
        // 搜索页 / 播放页都是各 Tab 自己 NavigationView 里的独立页面（push），
        // 不再用 sheet / fullScreenCover，返回时上一页的状态原样保留。
        // 自动检查更新由 ContentView 的 runGate 统一触发，避免本视图重建时重复请求。
    }
}
