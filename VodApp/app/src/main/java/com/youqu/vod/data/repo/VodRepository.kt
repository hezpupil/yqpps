package com.youqu.vod.data.repo

import com.youqu.vod.core.ApiResult
import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.data.model.ApiEnvelope
import com.youqu.vod.data.repo.DomainRepository
import com.youqu.vod.data.model.HotWord
import com.youqu.vod.data.model.ShowData
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.data.remote.ApiService
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.util.anyToInt
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class SearchResult(
    val list: List<VideoItem>,
    val page: Int,
    val pages: Int
)

/**
 * 业务仓库：把网关的 11 个 action 包装成 APP 直接可用的方法。
 *
 * 约定：网关返回 ok=true 且 data 非空才算成功；失败优先取 error 字段，
 * 其次取 message，都没有就给一句人话。
 */
class VodRepository(
    private val api: ApiService,
    private val settings: SettingsRepository,
    private val domainRepository: DomainRepository
) {

    /** 当前网关地址（设置里可改，改完立即生效） */
    val baseUrl: String get() = settings.baseUrl

    /** 首页热搜关键词（index/index 的 data.hot） */
    suspend fun index(): ApiResult<List<HotWord>> = call {
        api.index(GatewayUrls.api(baseUrl, "index"))
    }.mapList { it.hot }

    suspend fun recent(): ApiResult<List<VideoItem>> = call {
        api.recent(GatewayUrls.api(baseUrl, "recent"))
    }.mapList { it.list }

    suspend fun hot(): ApiResult<List<HotWord>> = call {
        api.hot(GatewayUrls.api(baseUrl, "hot"))
    }.mapList { it.list }

    suspend fun search(keyword: String, page: Int, order: Int): ApiResult<SearchResult> = call {
        api.search(
            GatewayUrls.api(
                baseUrl, "search",
                "kw" to keyword,
                "page" to page.toString(),
                "order" to order.toString()
            )
        )
    }.map {
        SearchResult(
            list = it.list ?: emptyList(),
            page = anyToInt(it.page).let { p -> if (p > 0) p else page },
            pages = anyToInt(it.pages).let { p -> if (p > 0) p else 1 }
        )
    }

    suspend fun show(vid: String): ApiResult<ShowData> = call {
        api.show(GatewayUrls.api(baseUrl, "show", "vid" to vid))
    }

    /** 播放心跳：seconds 与 H5 端保持一致，传 3 */
    suspend fun playtime(playcode: String, seconds: Int = 3): ApiResult<Unit> = call {
        api.playtime(
            GatewayUrls.api(
                baseUrl, "playtime",
                "playcode" to playcode,
                "seconds" to seconds.toString()
            )
        )
    }.map { }

    suspend fun deviceActive(): ApiResult<Unit> = call {
        api.deviceActive(GatewayUrls.api(baseUrl, "device_active"))
    }.map { }

    suspend fun relogin(): ApiResult<Unit> = call {
        api.login(GatewayUrls.api(baseUrl, "login"))
    }.map { }

    /* ---------- 内部工具 ---------- */

    private suspend fun <T> call(block: suspend () -> ApiEnvelope<T>): ApiResult<T> {
        // 首屏/任意网关请求前，先确保远端域名覆盖已拉取并写入本地配置。
        // domainRepository.ensure() 进程内只真正请求一次、之后立即返回，因此每次请求前调用无性能负担，
        // 但能彻底消除「首屏请求早于域名刷新、读到陈旧域名」的竞态。
        domainRepository.ensure()
        return try {
            val env = block()
            when {
                env.ok == false ->
                    ApiResult.Err(env.error?.takeIf { it.isNotBlank() } ?: "网关返回失败")

                env.data == null ->
                    ApiResult.Err(env.message?.takeIf { it.isNotBlank() } ?: "网关未返回数据")

                else -> ApiResult.Ok(env.data)
            }
        } catch (e: Exception) {
            ApiResult.Err(friendlyError(e))
        }
    }

    private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(transform(value))
        is ApiResult.Err -> this
    }

    private fun <T, R> ApiResult<T>.mapList(transform: (T) -> List<R>?): ApiResult<List<R>> =
        when (this) {
            is ApiResult.Ok -> ApiResult.Ok(transform(value) ?: emptyList())
            is ApiResult.Err -> this
        }

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "连不上网关，请检查设置里的服务器地址"
        is SocketTimeoutException -> "请求超时，网关响应太慢"
        is IOException -> "网络异常：${e.message ?: "未知错误"}"
        else -> e.message ?: "未知错误"
    }
}
