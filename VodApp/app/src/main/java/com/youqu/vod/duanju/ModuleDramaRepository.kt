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
class ModuleDramaRepository(
    private val context: Context,
    private val preferences: ModulePreferences
) {

    /** 首页默认分类：仅红果支持；其他模块用空串走各自推荐流 */
    private val homeCategory = "short_play"
    private val noModuleHint = "尚未安装或启用任何短剧模块，请到短剧页右上角「模块」安装并启用"

    /** 切换模块/手动刷新时清空详情缓存，避免旧源详情串到新源 */
    fun clearDetailCache() = synchronized(detailCache) { detailCache.clear() }

    /** 详情页与播放页共用同一份详情，避免进入播放页时重复请求详情造成卡顿 */
    private val detailCache = LinkedHashMap<String, DramaDetailData>()

    init {
        NativeCore.initialize(context)
    }

    val baseUrl: String get() = ""

    /** 首页列表。category 为空时按当前模块使用默认分类 */
    suspend fun lists(page: Int, category: String? = null): ApiResult<DramaPageResult> {
        val src = activeSource() ?: return ApiResult.Err(noModuleHint)
        return call {
            put("action", "catalog")
            put("source", src)
            put("category", category ?: if (src == "hongguo") homeCategory else "")
            put("page", page)
        }.map { d -> parsePage(d, page) }
    }

    /** 搜索：核心返回 items 结构，与 catalog 一致 */
    suspend fun search(keyword: String, page: Int): ApiResult<DramaPageResult> {
        val src = activeSource() ?: return ApiResult.Err(noModuleHint)
        return call {
            put("action", "search")
            put("source", src)
            put("query", keyword)
            put("page", page)
        }.map { d -> parsePage(d, page) }
    }

    /** 热搜词：核心 suggestions/榜单，失败时回退空（UI 会隐藏） */
    suspend fun hotWords(): ApiResult<List<String>> {
        val src = activeSource() ?: return ApiResult.Err(noModuleHint)
        return call {
            put("action", "categories")
            put("source", src)
        }.map { d -> parseCategories(d) }
    }

    /**
     * 当前生效的短剧源：用户指定的默认源 > 红果 > 其他已启用模块。
     * 成人模块只有用户确认过年龄限制后才会被自动选中。
     */
    private suspend fun activeSource(): String? {
        val result = call { put("action", "modules") }
        val arr = (result as? ApiResult.Ok)?.value?.optJSONArray("items") ?: return null
        val candidates = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                val id = o.optString("id")
                Triple(id, o.optBoolean("adult"), o.optString("error"))
            }
        }.filter { it.first.isNotBlank() && it.third.isBlank() }

        val preferred = preferences.preferredSource
        if (preferred != null && candidates.any { it.first == preferred } &&
            preferences.isEnabled(preferred) &&
            (!candidates.first { it.first == preferred }.second || preferences.adultConfirmed)
        ) return preferred

        candidates.firstOrNull { it.first == "hongguo" && preferences.isEnabled(it.first) }?.let { return it.first }
        return candidates.firstOrNull {
            preferences.isEnabled(it.first) && (!it.second || preferences.adultConfirmed)
        }?.first
    }

    /** 详情：drama + chapters。chapters 的 videoUrl 为内部 scheme，需 resolve */
    suspend fun detail(id: String): ApiResult<DramaDetailData> {
        synchronized(detailCache) { detailCache[id] }?.let { return ApiResult.Ok(it) }
        return call {
            put("action", "detail")
            put("drama", JSONObject().put("id", id))
        }.map { d ->
            parseDetail(d, id).also { parsed ->
                if (parsed.eps.orEmpty().isNotEmpty()) {
                    synchronized(detailCache) {
                        if (detailCache.size >= 20) detailCache.clear()
                        detailCache[id] = parsed
                    }
                }
            }
        }
    }

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
                is ApiResult.Err -> ApiResult.Err(friendlyResolveError(result.message))
            }
        }

    /** 红果私有取流接口近期频繁被封，把冗长技术错误收敛成可行动的提示 */
    private fun friendlyResolveError(raw: String): String = when {
        raw.contains("接口未返回有效数据") || raw.contains("未提供公开播放地址") ->
            "红果上游取流接口暂时不可用（官方近期频繁封禁），当前仅前几集试看可播，请稍后重试或切换其他短剧源"
        raw.contains("HTTP 404") ->
            "红果上游播放地址已失效，请稍后重试或切换其他短剧源"
        else -> raw
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
        try {
            when (val r = NativeCore.call(build)) {
                is NativeCore.CoreResult.Ok -> ApiResult.Ok(r.data)
                is NativeCore.CoreResult.Err -> ApiResult.Err(r.message)
            }
        } catch (t: Throwable) {
            android.util.Log.e("ModuleDramaRepository", "短剧核心调用异常", t)
            ApiResult.Err("短剧核心调用异常：${t.message ?: "未知错误"}")
        }

    private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(transform(value))
        is ApiResult.Err -> this
    }
}
