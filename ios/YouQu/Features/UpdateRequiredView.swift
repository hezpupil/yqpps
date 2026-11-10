import SwiftUI
import UIKit

/// 强制更新页（全屏，不可取消）。
///
/// 内容只有：版本号、更新日志、一个「前往下载」按钮。
/// 下载地址由 update.json 提供但**不展示**给用户，点击按钮才交给系统浏览器打开。
/// 本页由 ContentView 的 fullScreenCover 呈现，配合忽略关闭请求与
/// interactiveDismissDisabled，任何手势 / 返回都无法关掉；从浏览器回到 App 仍然在此页。
struct UpdateRequiredView: View {
    let info: AppUpdateInfo

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer(minLength: 0)

                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 54))
                    .foregroundColor(Theme.accent)
                    .padding(.bottom, 20)

                Text("发现新版本")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(Theme.textSecondary)

                Text(info.versionName.isEmpty ? "新版本" : info.versionName)
                    .font(.system(size: 30, weight: .bold))
                    .foregroundColor(Theme.textPrimary)
                    .padding(.top, 6)

                // 唯一的正文内容：更新日志（接口没给时给一句兜底文案）
                ScrollView {
                    Text(info.changelog.isEmpty ? "本次更新包含体验优化与问题修复。" : info.changelog)
                        .font(.system(size: 14))
                        .lineSpacing(6)
                        .foregroundColor(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(18)
                }
                .frame(maxHeight: 260)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Theme.surface)
                )
                .padding(.horizontal, 28)
                .padding(.top, 28)

                Text("请更新后继续使用")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.accent)
                    .padding(.top, 14)

                Button {
                    guard let url = URL(string: info.downloadURL) else { return }
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                } label: {
                    Text("前往下载")
                }
                .buttonStyle(AccentButtonStyle())
                .padding(.horizontal, 28)
                .padding(.top, 24)

                Spacer(minLength: 0)
            }
            .padding(.vertical, 32)
        }
    }
}
