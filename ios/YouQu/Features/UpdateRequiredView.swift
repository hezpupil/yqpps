import SwiftUI
import UIKit

/// 强制更新页（全屏，不可取消）。
///
/// 内容只有：版本号、更新日志、两个跳转按钮与一行必要的说明。
/// 下载地址由 update.json 提供但**不展示**给用户，点击按钮才交给系统打开。
/// 本页由 ContentView 的 fullScreenCover 呈现，配合忽略关闭请求与
/// interactiveDismissDisabled，任何手势 / 返回都无法关掉；从浏览器或巨魔回到 App 仍在页。
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

                // 通用路径：交给 Safari / 系统下载页（普通未签名 IPA 在 Safari 里装不了，
                // 但保留这一入口作为兜底，也便于查看文件）。
                Button {
                    guard let url = URL(string: info.downloadURL) else { return }
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                } label: {
                    Text("前往下载")
                }
                .buttonStyle(AccentButtonStyle())
                .padding(.horizontal, 28)
                .padding(.top, 24)

                // 巨魔路径：直接唤起 TrollStore 下载并永久签名安装未签名 IPA，
                // 这是 iOS 端唯一能真正完成自更新的方式。
                Button {
                    TrollInstaller.install(ipaURL: info.downloadURL)
                } label: {
                    Text("巨魔直装")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(Theme.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(Theme.accent.opacity(0.55), lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 28)
                .padding(.top, 12)

                Text("「巨魔直装」需本机已安装 TrollStore；未安装请使用上方按钮")
                    .font(.system(size: 11))
                    .foregroundColor(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
                    .padding(.top, 10)

                Spacer(minLength: 0)
            }
            .padding(.vertical, 32)
        }
    }
}
