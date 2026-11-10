import SwiftUI
import UIKit
import Foundation

/// 章节标题。
struct SectionTitle: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 18, weight: .bold))
            .foregroundColor(Theme.textPrimary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 通用「加载中 / 出错重试」状态视图。
struct LoadStateView: View {
    let isLoading: Bool
    let error: String?
    let retry: () -> Void

    var body: some View {
        if isLoading {
            VStack(spacing: 12) {
                ProgressView().tint(Theme.accent)
                Text("加载中…").font(.footnote).foregroundColor(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity, minHeight: 140)
        } else if let error {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 30))
                    .foregroundColor(Theme.danger)
                Text(error)
                    .font(.footnote)
                    .foregroundColor(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                Button("重试", action: retry)
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Theme.accent)
            }
            .frame(maxWidth: .infinity, minHeight: 140)
            .padding()
        }
    }
}

/// 封面图：上游图片是 AES 混淆体，必须经本机 /img 端点解密。
///
/// 懒加载（替代 AsyncImage）：卡片真正出现并停留 120ms 才发起请求，快速滑动经过的
/// 卡片不产生任何回源；离屏立刻 cancel 掉在途请求。未加载时显示默认小动图。
struct PosterImage: View {
    /// 上游返回的图片字段（完整 URL 或路径）
    let raw: String
    var height: CGFloat = 150
    var cornerRadius: CGFloat = 10

    @State private var image: UIImage?
    @State private var failed = false
    @State private var loadTask: Task<Void, Never>?

    private var urlString: String { LocalProxyServer.shared.imageURL(raw) }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Theme.surfaceElevated)
            if let uiImage = image {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
            } else if failed {
                Image(systemName: "film")
                    .font(.system(size: 22))
                    .foregroundColor(Theme.textSecondary)
            } else {
                // 默认小动图：加载中 / 尚未进入视口时一直转，图片到了即被盖住
                CoverLoadingMotion()
            }
        }
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
        .clipped()
        .cornerRadius(cornerRadius)
        .onAppear(perform: arm)
        .onDisappear(perform: cancel)
    }

    private func arm() {
        guard loadTask == nil, image == nil, !failed else { return }
        let key = urlString
        if let hit = CoverImageLoader.shared.cached(key) {
            image = hit
            return
        }
        loadTask = Task { @MainActor in
            // 防抖：快速滑动经过的卡片在窗口内就被 cancel，不发起请求
            try? await Task.sleep(nanoseconds: 120_000_000)
            guard !Task.isCancelled else { return }
            let loaded = await CoverImageLoader.shared.load(key)
            guard !Task.isCancelled else { return }
            if let loaded {
                image = loaded
            } else {
                failed = true
            }
            loadTask = nil
        }
    }

    private func cancel() {
        loadTask?.cancel()
        loadTask = nil
        // 重新进入视口时允许重试一次
        if image == nil { failed = false }
    }
}

/// 默认小动图：橙色旋转圆环 + 三颗呼吸小点，与 Android CoverLoadingMotion 同款。
/// 纯矢量动画，不需要 GIF 资源，也不占网络与图片解码开销。
struct CoverLoadingMotion: View {
    @State private var spinning = false
    @State private var pulsing = false

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.14, to: 0.86)
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                .frame(width: 24, height: 24)
                .rotationEffect(.degrees(spinning ? 360 : 0))
                .animation(
                    Animation.linear(duration: 1.1).repeatForever(autoreverses: false),
                    value: spinning
                )
            HStack(spacing: 3) {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .fill(Theme.accent)
                        .frame(width: 3.2, height: 3.2)
                        .opacity(pulsing ? 1 : 0.25)
                        .animation(
                            Animation.easeInOut(duration: 0.5)
                                .repeatForever(autoreverses: true)
                                .delay(Double(index) * 0.15),
                            value: pulsing
                        )
                }
            }
        }
        .frame(width: 40, height: 40)
        .onAppear { spinning = true; pulsing = true }
        .onDisappear { spinning = false; pulsing = false }
    }
}

/// 标签胶囊（热词 / 分类），可点击；未传 action 时点击无副作用。
struct Chip: View {
    let text: String
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(Theme.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Capsule().fill(Theme.surfaceElevated))
        }
        .buttonStyle(.plain)
    }
}

extension VideoItem {
    /// 上游 duration 是秒数（可能是数字或字符串，也可能是已格式化的 "10:00"）。
    /// 统一转成与 Android `formatDuration` 一致的分:秒 / 时:分:秒，
    /// 不再把原始秒数直接摆在封面右下角。
    var durationText: String {
        let raw = duration.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return "" }
        if raw.contains(":") { return raw }
        guard let value = Double(raw), value > 0 else { return "" }
        let total = Int(value)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
        return String(format: "%d:%02d", m, s)
    }
}

/// 视频卡片（首页 / 搜索结果 / 猜你喜欢复用）。
/// 封面固定高度、标题固定两行、分类固定一行 —— 保证两列严格对齐，
/// 与 Android 端 VideoCard 的结构保持一致。
struct VideoCard: View {
    let item: VideoItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    PosterImage(raw: item.image, height: 104, cornerRadius: 12)
                    if !item.durationText.isEmpty {
                        Text(item.durationText)
                            .font(.caption2)
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color.black.opacity(0.65))
                            .cornerRadius(4)
                            .padding(5)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title.isEmpty ? "未命名" : item.title)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(Theme.textPrimary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, minHeight: 34, maxHeight: 34, alignment: .topLeading)
                    // 与 Android 一致：数字 / 分类值前面补上字段名称「分类」
                    Text(item.category.isEmpty ? " " : "分类 \(item.category)")
                        .font(.caption2)
                        .foregroundColor(Theme.textSecondary)
                        .lineLimit(1)
                        .frame(height: 14)
                }
                .padding(.horizontal, 8)
                .padding(.top, 7)
                .padding(.bottom, 8)
            }
            .background(Theme.surface)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Theme.divider.opacity(0.6), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }
}

/// 固定两列对齐网格，与 Android `VideoGrid(GridCells.Fixed(2))` 一致。
/// 用于首页最近更新、搜索结果、播放页猜你喜欢。
struct VideoGridView: View {
    let items: [VideoItem]
    let onTap: (VideoItem) -> Void

    private let columns = [
        GridItem(.flexible(minimum: 0), spacing: 10),
        GridItem(.flexible(minimum: 0), spacing: 10)
    ]

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 10) {
            ForEach(items) { item in
                VideoCard(item: item) { onTap(item) }
            }
        }
    }
}

/// 胶囊标签流式排布：按可用宽度自动换行，一行一行排列，不显示序号。
/// iOS 15 兼容（不使用 iOS 16 才有的 Layout 协议），对齐 Android 的 FlowRow。
/// 热搜词与设置页的搜索记录共用这一套排布。
struct ChipFlowView: View {
    let texts: [String]
    var onTap: (String) -> Void = { _ in }

    private var availableWidth: CGFloat { UIScreen.main.bounds.width - 40 }
    private var rows: [[String]] { Self.makeRows(texts, width: availableWidth) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(rows.indices, id: \.self) { i in
                HStack(spacing: 8) {
                    ForEach(rows[i], id: \.self) { text in
                        Chip(text: text) { onTap(text) }
                            .fixedSize()
                    }
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private static func estimatedWidth(of text: String) -> CGFloat {
        var w: CGFloat = 24   // Capsule 左右各 12
        for ch in text { w += ch.isASCII ? 7.5 : 13.5 }
        return min(w + 8, 240)   // +8 是标签间距
    }

    private static func makeRows(_ texts: [String], width: CGFloat) -> [[String]] {
        var rows: [[String]] = []
        var row: [String] = []
        var used: CGFloat = 0
        for text in texts {
            let itemWidth = estimatedWidth(of: text)
            if !row.isEmpty && used + itemWidth > width {
                rows.append(row)
                row = []
                used = 0
            }
            row.append(text)
            used += itemWidth
        }
        if !row.isEmpty { rows.append(row) }
        return rows
    }
}

/// 热搜词专用薄封装，保持调用点简洁。
struct WordTagsView: View {
    let words: [WordItem]
    let onTap: (String) -> Void

    var body: some View {
        ChipFlowView(texts: words.map(\.word), onTap: onTap)
    }
}
