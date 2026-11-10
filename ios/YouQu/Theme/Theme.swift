import SwiftUI

/// OLED 暗色主题：与 Android 端视觉一致。
/// 背景 #0A0A0C，卡片 #151518，强调橙 #F97316。
enum Theme {
    static let background = Color(hex: 0x0A0A0C)
    static let surface = Color(hex: 0x151518)
    static let surfaceElevated = Color(hex: 0x1E1E23)
    static let accent = Color(hex: 0xF97316)
    static let accentSoft = Color(hex: 0xF97316, alpha: 0.16)
    static let textPrimary = Color(hex: 0xF5F5F7)
    static let textSecondary = Color(hex: 0x9A9AA2)
    static let divider = Color(hex: 0x2A2A30)
    static let danger = Color(hex: 0xEF4444)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

/// 全局 OLED 背景：铺满安全区域。
struct OLEDBackground: ViewModifier {
    func body(content: Content) -> some View {
        content.background(Theme.background.ignoresSafeArea())
    }
}

extension View {
    func oledBackground() -> some View { modifier(OLEDBackground()) }
}

/// 橙色主按钮样式。
struct AccentButtonStyle: ButtonStyle {
    var enabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(enabled ? Theme.accent : Theme.surfaceElevated)
            )
            .opacity(configuration.isPressed ? 0.85 : 1.0)
    }
}

/// 卡片背景。
struct CardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Theme.surface)
            )
    }
}

extension View {
    func card() -> some View { modifier(CardModifier()) }
}
