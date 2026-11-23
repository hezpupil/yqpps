package com.youqu.vod.duanju

import android.content.Context
import com.youqu.vod.core.ApiResult
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.model.DramaEpisode
import com.youqu.vod.data.model.DramaInfo
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.repo.DramaPageResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 红果短剧模块仓库（走 Go 核心 + guoapp 红果模块）。
 *
 * 与影视 VodRepository、旧 hezi1 DramaRepository 完全隔离：
 * 所有数据来自 NativeCore，映射到既有 DramaModels，UI 层无需感知数据源切换。
 */
class ModuleDramaRepository(private val context: Context) {

    private val source = "hongguo"
    /** 首页默认分类：真人短剧。空串表示混合推荐 */
    private val homeCategory = "short_play"

    init {
        NativeCore.initialize(context)
    }

    val baseUrl: String get() = ""

    /** 首页列表。category 为空时用默认短剧分类 */
    suspend fun lists(page: Int, category: String = homeCategory): ApiResult<DramaPageResult> = call {
        put("action", "catalog")
        put("source", source)
        put("category", category)
        put("page", page)
    }.map { d -> parsePage(d, page) }

    /** 搜索：核心返回 items 结构，与 catalog 一致 */
    suspend fun search(keyword: String, page: Int): ApiResult<DramaPageResult> = call {
        put("action", "search")
        put("source", source)
        put("query", keyword)
        put("page", page)
    }.map { d -> parsePage(d, page) }

    /** 热搜词：核心 suggestions/榜单，失败时回退空（UI 会隐藏） */
    suspend fun hotWords(): ApiResult<List<String>> = call {
        put("action", "categories")
        put("source", source)
    }.map { d -> parseCategories(d) }

    /** 详情：drama + chapters。chapters 的 videoUrl 为内部 scheme，需 resolve */
    suspend fun detail(id: String): ApiResult<DramaDetailData> = call {
        put("action", "detail")
        put("drama", JSONObject().put("id", id))
    }.map { d -> parseDetail(d, id) }

    /**
     * 解析真实播放 URL。
     * 核心返回本地代理地址（127.0.0.1:port/xxx.mp4），签名/解密由核心内部完成，
     * App 只需把这个 URL 交给 ExoPlayer。
     */
    suspend fun resolve(dramaId: String, chapterJson: String, index: Int): ApiResult<String> = call {
        put("action", "resolve")
        put("sequence", NativeCore.nextSequence())
        put("drama", JSONObject().put("id", dramaId))
        put("chapter", runCatching { JSONObject(chapterJson) }.getOrDefault(JSONObject()))
        put("index", index)
    }.map { d -> d?.optString("url").orEmpty() }
        .let { result ->
            when (result) {
                is ApiResult.Ok -> if (result.value.isBlank()) ApiResult.Err("未返回播放地址") else result
                is ApiResult.Err -> result
            }
        }

    /* ---------- 解析 ---------- */

    private fun parsePage(data: JSONObject?, fallbackPage: Int): DramaPageResult {
        if (data == null) return DramaPageResult(emptyList(), fallbackPage, false)
        val items = data.optJSONArray("items") ?: JSONArray()
        val list = (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let { o -> itemFrom(o) }
        }
        return DramaPageResult(
            list = list,
            page = data.int("page").let { if (it > 0) it else fallbackPage },
            more = data.optBoolean("hasMore")
        )
    }

    private fun itemFrom(o: JSONObject): DramaItem = DramaItem(
        id = o.optString("id"),
        title = o.optString("title").ifBlank { null },
        cover = o.optString("cover").ifBlank { null },
        intro = o.optString("description").ifBlank { null },
        ep_text = o.int("episodes").let { if (it > 0) "${it}集" else (o.optString("category").ifBlank { null }) }
    )

    private fun parseCategories(data: JSONObject?): List<String> {
        if (data == null) return emptyList()
        // categories 返回 {items:[...]} / {categories:[...]}
        val arr = (data.optJSONArray("items") ?: data.optJSONArray("categories")) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val v = arr.opt(i)
            when (v) {
                is JSONObject -> v.optString("name").ifBlank { v.optString("id") }
                is String -> v
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.take(12)
    }

    private fun parseDetail(data: JSONObject?, fallbackId: String): DramaDetailData {
        if (data == null) return DramaDetailData()
        val dramaObj = data.optJSONObject("drama")
        val chapters = data.optJSONArray("chapters") ?: JSONArray()
        val info = DramaInfo(
            id = dramaObj?.optString("id")?.ifBlank { fallbackId } ?: fallbackId,
            title = dramaObj?.optString("title"),
            cover = dramaObj?.optString("cover"),
            intro = dramaObj?.optString("description"),
            ep_text = dramaObj?.int("episodes")?.let { if (it > 0) "全${it}集" else null }
        )
        val eps = (0 until chapters.length()).mapNotNull { i ->
            val c = chapters.optJSONObject(i) ?: return@mapNotNull null
            DramaEpisode(
                // id 用章节原始 JSON，resolve 时直接回传给核心
                id = c.toString(),
                ep_no = c.int("currentEpisode").let { if (it > 0) it else i + 1 },
                title = c.optString("title").ifBlank { null },
                src = c.optString("videoUrl").ifBlank { null }
            )
        }
        return DramaDetailData(drama = info, eps = eps)
    }

    /* ---------- 内部工具 ---------- */

    private suspend fun call(build: JSONObject.() -> Unit): ApiResult<JSONObject?> =
        when (val r = NativeCore.call(build)) {
            is NativeCore.CoreResult.Ok -> ApiResult.Ok(r.data)
            is NativeCore.CoreResult.Err -> ApiResult.Err(r.message)
        }

    private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(transform(value))
        is ApiResult.Err -> this
    }
}
