package com.youqu.vod.player

import android.content.Context
import android.view.Surface
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 ExoPlayer 控制器，替换原来的 IJKPlayer。
 *
 * 为什么换：IJKPlayer 0.8.8 的预编译 native 库（ijkffmpeg/ijksdl/ijkplayer）只按 4KB 内存页编译，
 * 在 Android 15/16 的 16KB 页设备上 mmap/执行会直接 SIGSEGV（native 崩溃，Java 抓不到）。
 * ExoPlayer 由 Google 官方维护，原生库自 1.2 起就支持 16KB 页，是这类新机的稳妥选择。
 *
 * 1.0.12 起：不再经过 StreamProxy，直接把 show 返回的 src（CDN 直连、与网页版同源）
 * 喂给 ExoPlayer；HLS 播放列表中的相对 KEY/分片地址由 ExoPlayer 按播放列表地址自动解析。
 */
class ExoPlayerController(
    context: Context,
    private val userAgent: String,
    private val baseUrl: String
) : PlaybackController {

    override val state = PlayerState()

    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null
    private var lastSpeed = 1f
    private val disposed = AtomicBoolean(false)

    override fun attachSurface(surface: Surface?) {
        pendingSurface = surface
        runCatching { player?.setVideoSurface(surface) }
    }

    override fun open(url: String) {
        try {
            state.clearError()
            state.markBuffering(true)
            state.markPaused()
            state.resetProgress()
            state.videoRatio = null

            // 切集/换源：释放旧实例再建新实例，避免上一次的解码状态残留。
            runCatching { player?.release() }
            player = null

            val exo = ExoPlayer.Builder(appContext).build().apply {
                if (lastSpeed != 1f) setPlaybackSpeed(lastSpeed)
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(listener)
            }
            player = exo
            pendingSurface?.let { runCatching { exo.setVideoSurface(it) } }

            // 自定义 HTTP 源：带 UA 与 Referer（直连网关时防盗链用，本地代理会忽略）。
            val dsFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to baseUrl,
                        "Accept" to "*/*"
                    )
                )
            // 已知是 HLS（m3u8.php / 本地代理均返回 m3u8），显式用 HlsMediaSource 最稳。
            val mediaSource = HlsMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true
        } catch (t: Throwable) {
            state.markError("起播失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    override fun toggle() {
        val exo = player ?: return
        if (exo.isPlaying) runCatching { exo.pause() } else runCatching { exo.play() }
    }

    override fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val target = positionMs.coerceAtLeast(0L)
        runCatching { exo.seekTo(target) }
        state.positionMs = target
    }

    override fun setSpeed(speed: Float) {
        lastSpeed = speed
        state.speed = speed
        runCatching { player?.setPlaybackSpeed(speed) }
    }

    override fun currentPositionMs(): Long {
        val v = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        return if (v < 0) 0L else v
    }

    override fun durationMs(): Long {
        val v = runCatching { player?.duration ?: 0L }.getOrDefault(0L)
        return if (v < 0) 0L else v
    }

    override fun release() {
        if (disposed.getAndSet(true)) return
        runCatching { player?.release() }
        player = null
        pendingSurface = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> state.markBuffering(true)
                Player.STATE_READY -> {
                    state.markBuffering(false)
                    val d = player?.duration ?: 0L
                    if (d > 0) state.durationMs = d
                }
                Player.STATE_ENDED -> {
                    state.markPaused()
                    state.positionMs = state.durationMs
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) state.markPlaying() else state.markPaused()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // 记录真实宽高比（含像素宽高比校正），供画面按比例自适应、避免全屏拉伸
            if (videoSize.width > 0 && videoSize.height > 0) {
                state.videoRatio =
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val name = error.errorCodeName ?: error.message ?: "unknown"
            state.markError("ExoPlayer 播放失败（$name）")
        }
    }
}
