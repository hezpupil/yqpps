import SwiftUI

/// 独立搜索结果页：由 NavigationLink push 进来，不是浮层。
/// 状态放在 SearchSession 中，push 到播放页再返回时结果 / 页码 / 排序全部保留。
struct SearchView: View {
    let initialKeyword: String

    @StateObject private var session = SearchSession.shared
    /// 非 nil 时 push 到独立播放页。
    @State private var playTarget: PlayTarget?

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                TextField("搜索影片", text: $session.input)
                    .padding(10)
                    .background(Theme.surface)
                    .cornerRadius(10)
                    .foregroundColor(Theme.textPrimary)
                    .onSubmit { session.performSearch() }
                Button("搜索") { session.performSearch() }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Theme.accent)
            }
            .padding(.horizontal)

            HStack(spacing: 8) {
                orderButton("最新", 9)
                orderButton("最热", 3)
                orderButton("最久", 1)
                Spacer()
            }
            .padding(.horizontal)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    LoadStateView(isLoading: session.isLoading, error: session.error) { session.retry() }
                    VideoGridView(items: session.results) { item in
                        playTarget = PlayTarget(vid: item.id, title: item.title)
                    }
                    if session.searched && !session.isLoading && session.error == nil {
                        HStack {
                            Button("上一页") { session.prevPage() }
                                .disabled(session.page <= 1)
                            Spacer()
                            Text("\(session.page) / \(session.pages)")
                                .font(.caption)
                                .foregroundColor(Theme.textSecondary)
                            Spacer()
                            Button("下一页") { session.nextPage() }
                                .disabled(session.page >= session.pages)
                        }
                        .font(.caption)
                        .foregroundColor(Theme.accent)
                        .padding(.vertical, 8)
                    }
                }
                .padding()
            }
        }
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        // 播放页会隐藏系统导航栏；这里显式恢复，避免 iOS 15 pop 回来后标题栏丢失
        .navigationBarHidden(false)
        .oledBackground()
        // 播放页再 push 一层，返回时本页仍在导航栈里
        .background(PlayerPushLink(target: $playTarget))
        .onAppear {
            if !initialKeyword.isEmpty {
                session.start(keyword: initialKeyword)
            }
        }
    }

    @ViewBuilder
    private func orderButton(_ label: String, _ value: Int) -> some View {
        Button {
            session.setOrder(value)
        } label: {
            Text(label)
                .font(.caption)
                .foregroundColor(session.order == value ? .white : Theme.textSecondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(session.order == value ? Theme.accent : Theme.surfaceElevated))
        }
        .buttonStyle(.plain)
    }
}
