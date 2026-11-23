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
    @Volatile
    private var appContext: Context? = null

    /**
     * 初始化核心数据目录（必须在任何业务请求前调用一次）。
     * 目录用 App 私有目录，模块/缓存/下载都落在这里。
     */
    @Synchronized
    fun initialize(context: Context): String {
        appContext = context.applicationContext
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

    /** 确保已初始化（业务层统一入口，避免遗漏 initialize） */
    fun ensureInitialized() {
        appContext?.let { initialize(it) }
    }

    /** 构造请求并解析 envelope，data 为 null 表示失败，error 带可读原因 */
    fun call(build: JSONObject.() -> Unit): CoreResult {
        ensureInitialized()
        if (!loaded) return CoreResult.Err("短剧核心未加载")
        return try {
            val payload = JSONObject().apply(build).toString()
            val resp = JSONObject(nativeRequest(payload))
            if (resp.optBoolean("ok")) {
                CoreResult.Ok(resp.optJSONObject("data"))
            } else {
                CoreResult.Err(resp.optString("error").ifBlank { "短剧核心请求失败" })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "call 失败", t)
            CoreResult.Err(t.message ?: "短剧核心调用异常")
        }
    }

    sealed interface CoreResult {
        data class Ok(val data: JSONObject?) : CoreResult
        data class Err(val message: String) : CoreResult
    }

    /** 播放会话序号：切集必须递增，核心据此取消上一次播放 */
    @Volatile
    private var sequence = 0L
    fun nextSequence(): Long = ++sequence
}

/** JSON 数组安全取字符串 */
fun org.json.JSONArray.str(position: Int): String = opt(position)?.toString()?.takeIf { it != "null" } ?: ""
/** JSON 对象安全取整数（兼容数字/字符串/浮点） */
fun JSONObject.int(key: String): Int {
    val v = opt(key) ?: return 0
    return when (v) {
        is Number -> v.toDouble().toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: 0
        else -> 0
    }
}
