import SwiftUI
import UIKit

/// 核对中 loading。
struct GateLoadingView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.shield")
                .font(.system(size: 44))
                .foregroundColor(Theme.accent)
            ProgressView().tint(Theme.accent)
            Text("正在核对设备状态…")
                .font(.footnote)
                .foregroundColor(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .oledBackground()
    }
}

/// 激活页：展示设备码（可复制）+ 输入激活码 + 激活按钮 + 「我已被管理员开通，直接进入」。
struct ActivationView: View {
    @Binding var gate: GateState
    let message: String
    let recheck: () async -> Void

    @State private var code = ""
    @State private var isWorking = false
    @State private var localError: String?

    private var deviceCode: String { ActivationService.shared.deviceCode() }

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                Image(systemName: "lock.shield.fill")
                    .font(.system(size: 48))
                    .foregroundColor(Theme.accent)
                Text("设备未开通")
                    .font(.title2.bold())
                    .foregroundColor(Theme.textPrimary)
                Text(message.isEmpty ? "请联系管理员开通本设备" : message)
                    .font(.footnote)
                    .foregroundColor(Theme.textSecondary)
                    .multilineTextAlignment(.center)

                VStack(alignment: .leading, spacing: 10) {
                    Text("设备码（发给管理员开通）")
                        .font(.caption)
                        .foregroundColor(Theme.textSecondary)
                    HStack {
                        Text(deviceCode)
                            .font(.system(size: 20, weight: .bold, design: .monospaced))
                            .foregroundColor(Theme.textPrimary)
                        Spacer()
                        Button {
                            UIPasteboard.general.string = deviceCode
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .foregroundColor(Theme.accent)
                        }
                    }
                    .padding()
                    .card()
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("激活码")
                        .font(.caption)
                        .foregroundColor(Theme.textSecondary)
                    TextField("请输入 12 位激活码", text: $code)
                        .textInputAutocapitalization(.characters)
                        .disableAutocorrection(true)
                        .padding()
                        .background(Theme.surface)
                        .cornerRadius(10)
                        .foregroundColor(Theme.textPrimary)
                }

                if let localError {
                    Text(localError)
                        .font(.footnote)
                        .foregroundColor(Theme.danger)
                        .multilineTextAlignment(.center)
                }

                Button {
                    Task { await activate() }
                } label: {
                    HStack(spacing: 8) {
                        if isWorking { ProgressView().tint(.white) }
                        Text(isWorking ? "验证中…" : "激活")
                    }
                }
                .buttonStyle(AccentButtonStyle(enabled: !isWorking))
                .disabled(isWorking)

                Button {
                    Task { await recheck() }
                } label: {
                    Text("我已被管理员开通，直接进入")
                        .font(.footnote)
                        .foregroundColor(Theme.accent)
                }
            }
            .padding(24)
        }
        .oledBackground()
    }

    @MainActor
    private func activate() async {
        isWorking = true
        localError = nil
        let result = await ActivationService.shared.verify(code: code)
        isWorking = false
        switch result {
        case .success:
            gate = .allowed
        case .failure(let msg):
            localError = msg
        }
    }
}

/// 拦截页：区分断网（fail-closed）与停用 / 解绑。
struct BlockedView: View {
    let offline: Bool
    let message: String
    let recheck: () async -> Void

    @State private var isWorking = false

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: offline ? "wifi.exclamationmark" : "hand.raised.fill")
                .font(.system(size: 50))
                .foregroundColor(offline ? Theme.accent : Theme.danger)
            Text(offline ? "无法连接" : "设备已被停用")
                .font(.title2.bold())
                .foregroundColor(Theme.textPrimary)
            Text(offline
                 ? "无网络，连接后重开"
                 : (message.isEmpty ? "设备未激活或已被管理员停用，请联系管理员" : message))
                .font(.body)
                .foregroundColor(Theme.textSecondary)
                .multilineTextAlignment(.center)

            Button {
                Task { await doRecheck() }
            } label: {
                HStack(spacing: 8) {
                    if isWorking { ProgressView().tint(.white) }
                    Text(isWorking ? "核对中…" : "重新核对")
                }
            }
            .buttonStyle(AccentButtonStyle(enabled: !isWorking))
            .disabled(isWorking)
            .padding(.horizontal, 40)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .oledBackground()
    }

    @MainActor
    private func doRecheck() async {
        isWorking = true
        await recheck()
        isWorking = false
    }
}
