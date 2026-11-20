import SwiftUI
import AVFoundation
import UIKit
import Combine

// MARK: - 短剧首页（Tab 内容：搜索 + 热搜 + 3 列竖屏网格 + 无限加载）

struct DramaSeriesView: View {
    @State private var items: [SeriesItem] = []
    @State private var hotWords: [String] = []
    @State private var page = 1
    @State private var more = true
    @State private var isLoading = false
    @State private var error: String?
    @State private var loadID = UUID()
    @State private var searchInput = ""
    /// 非 nil 时 push 到短剧详情页
    @State private var detailTarget: SeriesItem?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // 搜索框：与影视首页同款样式
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(Theme.textSecondary)
                        TextField("搜索短剧", text: $searchInput)
                            .foregroundColor(Theme.textPrimary)
                            .submitLabel(.search)
                            .onSubmit { startSearch(searchInput) }
                        if !searchInput.isEmpty {
                            Button {
                                searchInput = ""
                                loadID = UUID()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(Theme.textSecondary)
                            }
                            .buttonStyle(.plain)
                        }
                        Button("搜索") { startSearch(searchInput) }
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
                            ChipFlowView(texts: hotWords) { w in
                                searchInput = w
                                startSearch(w)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        SectionTitle(text: searchInput.isEmpty ? "短剧推荐" : "「\(searchInput)」的结果")
                        LoadStateView(isLoading: isLoading && items.isEmpty, error: error) {
                            loadID = UUID()
                        }
                        if !items.isEmpty {
                            seriesGrid
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("短剧")
            .navigationBarTitleDisplayMode(.inline)
            .oledBackground()
            .background(SeriesDetailPushLink(target: $detailTarget))
        }
        .navigationViewStyle(.stack)
        .task(id: loadID) { await reload() }
    }

    /// 3 列竖屏封面网格（与 Android DramaScreen 一致），滚动到底自动翻页
    private var seriesGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                  alignment: .leading, spacing: 14) {
            ForEach(items) { item in
                SeriesCard(item: item) { detailTarget = item }
                    .onAppear {
                        if item.id == items.last?.id && more && !isLoading {
                            Task { await loadMore() }
                        }
                    }
            }
            if more && !items.isEmpty {
                HStack {
                    Spacer()
                    ProgressView().tint(Theme.accent)
                    Spacer()
                }
                .padding(.vertical, 8)
            }
        }
    }

    private func startSearch(_ kw: String) {
        let w = kw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !w.isEmpty else { return }
        searchInput = w
        loadID = UUID()
    }

    private func reload() async {
        page = 1
        more = true
        items = []
        error = nil
        isLoading = true
        // 列表与热搜并行加载
        async let listTask: SeriesPage? = try? seriesPage(page: 1)
        async let hotTask: [String]? = try? searchInput.isEmpty ? DramaSeriesAPI.shared.hotWords() : []
        if let first = await listTask {
            items = first.list
            page = first.page
            more = first.more
        } else if items.isEmpty {
            error = "短剧加载失败，请重试"
        }
        hotWords = await hotTask ?? []
        isLoading = false
    }

    private func seriesPage(page p: Int) async throws -> SeriesPage {
        if searchInput.isEmpty {
            return try await DramaSeriesAPI.shared.lists(page: p)
        }
        return try await DramaSeriesAPI.shared.search(kw: searchInput, page: p)
    }

    private func loadMore() async {
        guard more, !isLoading else { return }
        isLoading = true
        let next = page + 1
        if let r = try? await seriesPage(page: next) {
            page = r.page
            more = r.more
            // 搜索翻页可能重复返回，按 id 去重
            let known = Set(items.map(\.id))
            items += r.list.filter { !known.contains($0.id) }
        }
        isLoading = false
    }
}

/// 短剧卡片：竖屏封面 9:16 + 集数标签（与 Android DramaCard 对齐）
struct SeriesCard: View {
    let item: SeriesItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    // 竖屏封面：固定 9:16 比例
                    GeometryReader { geo in
                        PosterImage(raw: item.cover, height: geo.size.width * 16 / 9, cornerRadius: 10)
                    }
                    .aspectRatio(9 / 16, contentMode: .fit)
                    if !item.epText.isEmpty {
                        Text(item.epText)
                            .font(.system(size: 10))
                            .foregroundColor(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(Color.black.opacity(0.65))
                            .cornerRadius(3)
                            .padding(5)
                    }
                }
                Text(item.title.isEmpty ? "未命名" : item.title)
                    .font(.system(size: 12))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, minHeight: 30, maxHeight: 30, alignment: .topLeading)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
            }
            .background(Theme.surface)
            .cornerRadius(10)
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(Theme.divider.opacity(0.6), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
    }
}

/// 隐藏 NavigationLink：短剧详情页 push 进当前导航栈（iOS 15 兼容）
struct SeriesDetailPushLink: View {
    @Binding var target: SeriesItem?

    var body: some View {
        NavigationLink(
            destination: SeriesDetailView(item: target ?? SeriesItem(id: "", title: "", cover: "", epText: "", intro: "")),
            isActive: Binding(
                get: { target != nil },
                set: { active in if !active { target = nil } }
            )
        ) { EmptyView() }
    }
}

// MARK: - 短剧详情 + 播放页（竖屏视频竖向放大全屏 + 选集浮层）

final class SeriesDetailViewModel: ObservableObject {
    @Published var detail: SeriesDetail?
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var currentEpisode: SeriesEpisode?

    let player = AVPlayer()
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    /// 视频宽高比（nil=未知）；<1 为竖屏视频
    @Published var videoRatio: Double?
    /// 竖屏视频：全屏时不旋转横屏，改为竖屏铺满放大
    var isPortraitVideo: Bool {
        if let r = videoRatio, r > 0 { return r < 1 }
        // 比例未知时默认按竖屏（短剧绝大多数为 9:16）
        return true
    }

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var sizeObservation: AnyCancellable?
    private var rateObservation: AnyCancellable?

    func load(id: String) {
        isLoading = true
        errorMessage = nil
        Task { [weak self] in
            do {
                let d = try await DramaSeriesAPI.shared.detail(id: id)
                await MainActor.run {
                    guard let self else { return }
                    self.detail = d
                    self.isLoading = false
                    // 默认播第一集
                    if self.currentEpisode == nil {
                        if let first = d.eps.first(where: { !$0.src.isEmpty }) {
                            self.play(first)
                        }
                    }
                }
            } catch {
                await MainActor.run {
                    guard let self else { return }
                    self.isLoading = false
                    self.errorMessage = "短剧详情加载失败：\(error.localizedDescription)"
                }
            }
        }
    }

    /// 播放指定集：src 是带一次性签名的 m3u8，经本机 /dm3u8 代取并改写分片
    func play(_ ep: SeriesEpisode) {
        guard !ep.src.isEmpty else { return }
        currentEpisode = ep
        cleanupObservers()

        let base = LocalProxyServer.shared.dramaM3u8URL(src: ep.src)
        let sep = base.contains("?") ? "&" : "?"
        // 带时间戳避免播放列表缓存命中过期签名
        let urlString = base + sep + "_r=\(Int(Date().timeIntervalSince1970 * 1000))"
        guard let url = URL(string: urlString) else { return }

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)

        // 视频尺寸：决定竖屏全屏还是横屏全屏
        sizeObservation = item.publisher(for: \.presentationSize)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] size in
                guard let self else { return }
                if size.width > 0, size.height > 0 {
                    self.videoRatio = Double(size.width / size.height)
                }
            }
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] observed, _ in
            DispatchQueue.main.async {
                if observed.status == .failed {
                    self?.errorMessage = observed.error?.localizedDescription ?? "播放失败"
                }
            }
        }

        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] t in
            guard let self else { return }
            let s = CMTimeGetSeconds(t)
            self.currentTime = s.isFinite ? s : 0
            let d = self.player.currentItem?.duration.seconds ?? 0
            if d.isFinite && d > 0 { self.duration = d }
        }
        rateObservation = player.publisher(for: \.timeControlStatus)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                self?.isPlaying = (status == .playing)
            }
        player.play()
    }

    func togglePlay() {
        if isPlaying { player.pause() } else { player.play() }
    }

    func seek(to seconds: Double) {
        let target = duration > 0 ? min(max(0, seconds), duration) : max(0, seconds)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
    }

    func cleanup() {
        cleanupObservers()
        player.replaceCurrentItem(with: nil)
    }

    private func cleanupObservers() {
        if let o = timeObserver {
            player.removeTimeObserver(o)
            timeObserver = nil
        }
        statusObservation?.invalidate(); statusObservation = nil
        sizeObservation?.cancel(); sizeObservation = nil
        rateObservation?.cancel(); rateObservation = nil
    }
}

struct SeriesDetailView: View {
    let item: SeriesItem

    @StateObject private var vm = SeriesDetailViewModel()
    @State private var showControls = true
    @State private var isFullscreen = false
    @State private var showEpSheet = false
    @Environment(\.presentationMode) private var presentationMode
    /// 选集浮层自动隐藏定时
    @State private var controlsTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if isFullscreen {
                fullscreenStage
            } else {
                inlineStage
            }
        }
        .navigationBarHidden(true)
        .onAppear {
            vm.load(id: item.id)
        }
        .onDisappear {
            controlsTask?.cancel()
            vm.cleanup()
            if isFullscreen { applyFullscreen(false) }
        }
        // 选集浮层：底部弹出（用户要求选集使用浮层）
        .sheet(isPresented: $showEpSheet) { episodeSheet }
        // 竖屏全屏时隐藏状态栏
        .statusBar(hidden: isFullscreen)
    }

    // MARK: 非全屏：播放器（按视频比例）+ 信息 + 选集入口

    private var inlineStage: some View {
        VStack(spacing: 0) {
            playerBox
                // 比例未知先按竖屏 9:16 占位（短剧绝大多数为竖屏），
                // presentationSize 到达后 vm.videoRatio 更新自动切换真实比例
                .aspectRatio(vm.videoRatio ?? 9 / 16, contentMode: .fit)
                .frame(maxWidth: .infinity)
            infoPanel
        }
        .background(Theme.background)
    }

    private var infoPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let d = vm.detail {
                    Text(d.drama.title.isEmpty ? item.title : d.drama.title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(Theme.textPrimary)
                    if !d.drama.intro.isEmpty {
                        Text(d.drama.intro)
                            .font(.system(size: 12))
                            .foregroundColor(Theme.textSecondary)
                    }
                    if !d.drama.epText.isEmpty {
                        Text(d.drama.epText)
                            .font(.system(size: 12))
                            .foregroundColor(Theme.accent)
                    }
                    // 选集入口：打开浮层
                    Button {
                        showEpSheet = true
                    } label: {
                        HStack {
                            Image(systemName: "rectangle.stack")
                                .font(.system(size: 15))
                                .foregroundColor(Theme.accent)
                            Text("选集 · \(currentEpLabel)")
                                .font(.system(size: 14))
                                .foregroundColor(Theme.textPrimary)
                            Spacer()
                            Text("共\(d.eps.count)集")
                                .font(.system(size: 12))
                                .foregroundColor(Theme.textSecondary)
                            Image(systemName: "chevron.up")
                                .font(.system(size: 12))
                                .foregroundColor(Theme.textSecondary)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(Theme.surfaceElevated)
                        .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                } else if vm.isLoading {
                    HStack {
                        Spacer()
                        ProgressView().tint(Theme.accent)
                        Spacer()
                    }
                    .padding(.top, 20)
                } else if let msg = vm.errorMessage {
                    VStack(spacing: 10) {
                        Text(msg)
                            .font(.footnote)
                            .foregroundColor(Theme.textSecondary)
                            .multilineTextAlignment(.center)
                        Button("重试") { vm.load(id: item.id) }
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(Theme.accent)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 20)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var currentEpLabel: String {
        guard let ep = vm.currentEpisode else { return "未播放" }
        return "第\(ep.epNo)集"
    }

    // MARK: 全屏：竖屏视频竖向放大铺满（不旋转），横屏视频走旋转横屏

    private var fullscreenStage: some View {
        ZStack(alignment: .bottom) {
            playerBox
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            VStack(spacing: 0) {
                if showControls { topBar }
                Spacer().allowsHitTesting(false)
                if showControls { seriesControlBar }
            }
        }
        .ignoresSafeArea()
    }

    // MARK: 播放器画面 + 点击显隐控制

    private var playerBox: some View {
        ZStack {
            PlayerUIView(player: vm.player)
            Color.black.opacity(0.001)
            if vm.errorMessage != nil && vm.currentEpisode == nil {
                Text(vm.errorMessage ?? "")
                    .font(.footnote)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding()
            }
            if let ep = vm.currentEpisode, vm.errorMessage != nil {
                VStack(spacing: 8) {
                    Text("播放失败")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                    Text(vm.errorMessage ?? "")
                        .font(.footnote)
                        .foregroundColor(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                    Button("重试") { vm.play(ep) }
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Theme.accent)
                }
                .padding()
            }
            if vm.isLoading {
                ProgressView().tint(Theme.accent)
            }
        }
        .background(Color.black)
        .contentShape(Rectangle())
        .onTapGesture {
            showControls.toggle()
            scheduleControlsHide()
        }
    }

    private func scheduleControlsHide() {
        controlsTask?.cancel()
        guard showControls else { return }
        controlsTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation { showControls = false }
        }
    }

    // MARK: 顶栏 / 控制条（全屏）

    private var topBar: some View {
        HStack(spacing: 12) {
            Button { presentationMode.wrappedValue.dismiss() } label: {
                HStack(spacing: 3) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                    Text("返回").font(.system(size: 14))
                }
            }
            Text(vm.detail?.drama.title.isEmpty == false
                 ? (vm.detail?.drama.title ?? item.title)
                 : item.title)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(1)
            Spacer()
        }
        .foregroundColor(.white)
        .padding()
        .background(Color.black.opacity(0.6))
    }

    private var seriesControlBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Text(timeString(vm.currentTime))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.white)
                Slider(
                    value: Binding(get: { vm.currentTime }, set: { vm.seek(to: $0) }),
                    in: 0...max(vm.duration, 1)
                )
                .tint(Theme.accent)
                Text(timeString(vm.duration))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(.white)
            }
            HStack(spacing: 22) {
                Button { vm.togglePlay() } label: {
                    Image(systemName: vm.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 24))
                }
                Button { showEpSheet = true } label: {
                    Image(systemName: "rectangle.stack")
                        .font(.system(size: 18))
                }
                Spacer()
                Button { applyFullscreen(!isFullscreen) } label: {
                    Text(isFullscreen ? "退出全屏" : "全屏")
                        .font(.system(size: 13, weight: .medium))
                }
            }
            .font(.system(size: 18))
            .foregroundColor(.white)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(Color.black.opacity(0.78))
    }

    // MARK: 选集浮层

    private var episodeSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("选集")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(Theme.textPrimary)
                if let d = vm.detail {
                    Text("共\(d.eps.count)集")
                        .font(.system(size: 12))
                        .foregroundColor(Theme.textSecondary)
                }
                Spacer()
                Button {
                    showEpSheet = false
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 20))
                        .foregroundColor(Theme.textSecondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

            if let d = vm.detail, !d.eps.isEmpty {
                // 6 列集数网格（与 Android 选集浮层一致）
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6),
                          spacing: 8) {
                    ForEach(d.eps) { ep in
                        let selected = vm.currentEpisode?.id == ep.id
                        Button {
                            vm.play(ep)
                            showEpSheet = false
                        } label: {
                            Text(ep.epNo.isEmpty ? "-" : ep.epNo)
                                .font(.system(size: 13, weight: selected ? .bold : .regular))
                                .foregroundColor(selected ? .black : Theme.textPrimary)
                                .frame(maxWidth: .infinity)
                                .frame(height: 42)
                                .background(selected ? Theme.accent : Theme.surfaceElevated)
                                .cornerRadius(6)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            } else {
                Text("暂无剧集")
                    .font(.footnote)
                    .foregroundColor(Theme.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 120)
            }
            Spacer(minLength: 0)
        }
        .background(Theme.background)
    }

    // MARK: 全屏方向

    private func applyFullscreen(_ enable: Bool) {
        isFullscreen = enable
        guard enable, !vm.isPortraitVideo else {
            // 竖屏视频：保持竖屏，只铺满放大（状态栏已隐藏），不旋转
            if !enable { restoreOrientation() }
            return
        }
        // 横屏视频：与影视播放页一致的旋转横屏
        if #available(iOS 16.0, *) {
            let mask: UIInterfaceOrientationMask = .landscape
            if let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).first {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
                scene.windows.first?.rootViewController?
                    .setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        } else {
            UIDevice.current.setValue(UIInterfaceOrientation.landscapeRight.rawValue,
                                      forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private func restoreOrientation() {
        if #available(iOS 16.0, *) {
            if let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).first {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait))
            }
        } else {
            UIDevice.current.setValue(UIInterfaceOrientation.portrait.rawValue,
                                      forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private func timeString(_ s: Double) -> String {
        guard s.isFinite, s >= 0 else { return "0:00" }
        let total = Int(s)
        let m = (total % 3600) / 60
        let sec = total % 60
        return String(format: "%d:%02d", m, sec)
    }
}
