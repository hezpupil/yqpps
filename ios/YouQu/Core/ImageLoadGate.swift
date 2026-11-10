import Foundation

/// 封面回源闸门（与 Android 端 ImageLoadGate 同口径）。
///
/// 一屏列表十几张封面同时回源，会和 HLS 播放列表 / 分片抢连接与带宽，导致起播慢、播放卡。
/// 这里把 /img 的「上游下载 + 解密」统一排队：
///  - 平时最多 2 个并发；
///  - 播放页在前台时收窄到 1 个，且起播后 bufferingGuard 秒内完全让路给分片；
///  - 排队最多 maxQueue 秒，超时仍放行，避免封面永远停在占位动图。
final class ImageLoadGate {
    static let shared = ImageLoadGate()

    private let lock = NSLock()
    private var running = 0
    private var playbackDepth = 0
    private var playbackStarted = Date.distantPast

    private let normalLimit = 2
    private let playbackLimit = 1
    private let bufferingGuard: TimeInterval = 4
    private let maxQueue: TimeInterval = 8

    private init() {}

    /// 进入播放页（可重入：从「猜你喜欢」跳下一个播放页时不会提前解除）
    func beginPlayback() {
        lock.lock()
        if playbackDepth == 0 { playbackStarted = Date() }
        playbackDepth += 1
        lock.unlock()
    }

    func endPlayback() {
        lock.lock()
        playbackDepth = max(0, playbackDepth - 1)
        if playbackDepth == 0 { playbackStarted = Date.distantPast }
        lock.unlock()
    }

    /// 占用一个回源名额；返回 false 表示任务已取消，调用方不应再发请求。
    func acquire() async -> Bool {
        let deadline = Date().addingTimeInterval(maxQueue)
        while true {
            if Task.isCancelled { return false }
            lock.lock()
            let playing = playbackDepth > 0
            let guarding = playing && Date().timeIntervalSince(playbackStarted) < bufferingGuard
            let limit = playing ? playbackLimit : normalLimit
            let granted = !guarding && running < limit
            if granted { running += 1 }
            lock.unlock()
            if granted { return true }

            // 排队超时：直接放行，避免上游异常 / 闸门计数不一致时封面卡住
            if Date() >= deadline {
                lock.lock(); running += 1; lock.unlock()
                return true
            }
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
    }

    func release() {
        lock.lock()
        running = max(0, running - 1)
        lock.unlock()
    }
}
