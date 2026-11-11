import SwiftUI
import UIKit

/// 设置页：与 Android 端保持同样的分区顺序与信息结构。
/// 版本更新 / 设备信息 / 访问凭证 / 搜索记录 / 播放记录。
/// 刻意不展示本机代理地址（属于内部实现细节，对用户无意义）。
struct SettingsView: View {
    @State private var deviceCode = ActivationService.shared.deviceCode()
    @State private var tokenInfo = "—"
    @State private var isRefreshing = false
    @State private var isActivating = false
    @State private var isCheckingUpdate = false
    @State private var message: String?
    @State private var searches: [String] = HistoryStore.shared.searches()
    @State private var plays: [PlayRecord] = HistoryStore.shared.plays()

    /// 历史记录点击后 push 到独立页面：重新搜索 / 重新播放。
    @State private var searchKeyword: String?
    @State private var playTarget: PlayTarget?

    // 更新弹窗统一挂在 ContentView 顶层，这里只负责发起手动检查。

    private var appVersion: String {
        // CFBundleShortVersionString 由 MARKETING_VERSION 注入（CI 取 Android gradle 的 appVersionName）
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0.25"
    }

    var body: some View {
        NavigationView {
            ZStack {
                List {
                    Section("版本更新") {
                        HStack {
                            Text("当前版本")
                            Spacer()
                            Text(appVersion).foregroundColor(Theme.textSecondary)
                        }
                        // 下载地址由 update.json 接口返回；发现新版本走 ContentView 的统一弹窗
                        Button {
                            Task { await checkUpdate() }
                        } label: {
                            HStack {
                                Text(isCheckingUpdate ? "检查中…" : "检查更新")
                                Spacer()
                                if isCheckingUpdate { ProgressView() }
                            }
                        }
                        .disabled(isCheckingUpdate)
                        .foregroundColor(Theme.accent)
                    }

                    Section("设备信息") {
                        HStack {
                            Text("机器码")
                            Spacer()
                            Text(deviceCode)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(Theme.textSecondary)
                        }
                        Button("复制机器码") {
                            UIPasteboard.general.string = deviceCode
                            message = "机器码已复制"
                        }
                        .foregroundColor(Theme.accent)
                    }

                    Section("访问凭证") {
                        Button {
                            Task { await refreshToken() }
                        } label: {
                            HStack {
                                Text(isRefreshing ? "刷新中…" : "强制刷新访问凭证")
                                Spacer()
                                if isRefreshing { ProgressView() }
                            }
                        }
                        .disabled(isRefreshing)
                        .foregroundColor(Theme.accent)

                        Button {
                            Task { await activateDevice() }
                        } label: {
                            HStack {
                                Text(isActivating ? "上报中…" : "上报设备激活")
                                Spacer()
                                if isActivating { ProgressView() }
                            }
                        }
                        .disabled(isActivating)
                        .foregroundColor(Theme.accent)

                        Text(tokenInfo)
                            .font(.caption)
                            .foregroundColor(Theme.textSecondary)
                    }

                    Section("搜索记录") {
                        if searches.isEmpty {
                            Text("暂无搜索记录")
                                .font(.caption)
                                .foregroundColor(Theme.textSecondary)
                        } else {
                            // 点击关键词直接 push 独立搜索结果页，重新发起搜索
                            ChipFlowView(texts: searches) { kw in
                                openSearchPage(keyword: $searchKeyword, kw)
                            }
                            .padding(.vertical, 4)
                        }
                        Button("清空搜索记录") {
                            HistoryStore.shared.clearSearches()
                            searches = []
                            message = "搜索记录已清空"
                        }
                        .foregroundColor(Theme.accent)
                    }

                    Section("播放记录") {
                        if plays.isEmpty {
                            Text("暂无播放记录")
                                .font(.caption)
                                .foregroundColor(Theme.textSecondary)
                        } else {
                            ForEach(plays) { record in
                                // 点击记录直接 push 独立播放页，重新播放
                                Button {
                                    playTarget = PlayTarget(vid: record.vid, title: record.title)
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "play.circle")
                                            .foregroundColor(Theme.accent)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(record.title)
                                                .font(.system(size: 14))
                                                .foregroundColor(Theme.textPrimary)
                                                .lineLimit(1)
                                            Text(record.dateText)
                                                .font(.caption2)
                                                .foregroundColor(Theme.textSecondary)
                                        }
                                        Spacer(minLength: 4)
                                        Image(systemName: "chevron.right")
                                            .font(.caption)
                                            .foregroundColor(Theme.textSecondary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        Button("清空播放记录") {
                            HistoryStore.shared.clearPlays()
                            plays = []
                            message = "播放记录已清空"
                        }
                        .foregroundColor(Theme.accent)
                    }

                    if let message {
                        Section {
                            Text(message)
                                .font(.caption)
                                .foregroundColor(Theme.accent)
                        }
                    }
                }
                .listStyle(.insetGrouped)

                // 隐藏的跳转链接放在 NavigationView 层级内，不占 List 行
                VStack(spacing: 0) {
                    SearchPushLink(keyword: $searchKeyword)
                    PlayerPushLink(target: $playTarget)
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarHidden(false)
            .oledBackground()
        }
        .navigationViewStyle(.stack)
        // 更新弹窗由 ContentView 统一呈现（自动检查与手动检查共用），此处不再挂 .alert
        .onAppear {
            searches = HistoryStore.shared.searches()
            plays = HistoryStore.shared.plays()
        }
    }

    /// 检查更新：接口给出新版本时弹窗确认，不再直接跳转。
    @MainActor
    private func checkUpdate() async {
        isCheckingUpdate = true
        let result = await UpdateService.shared.check()
        isCheckingUpdate = false
        switch result {
        case .available(let info):
            guard URL(string: info.downloadURL) != nil else {
                message = "下载地址无效"
                return
            }
            UpdatePrompt.shared.present(info)
        case .upToDate:
            message = "已是最新版本"
        case .failed(let reason):
            message = reason
        }
    }

    @MainActor
    private func refreshToken() async {
        isRefreshing = true
        // 只展示 device / base_url，绝不展示任何上游账号密码
        let c = try? await TokenProvider.shared.get(forceRefresh: true)
        tokenInfo = c.map { "device=\($0.device) · \($0.baseUrl)" } ?? "刷新失败，请检查网络"
        message = c == nil ? "凭证刷新失败" : "凭证已刷新"
        isRefreshing = false
    }

    @MainActor
    private func activateDevice() async {
        isActivating = true
        let ok = await DramaAPI.shared.deviceActive()
        message = ok ? "设备激活成功" : "设备激活失败，请检查网络"
        isActivating = false
    }
}
