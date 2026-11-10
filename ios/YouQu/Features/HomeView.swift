import SwiftUI

struct HomeView: View {
    @State private var hotWords: [WordItem] = []
    @State private var recent: [VideoItem] = []
    @State private var isLoading = false
    @State private var error: String?
    @State private var loadID = UUID()

    @State private var searchInput: String = ""
    /// 非 nil 时 push 到独立搜索结果页；nil 时回到首页。
    @State private var searchKeyword: String?
    /// 非 nil 时 push 到独立播放页。
    @State private var playTarget: PlayTarget?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // 顶部搜索框：与 Android 首页 SearchField 对齐，不再单独占一个导航 Tab
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(Theme.textSecondary)
                        TextField("搜索影片", text: $searchInput)
                            .foregroundColor(Theme.textPrimary)
                            .submitLabel(.search)
                            .onSubmit(submitSearch)
                        if !searchInput.isEmpty {
                            Button {
                                searchInput = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(Theme.textSecondary)
                            }
                            .buttonStyle(.plain)
                        }
                        Button("搜索", action: submitSearch)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.accent)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Theme.surface)
                    .cornerRadius(12)

                    if !hotWords.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            SectionTitle(text: "热门搜索")
                            WordTagsView(words: hotWords) { w in
                                openSearchPage(keyword: $searchKeyword, w)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        SectionTitle(text: "最近更新")
                        LoadStateView(isLoading: isLoading, error: error) { loadID = UUID() }
                        VideoGridView(items: recent) { item in
                            playTarget = PlayTarget(vid: item.id, title: item.title)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("友趣")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarHidden(false)
            .oledBackground()
            // 隐藏的跳转链接：搜索结果 / 播放都是导航栈里的独立页面，
            // 从播放页返回时首页与搜索结果都不会被重建。
            .background(
                ZStack {
                    SearchPushLink(keyword: $searchKeyword)
                    PlayerPushLink(target: $playTarget)
                }
            )
        }
        .navigationViewStyle(.stack)
        .task(id: loadID) { await load() }
    }

    private func submitSearch() {
        let kw = searchInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kw.isEmpty else { return }
        openSearchPage(keyword: $searchKeyword, kw)
    }

    @MainActor
    private func load() async {
        isLoading = true
        error = nil
        let h = try? await DramaAPI.shared.index()
        let r = try? await DramaAPI.shared.recent()
        hotWords = h ?? []
        recent = r ?? []
        if h == nil && r == nil { error = "加载失败，请检查网络" }
        isLoading = false
    }
}
