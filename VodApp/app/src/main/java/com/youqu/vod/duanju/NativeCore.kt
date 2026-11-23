package com.youqu.vod.duanju

import android.content.Context
import android.util.Log
import org.json.JSONObject

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

    @Volatile
    private var initialized = false

    /**
     * 初始化核心数据目录（必须在任何业务请求前调用一次）。
     * 目录用 App 私有目录，模块/缓存/下载都落在这里。
     */
    fun initialize(context: Context): String {
        if (!loaded) return """{"ok":false,"error":"短剧核心未加载"}"""
        if (initialized) return """{"ok":true,"data":{"already":true}}"""
        return try {
            val root = context.filesDir
            val dir = java.io.File(root, "duanju").apply { mkdirs() }
            val cache = java.io.File(context.cacheDir, "duanju").apply { mkdirs() }
            val payload = JSONObject().apply {
                put("action", "initialize")
                put("directory", dir.absolutePath)
                put("cache", cache.absolutePath)
                put("downloads", java.io.File(dir, "downloads").apply { mkdirs() }.absolutePath)
            }.toString()
            val resp = nativeRequest(payload)
            if (JSONObject(resp).optBoolean("ok")) initialized = true
            resp
        } catch (t: Throwable) {
            Log.e(TAG, "initialize 失败", t)
            """{"ok":false,"error":"短剧核心初始化失败：${t.message ?: "未知错误"}"}"""
        }
    }
}
