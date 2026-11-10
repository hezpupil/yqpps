import SwiftUI

struct HotView: View {
    @State private var words: [WordItem] = []
    @State private var isLoading = false
    @State private var error: String?
    @State private var loadID = UUID()

    /// 非 nil 时 push 到独立搜索结果页。
    @State private var searchKeyword: String?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    SectionTitle(text: "热搜榜")
                    LoadStateView(isLoading: isLoading, error: error) { loadID = UUID() }
                    // 不显示序号：关键词用椭圆胶囊背景，按宽度自动一行一行排列
                    WordTagsView(words: words) { w in
                        openSearchPage(keyword: $searchKeyword, w)
                    }
                }
                .padding()
            }
            .navigationTitle("热榜")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarHidden(false)
            .oledBackground()
            .background(SearchPushLink(keyword: $searchKeyword))
        }
        .navigationViewStyle(.stack)
        .task(id: loadID) { await load() }
    }

    @MainActor
    private func load() async {
        isLoading = true
        error = nil
        let result = try? await DramaAPI.shared.hotWords()
        if let result {
            words = result
        } else {
            error = "加载失败，请检查网络"
        }
        isLoading = false
    }
}
