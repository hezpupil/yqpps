import SwiftUI
import Combine

/// 播放目标：由导航栈 push 到独立播放页（不再是 fullScreenCover）。
struct PlayTarget: Identifiable {
    let id = UUID()
    let vid: String
    let title: String
}

/// 搜索会话状态。
///
/// 刻意放在视图外部：搜索结果页被 push 到播放页时，即使 SwiftUI 重建了
/// SearchView 的实例，关键词 / 结果 / 页码 / 排序也不会丢，返回后接着看。
/// 与安卓端「搜索结果留在返回栈里」的行为一致。
final class SearchSession: ObservableObject {
    static let shared = SearchSession()

    @Published var keyword = ""          // 当前已发起搜索的词
    @Published var input = ""            // 输入框内容
    @Published var order = 9             // 1=最久 3=最热 9=最新
    @Published var page = 1
    @Published var pages = 1
    @Published var results: [VideoItem] = []
    @Published var isLoading = false
    @Published var error: String?
    @Published var searched = false

    private init() {}

    /// 从首页 / 热榜 / 设置页主动打开搜索前调用，保证这次一定重新发起搜索。
    /// 从播放页返回时不会调用，因此结果原样保留。
    func invalidate() {
        searched = false
    }

    /// 携带关键词进入搜索页：首次进入或换了关键词才真正请求。
    func start(keyword: String) {
        let kw = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kw.isEmpty else { return }
        if searched, self.keyword == kw { return }
        self.keyword = kw
        input = kw
        order = 9
        page = 1
        searched = true
        HistoryStore.shared.addSearch(kw)
        run()
    }

    /// 搜索页内点「搜索」按钮 / 键盘回车。
    func performSearch() {
        let kw = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !kw.isEmpty else { return }
        keyword = kw
        page = 1
        searched = true
        HistoryStore.shared.addSearch(kw)
        run()
    }

    func retry() {
        if searched { run() }
    }

    func setOrder(_ value: Int) {
        order = value
        if searched { page = 1; run() }
    }

    func prevPage() {
        guard page > 1 else { return }
        page -= 1
        run()
    }

    func nextPage() {
        guard page < pages else { return }
        page += 1
        run()
    }

    private func run() {
        let kw = input
        let o = order
        let p = page
        isLoading = true
        error = nil
        Task { [weak self] in
            let r = try? await DramaAPI.shared.search(kw: kw, page: p, order: o)
            await MainActor.run {
                guard let self else { return }
                self.isLoading = false
                if let r {
                    self.results = r.list
                    self.page = r.page
                    self.pages = r.pages
                } else {
                    self.error = "搜索失败，请检查网络"
                }
            }
        }
    }
}

/// 隐藏的 NavigationLink：把搜索结果作为独立页面 push 进当前导航栈。
/// keyword 为 nil 表示当前不在搜索页；pop 时自动回填 nil。
struct SearchPushLink: View {
    @Binding var keyword: String?

    var body: some View {
        NavigationLink(
            destination: SearchView(initialKeyword: keyword ?? ""),
            isActive: Binding(
                get: { keyword != nil },
                set: { active in if !active { keyword = nil } }
            )
        ) { EmptyView() }
    }
}

/// 隐藏的 NavigationLink：把播放页作为独立页面 push 进当前导航栈。
struct PlayerPushLink: View {
    @Binding var target: PlayTarget?

    var body: some View {
        NavigationLink(
            destination: PlayerView(
                vid: target?.vid ?? "",
                title: target?.title ?? ""
            ),
            isActive: Binding(
                get: { target != nil },
                set: { active in if !active { target = nil } }
            )
        ) { EmptyView() }
    }
}

/// 打开独立搜索页（统一入口，保证每次主动搜索都重新请求）。
func openSearchPage(keyword: Binding<String?>, _ value: String) {
    let kw = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !kw.isEmpty else { return }
    SearchSession.shared.invalidate()
    keyword.wrappedValue = kw
}
