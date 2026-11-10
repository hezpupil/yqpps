package com.youqu.vod.data.model

/**
 * 网关统一响应外层：{"action":"..","ok":true,"code":1,"message":"","data":{...},"meta":{...}}
 *
 * 说明：网关对部分字段（id / duration / pages）可能返回数字也可能返回字符串，
 * 因此这里用 Any? 接收，读取时统一走 util/Format.kt 的转换函数，避免解析崩溃。
 */
data class ApiEnvelope<T>(
    val ok: Boolean? = null,
    val code: Any? = null,
    val message: String? = null,
    val data: T? = null,
    val error: String? = null,
    val raw: String? = null
)

/** 热搜关键词：{"id":1,"word":"老师"} */
data class HotWord(
    val id: Any? = null,
    val word: String? = null
)

/** 列表卡片项：{"id":663,"title":"..","image":"..","duration":600,"category":".."} */
data class VideoItem(
    val id: Any? = null,
    val title: String? = null,
    val image: String? = null,
    val duration: Any? = null,
    val category: String? = null
)

/** index 首页数据：data.hot 为热搜词 */
data class IndexData(
    val hot: List<HotWord>? = null,
    val list: List<VideoItem>? = null
)

/** recent 最近更新：data.list */
data class RecentData(
    val list: List<VideoItem>? = null
)

/** hot 热搜榜：data.list */
data class HotListData(
    val list: List<HotWord>? = null
)

/** search 搜索结果：data.list + 分页信息 */
data class SearchData(
    val list: List<VideoItem>? = null,
    val page: Any? = null,
    val pages: Any? = null,
    val total: Any? = null
)

/** show 详情：data.video + data.playcode + data.guess */
data class ShowData(
    val video: VideoInfo? = null,
    val playcode: String? = null,
    val guess: List<VideoItem>? = null
)

data class VideoInfo(
    val id: Any? = null,
    val title: String? = null,
    val image: String? = null,
    val duration: Any? = null,
    val src: String? = null
)

/** playtime / device_active 等无业务数据的响应 */
data class EmptyData(
    val placeholder: Any? = null
)
