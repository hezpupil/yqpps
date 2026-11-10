package com.youqu.vod.player

import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 播放状态容器：全部是 Compose 状态，变化即触发控制层重组 */
class PlayerState {
    var playing by mutableStateOf(false)
        internal set
    var buffering by mutableStateOf(true)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    /** 错误诊断（取流方式、上游最近 HTTP 码等），仅供错误浮层展示，不进业务逻辑 */
    var diagnostic by mutableStateOf<String?>(null)
        internal set
    var positionMs by mutableStateOf(0L)
        internal set
    var durationMs by mutableStateOf(0L)
        internal set
    var bufferedPercent by mutableStateOf(0)
        internal set
    var speed by mutableStateOf(1f)
        internal set
    /** 视频宽高比（宽/高，含像素宽高比校正）；null=未知，未知时画面铺满容器 */
    var videoRatio by mutableStateOf<Float?>(null)
        internal set

    fun markPlaying() {
        playing = true
        error = null
    }

    fun markPaused() {
        playing = false
    }

    fun markBuffering(value: Boolean) {
        buffering = value
    }

    fun markError(message: String) {
        error = message
        playing = false
        buffering = false
    }

    /** 重新起播前清掉上一次的失败状态 */
    fun clearError() {
        error = null
        diagnostic = null
    }

    fun resetProgress() {
        positionMs = 0L
        durationMs = 0L
        bufferedPercent = 0
    }
}

/**
 * 取流方式：
 * - PROXY：播放器连本地代理（127.0.0.1），由代理负责请求头、重试与分片 token 刷新，默认走这条；
 * - DIRECT：播放器直接连网关，本地代理起不来、或代理链路崩溃过时自动降级到这里。
 */
enum class PlayMode { PROXY, DIRECT }

/** 控制层只依赖这个接口，内核可替换 */
interface PlaybackController {
    val state: PlayerState

    fun open(url: String)
    fun toggle()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun release()

    /** 画面载体就绪/销毁时回调；内核在创建后才能真正绑定 Surface */
    fun attachSurface(surface: Surface?)
}
