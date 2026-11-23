package com.youqu.vod.duanju

import android.content.Context
import com.youqu.vod.core.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短剧模块管理仓库：直接复用 Go 核心已有的 5 个 action，
 * modules / moduleIndex / prepareModule / installModule / uninstallModule。
 */
class ModuleRepository(private val context: Context) {

    /** 初始化推迟到 IO 线程的首次调用，避免占用 App 启动主线程 */
    @Volatile
    private var coreReady = false

    private fun ensureCore() {
        if (coreReady) return
        NativeCore.initialize(context)
        coreReady = true
    }

    /** 已安装模块。注意：modules() 返回的条目天然都是已安装，installed 字段为默认值 false。 */
    suspend fun installed(): ApiResult<List<SourceModule>> = io {
        nativeCall { put("action", "modules") }.map { data ->
            val arr = data?.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { SourceModule.from(it).copy(installed = true) }
            }
        }
    }

    /** 在线仓库 + 内置模块条目 */
    suspend fun store(): ApiResult<ModuleStore> = io {
        nativeCall { put("action", "moduleIndex") }.map { data ->
            val arr = data?.optJSONArray("items") ?: JSONArray()
            ModuleStore(
                items = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { IndexModule.from(it) }
                },
                warning = data?.optString("warning").orEmpty()
            )
        }
    }

    /** 从在线仓库或内置条目准备安装，返回带 token 的预览 */
    suspend fun prepareIndexed(id: String, bundled: Boolean): ApiResult<SourceModule> = io {
        nativeCall {
            put("action", "prepareModule")
            put("source", id)
            put("bundled", bundled)
        }.map { SourceModule.from(it ?: JSONObject()) }
            .let { r ->
                if (r is ApiResult.Ok && r.value.token.isBlank()) {
                    ApiResult.Err("核心未返回安装令牌，请重试")
                } else r
            }
    }

    /** 从 http(s) 地址准备安装 */
    suspend fun prepareUrl(url: String): ApiResult<SourceModule> = io {
        nativeCall {
            put("action", "prepareModule")
            put("url", url)
        }.map { SourceModule.from(it ?: JSONObject()) }
    }

    /** 从本地 zip 真实路径准备安装 */
    suspend fun prepareFile(path: String): ApiResult<SourceModule> = io {
        nativeCall {
            put("action", "prepareModule")
            put("file", path)
        }.map { SourceModule.from(it ?: JSONObject()) }
    }

    /** 落盘安装，token 只能使用一次 */
    suspend fun install(token: String): ApiResult<String> = io {
        nativeCall {
            put("action", "installModule")
            put("token", token)
        }.map { it?.optString("source").orEmpty() }
    }

    suspend fun uninstall(id: String): ApiResult<Unit> = io {
        nativeCall {
            put("action", "uninstallModule")
            put("source", id)
        }.map { Unit }
    }

    private suspend fun <T> io(block: suspend () -> ApiResult<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            ensureCore()
            block()
        }

    private fun nativeCall(build: JSONObject.() -> Unit): ApiResult<JSONObject?> =
        when (val r = NativeCore.call(build)) {
            is NativeCore.CoreResult.Ok -> ApiResult.Ok(r.data)
            is NativeCore.CoreResult.Err -> ApiResult.Err(r.message)
        }

    private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(transform(value))
        is ApiResult.Err -> this
    }
}
