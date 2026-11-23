package com.youqu.vod.duanju

import android.util.Log

/**
 * Go 短剧核心 JNI 桥接。
 *
 * 底层是移植自 guoapp（GPLv3）的 Go + goja 引擎，负责执行短剧模块 JS、
 * 网络请求与媒体解析。详见 native/LICENSE.guoapp。
 */
object NativeCore {
    private const val TAG = "NativeCore"

    @Volatile
    private var loaded = false

    init {
        try {
            System.loadLibrary("youqu_duanju")
            loaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "加载短剧核心失败", t)
        }
    }

    /** 核心是否可用（so 是否成功加载） */
    val isAvailable: Boolean get() = loaded

    /**
     * 向核心发送一个 JSON 请求，返回 JSON 字符串。
     * 与 guoapp NativeRequest 协议一致：{"action":"...", ...} -> {"ok":bool,"data":...}
     */
    @JvmStatic
    external fun nativeRequest(json: String): String

    /** 请求封装：核心不可用时返回统一的错误包 */
    fun request(json: String): String {
        if (!loaded) return """{"ok":false,"error":"短剧核心未加载"}"""
        return try {
            nativeRequest(json)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeRequest 失败", t)
            """{"ok":false,"error":"短剧核心调用失败：${t.message ?: "未知错误"}"}"""
        }
    }
}
