package com.youqu.vod.direct

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 封面回源闸门（与 iOS 端 ImageLoadGate 同口径）。
 *
 * 一屏列表会有十几张封面同时进入视口，如果无限制并发回源，会和 HLS 播放列表 / 分片
 * 抢连接与带宽，起播变慢、播放中途卡顿。这里把「图片回源 + 解密」统一排队：
 *
 *  - 平时最多 2 个并发回源；
 *  - 播放页在前台时收窄到 1 个，且起播后 [BUFFERING_GUARD_MS] 毫秒内完全让路给分片；
 *  - 排队最多 [MAX_QUEUE_MS]，超时仍放行，避免上游异常时封面永远停在占位动图。
 *
 * 只在本地网关的 /img 处理线程上调用（阻塞式），不碰播放器线程。
 */
object ImageLoadGate {

    private const val MAX_PARALLEL = 2
    private const val PLAYBACK_PARALLEL = 1
    private const val BUFFERING_GUARD_MS = 4_000L
    private const val MAX_QUEUE_MS = 8_000L
    private const val POLL_MS = 60L

    private val slots = Semaphore(MAX_PARALLEL, true)
    private val playbackSlots = Semaphore(PLAYBACK_PARALLEL, true)
    private val playbackDepth = AtomicInteger(0)

    @Volatile
    private var playbackStartedAt = 0L

    private val inPlayback: Boolean get() = playbackDepth.get() > 0

    /** 进入播放页（可重入：从播放页点「猜你喜欢」跳下一个播放页时不会提前解除） */
    fun beginPlayback() {
        if (playbackDepth.incrementAndGet() == 1) playbackStartedAt = System.currentTimeMillis()
    }

    fun endPlayback() {
        val left = playbackDepth.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (left == 0) playbackStartedAt = 0L
    }

    /** 排队执行一段「图片回源 + 解密」逻辑，返回其结果。 */
    fun <T> withSlot(block: () -> T): T {
        // 起播保护窗口：先让播放列表与头几个分片缓冲完，再开始拉封面
        val guardDeadline = System.currentTimeMillis() + MAX_QUEUE_MS
        while (inPlayback &&
            System.currentTimeMillis() - playbackStartedAt < BUFFERING_GUARD_MS &&
            System.currentTimeMillis() < guardDeadline
        ) {
            Thread.sleep(POLL_MS)
        }

        val acquired = slots.tryAcquire(MAX_QUEUE_MS, TimeUnit.MILLISECONDS)
        var acquiredNarrow = false
        return try {
            if (acquired && inPlayback) {
                acquiredNarrow = playbackSlots.tryAcquire(MAX_QUEUE_MS, TimeUnit.MILLISECONDS)
            }
            block()
        } finally {
            if (acquiredNarrow) playbackSlots.release()
            // 超时未拿到名额时仍然放行，但因为没有占用，这里不能释放
            if (acquired) slots.release()
        }
    }
}

/**
 * 解密后封面的内存 LRU。Coil 自带磁盘/内存缓存，但没有解密前的那一层，
 * 列表来回滚动时加上这一层可彻底避免重复回源（与 iOS 的 ImageCache.shared 对齐）。
 */
object ImageByteCache {

    private const val MAX_ENTRIES = 80
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val map = LinkedHashMap<String, ImageDecryptor.Image>(64, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): ImageDecryptor.Image? = map[key]

    @Synchronized
    fun put(key: String, image: ImageDecryptor.Image) {
        if (image.bytes.size > MAX_BYTES) return
        val old = map.put(key, image)
        totalBytes += image.bytes.size.toLong() - (old?.bytes?.size?.toLong() ?: 0L)
        while (map.size > MAX_ENTRIES || totalBytes > MAX_BYTES) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            totalBytes -= eldest.value.bytes.size.toLong()
            iterator.remove()
        }
        if (totalBytes < 0L) totalBytes = 0L
    }
}
