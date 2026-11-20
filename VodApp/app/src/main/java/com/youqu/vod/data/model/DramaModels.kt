package com.youqu.vod.data.model

/**
 * 短剧专区数据模型（与影视 VOD 完全隔离，字段不交叉）。
 *
 * 接口来源：java drama 系列接口，经 APP 内置网关直连、倒序 Base64 解码后返回。
 * 字段用 Any? 接收数字/字符串漂移，读取时统一走 util/Format.kt。
 */

/** 短剧列表卡片：/java/drama/lists */
data class DramaItem(
    val id: Any? = null,
    val title: String? = null,
    val cover: String? = null,
    val cover_width: Any? = null,
    val cover_height: Any? = null,
    val intro: String? = null,
    val ep_text: String? = null
)

/** 短剧列表响应：data.page / data.more / data.list */
data class DramaListData(
    val page: Any? = null,
    val more: Any? = null,
    val list: List<DramaItem>? = null
)

/** 短剧搜索响应：data.page / data.more / data.list */
data class DramaSearchData(
    val page: Any? = null,
    val more: Any? = null,
    val list: List<DramaItem>? = null
)

/** 短剧热搜词响应：data.list 为纯字符串数组（与影视热词 {id,word} 不同） */
data class DramaHotData(
    val list: List<String>? = null
)

/** 短剧详情里的剧集：eps[] */
data class DramaEpisode(
    val id: Any? = null,
    val ep_no: Any? = null,
    val title: String? = null,
    val duration: Any? = null,
    val price: Any? = null,
    val src: String? = null,
    val unlocked: Any? = null
)

/** 短剧详情 data.drama */
data class DramaInfo(
    val id: Any? = null,
    val title: String? = null,
    val cover: String? = null,
    val intro: String? = null,
    val ep_text: String? = null,
    val is_favorite: Any? = null,
    val fav_ep: Any? = null
)

/** 短剧详情 data.author */
data class DramaAuthor(
    val id: Any? = null,
    val name: String? = null,
    val icon: String? = null
)

/** 短剧详情响应：data.drama + data.author + data.eps */
data class DramaDetailData(
    val drama: DramaInfo? = null,
    val author: DramaAuthor? = null,
    val eps: List<DramaEpisode>? = null
)
