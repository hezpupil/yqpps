import SwiftUI
import AVFoundation
import UIKit
import Foundation

// PlayTarget 定义在 App/AppNavigation.swift：播放页现在是导航栈里的独立页面。

// MARK: - 播放器状态（AVPlayer 封装）

final class PlayerViewModel: ObservableObject {
    @Published var isPlaying = false
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    @Published var errorMessage: String?
    @Published var reloadToken = 0
    /// 当前实际倍速（长按临时倍速时显示 2.0）
    @Published var rate: Float = 1.0

    let player = AVPlayer()
    /// 心跳用的 playcode，由 show 接口返回；用引用类型承载，Task 里读到的始终是最新的
    var playcode = ""

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var didRetry = false
    /// 常驻倍速（控制条点击循环切换），长按松手后恢复到这里
    private var normalRate: Float = 1.0
    /// 与安卓 SPEED_STEPS 保持一致
    private static let rateSteps: [Float] = [1.0, 1.25, 1.5, 2.0, 0.75]

    func load(vid: String) {
        cleanupObservers()
        errorMessage = nil
        didRetry = false

        // 每次加载都换一个带时间戳的地址，避免播放列表 / 分片缓存命中过期签名
        let base = LocalProxyServer.shared.m3u8URL(vid: vid)
        let sep = base.contains("?") ? "&" : "?"
        let urlString = base + sep + "_r=\(Int(Date().timeIntervalSince1970 * 1000))"
        guard let url = URL(string: urlString) else {
            errorMessage = "播放地址非法"
            return
        }

        let item = AVPlayerItem(url: url)
        player.replaceCurrentItem(with: item)

        // 分片 4xx / 播放失败：自动重新拉一次播放列表换新地址再试一次
        statusObservation = item.observe(\.status) { [weak self] observed, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if observed.status == .failed {
                    if !self.didRetry {
                        self.didRetry = true
                        self.reloadToken += 1
                    } else {
                        self.errorMessage = observed.error?.localizedDescription ?? "播放失败"
                    }
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
        resume()
    }

    /// 按常驻倍速起播 / 恢复（AVPlayer.rate 非 0 即开始播放）
    private func resume() {
        rate = normalRate
        player.rate = normalRate
        isPlaying = true
    }

    func togglePlay() {
        if isPlaying { pause() } else { resume() }
    }

    /// 点击倍速文字循环切换常驻倍速
    func cycleRate() {
        let idx = Self.rateSteps.firstIndex(of: normalRate) ?? 0
        normalRate = Self.rateSteps[(idx + 1) % Self.rateSteps.count]
        rate = normalRate
        if isPlaying { player.rate = normalRate }
    }

    /// 长按临时 2 倍速（仅播放中生效）
    func beginLongPressSpeed() {
        guard isPlaying else { return }
        rate = 2.0
        player.rate = 2.0
    }

    /// 松手恢复常驻倍速
    func endLongPressSpeed() {
        rate = normalRate
        player.rate = isPlaying ? normalRate : 0
    }

    func seek(to seconds: Double) {
        let target = max(0, seconds)
        let clamped = duration > 0 ? min(target, duration) : target
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600))
    }

    func seek(by delta: Double) {
        seek(to: CMTimeGetSeconds(player.currentTime()) + delta)
    }

    func pause() {
        player.pause()
        isPlaying = false
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
        statusObservation?.invalidate()
        statusObservation = nil
    }
}

// MARK: - AVPlayerLayer 承载 UIView

struct PlayerUIView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerContainerView {
        let v = PlayerContainerView()
        v.player = player
        return v
    }

    func updateUIView(_ uiView: PlayerContainerView, context: Context) {
        uiView.player = player
    }
}

final class PlayerContainerView: UIView {
    private let playerLayer = AVPlayerLayer()

    var player: AVPlayer? {
        get { playerLayer.player }
        set { playerLayer.player = newValue }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
        layer.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}

// MARK: - 播放页（AVPlayer + 自绘控制层 + 手势层）

struct PlayerView: View {
    let vid: String
    let title: String

    @StateObject private var vm = PlayerViewModel()
    @State private var showControls = true
    @State private var currentTitle = ""
    @State private var currentVid = ""
    @State private var guess: [VideoItem] = []
    @State private var heartbeatTask: Task<Void, Never>?
    // push 进来的页面用 presentationMode 返回，iOS 15 上最稳妥
    @Environment(\.presentationMode) private var presentationMode

    // MARK: 手势与全屏状态
    /// 与安卓一致：约 12pt = 1 秒
    private let pxPerSecond: CGFloat = 12
    @State private var isFullscreen = false
    @State private var isSeeking = false
    @State private var seekSeconds: Double = 0
    @State private var seekBaseTime: Double = 0
    @State private var isLongPressSpeed = false
    /// 本次手势已产生动作（长按或滑动），松手时不再当作点击
    @State private var gestureActed = false
    @State private var longPressTask: Task<Void, Never>?
    /// 播放页在前台期间持有回源闸门，保证 begin/end 严格配对
    @State private var playbackGateHeld = false

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if isFullscreen { fullscreenStage } else { inlineStage }
        }
        // 用自绘顶栏，隐藏系统导航栏避免出现两个返回按钮
        .navigationBarHidden(true)
        .onAppear {
            if !playbackGateHeld {
                playbackGateHeld = true
                ImageLoadGate.shared.beginPlayback()
            }
            currentVid = vid
            currentTitle = title
            HistoryStore.shared.addPlay(vid: vid, title: title)
            vm.load(vid: vid)
            Task {
                if let d = try? await DramaAPI.shared.show(vid: vid) {
                    vm.playcode = d.playcode
                    guess = d.guess
                    if !d.video.title.isEmpty {
                        currentTitle = d.video.title
                        HistoryStore.shared.addPlay(vid: vid, title: d.video.title, image: d.video.image)
                    }
                }
            }
            startHeartbeat()
        }
        .onDisappear {
            if playbackGateHeld {
                playbackGateHeld = false
                ImageLoadGate.shared.endPlayback()
            }
            heartbeatTask?.cancel()
            longPressTask?.cancel()
            vm.cleanup()
            // 全屏退出页面时把方向还原，避免返回搜索页仍是横屏
            if isFullscreen { applyOrientation(landscape: false) }
        }
        .onChange(of: vm.reloadToken) { _ in
            vm.load(vid: currentVid.isEmpty ? vid : currentVid)
        }
    }

    // MARK: 布局：竖屏小窗 / 横屏全屏

    private var inlineStage: some View {
        VStack(spacing: 0) {
            topBar
            playerBox
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(maxWidth: .infinity)
            if showControls { controlBar }
            // 猜你喜欢放在下方，与首页 / 搜索结果一样双列对齐
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if !guess.isEmpty {
                        SectionTitle(text: "猜你喜欢")
                            .padding(.top, 4)
                        VideoGridView(items: guess) { item in playGuess(item) }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
        }
    }

    private var fullscreenStage: some View {
        ZStack(alignment: .bottom) {
            playerBox
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            VStack(spacing: 0) {
                if showControls { topBar }
                // 中间区域放行手势，全屏时仍可滑动 / 长按
                Spacer().allowsHitTesting(false)
                if showControls { controlBar }
            }
        }
        .ignoresSafeArea()
    }

    /// 画面 + 手势 + 浮层。三种手势统一由一个 DragGesture 状态机处理，避免互相抢占。
    private var playerBox: some View {
        ZStack {
            PlayerUIView(player: vm.player)
            // 极淡覆盖层：黑屏 / 未起播时也能命中 SwiftUI 手势
            Color.black.opacity(0.001)
            if let msg = vm.errorMessage {
                VStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 34))
                    Text(msg).font(.footnote).multilineTextAlignment(.center)
                }
                .foregroundColor(.white)
                .padding()
            }
            if isSeeking {
                seekHUD
            } else if isLongPressSpeed {
                speedHUD
            }
        }
        .background(Color.black)
        .contentShape(Rectangle())
        .gesture(playerGesture)
    }

    // MARK: 手势：点击 / 左右滑动快进快退 / 长按 2 倍速

    private var playerGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                // 首次按下：记录基准时间并启动长按计时（手指静止 0.4s 进入倍速）
                if longPressTask == nil && !gestureActed && !isSeeking {
                    seekBaseTime = vm.currentTime
                    longPressTask = Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 400_000_000)
                        guard !Task.isCancelled, !isSeeking, !gestureActed else { return }
                        gestureActed = true
                        isLongPressSpeed = true
                        vm.beginLongPressSpeed()
                    }
                }
                let dx = value.translation.width
                guard abs(dx) > pxPerSecond else { return }
                // 开始左右滑动：取消长按时并恢复常驻倍速，右滑快进 / 左滑快退
                longPressTask?.cancel()
                longPressTask = nil
                if isLongPressSpeed {
                    vm.endLongPressSpeed()
                    isLongPressSpeed = false
                }
                isSeeking = true
                gestureActed = true
                seekSeconds = Double(dx / pxPerSecond)
            }
            .onEnded { _ in
                longPressTask?.cancel()
                longPressTask = nil
                if isSeeking {
                    vm.seek(to: seekBaseTime + seekSeconds)
                }
                if isLongPressSpeed {
                    vm.endLongPressSpeed()
                }
                if !gestureActed {
                    showControls.toggle()
                }
                isSeeking = false
                isLongPressSpeed = false
                gestureActed = false
                seekSeconds = 0
            }
    }

    private var seekHUD: some View {
        VStack(spacing: 4) {
            Text(String(format: "%+.0fs", seekSeconds))
                .font(.system(size: 20, weight: .semibold))
            Text("\(timeString(max(0, seekBaseTime + seekSeconds))) / \(timeString(vm.duration))")
                .font(.system(size: 12))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(Color.black.opacity(0.65))
        .cornerRadius(10)
    }

    private var speedHUD: some View {
        Text("2.0x 倍速中 · 松手恢复")
            .font(.system(size: 15, weight: .medium))
            .foregroundColor(.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.65))
            .cornerRadius(10)
    }

    // MARK: 全屏方向

    private func toggleFullscreen() {
        isFullscreen.toggle()
        applyOrientation(landscape: isFullscreen)
    }

    /// Info.plist 已声明 Portrait + LandscapeLeft + LandscapeRight，这里只请求方向。
    /// iOS 16+ 用官方 requestGeometryUpdate(_:errorHandler:)；iOS 15 用设备方向 + 主动重试旋转。
    private func applyOrientation(landscape: Bool) {
        if #available(iOS 16.0, *) {
            let mask: UIInterfaceOrientationMask = landscape ? .landscape : .portrait
            if let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).first {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
                scene.windows.first?.rootViewController?
                    .setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        } else {
            UIDevice.current.setValue(
                landscape
                    ? UIInterfaceOrientation.landscapeRight.rawValue
                    : UIInterfaceOrientation.portrait.rawValue,
                forKey: "orientation"
            )
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    // MARK: 数据

    /// 点猜你喜欢切换影片：刷新播放地址、playcode 与推荐列表，并记录播放历史。
    private func playGuess(_ item: VideoItem) {
        let nextVid = item.id
        let nextTitle = item.title.isEmpty ? "未命名" : item.title
        currentVid = nextVid
        currentTitle = nextTitle
        HistoryStore.shared.addPlay(vid: nextVid, title: nextTitle, image: item.image)
        vm.playcode = ""
        vm.load(vid: nextVid)
        Task {
            if let d = try? await DramaAPI.shared.show(vid: nextVid) {
                vm.playcode = d.playcode
                guess = d.guess
            }
        }
    }

    // MARK: 控制条

    private var topBar: some View {
        HStack(spacing: 12) {
            // 独立页面（push）用返回箭头，点击 pop 回上一页（搜索结果仍在栈里）
            Button { presentationMode.wrappedValue.dismiss() } label: {
                HStack(spacing: 3) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                    Text("返回").font(.system(size: 14))
                }
            }
            Text(currentTitle.isEmpty ? title : currentTitle)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(1)
            Spacer()
        }
        .foregroundColor(.white)
        .padding()
        .background(Color.black.opacity(0.6))
    }

    private var controlBar: some View {
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
                Button { vm.seek(by: -10) } label: { Image(systemName: "gobackward.10") }
                Button { vm.togglePlay() } label: {
                    Image(systemName: vm.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 24))
                }
                Button { vm.seek(by: 10) } label: { Image(systemName: "goforward.10") }
                Button { vm.load(vid: currentVid.isEmpty ? vid : currentVid) } label: {
                    Image(systemName: "arrow.clockwise")
                }
                Spacer()
                Button { vm.cycleRate() } label: {
                    Text(String(format: "%gx", Double(vm.rate)))
                        .font(.system(size: 13, weight: .medium))
                }
                Button { toggleFullscreen() } label: {
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

    /// 每 30 秒一次心跳（seconds=3）
    private func startHeartbeat() {
        let model = vm
        heartbeatTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if Task.isCancelled { break }
                await DramaAPI.shared.playtime(playcode: model.playcode, seconds: 3)
            }
        }
    }

    private func timeString(_ s: Double) -> String {
        guard s.isFinite, s >= 0 else { return "0:00" }
        let total = Int(s)
        let h = total / 3600
        let m = (total % 3600) / 60
        let sec = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, sec)
            : String(format: "%d:%02d", m, sec)
    }
}
