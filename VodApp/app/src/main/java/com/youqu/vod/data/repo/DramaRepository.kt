package com.youqu.vod.data.repo

import com.youqu.vod.core.ApiResult
import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.data.model.ApiEnvelope
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.model.DramaListData
import com.youqu.vod.data.model.DramaSearchData
import com.youqu.vod.data.remote.ApiService
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.util.anyToInt
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class DramaPageResult(
    val list: List<DramaItem>,
    val page: Int,
    val more: Boolean
)

/**
 * 短剧专区仓库：与影视 VodRepository 完全隔离，走 /java/drama/* 系列 action。
 * 所有请求经 APP 内置网关直连上游，字段各自解析、不与影视混用。
 */
class DramaRepository(
    private val api: ApiService,
    private val settings: SettingsRepository,
    private val domainRepository: DomainRepository
) {
    val baseUrl: String get() = settings.baseUrl

    suspend fun lists(page: Int): ApiResult<DramaPageResult> = call {
        api.dramaList(GatewayUrls.api(baseUrl, "drama_lists", "page" to page.toString()))
    }.map { d ->
        DramaPageResult(
            list = d.list ?: emptyList(),
            page = anyToInt(d.page).let { if (it > 0) it else page },
            more = d.more?.toString()?.equals("true", ignoreCase = true) == true ||
                anyToInt(d.more) == 1
        )
    }

    suspend fun hotWords(): ApiResult<List<String>> = call {
        api.dramaHot(GatewayUrls.api(baseUrl, "drama_hot"))
    }.map { (it.list ?: emptyList()).filter { w -> w.isNotBlank() } }

    suspend fun search(keyword: String, page: Int): ApiResult<DramaPageResult> = call {
        api.dramaSearch(
            GatewayUrls.api(baseUrl, "drama_search", "kw" to keyword, "page" to page.toString())
        )
    }.map { d ->
        DramaPageResult(
            list = d.list ?: emptyList(),
            page = anyToInt(d.page).let { if (it > 0) it else page },
            more = d.more?.toString()?.equals("true", ignoreCase = true) == true ||
                anyToInt(d.more) == 1
        )
    }

    suspend fun detail(id: String): ApiResult<DramaDetailData> = call {
        api.dramaDetail(GatewayUrls.api(baseUrl, "drama_detail", "id" to id))
    }

    /* ---------- 内部工具（与 VodRepository 同构） ---------- */

    private suspend fun <T> call(block: suspend () -> ApiEnvelope<T>): ApiResult<T> {
        domainRepository.ensure()
        return try {
            val env = block()
            when {
                env.ok == false ->
                    ApiResult.Err(env.error?.takeIf { it.isNotBlank() } ?: "短剧网关返回失败")
                env.data == null ->
                    ApiResult.Err(env.message?.takeIf { it.isNotBlank() } ?: "短剧网关未返回数据")
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

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "连不上网关，请检查设置里的服务器地址"
        is SocketTimeoutException -> "请求超时，网关响应太慢"
        is IOException -> "网络异常：${e.message ?: "未知错误"}"
        else -> e.message ?: "未知错误"
    }
}
