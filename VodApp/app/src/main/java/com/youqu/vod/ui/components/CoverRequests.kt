package com.youqu.vod.ui.components

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.net.URI

/**
 * 短剧封面请求：第三方模块/红果的封面 CDN 普遍带防盗链，裸 GET 会被 403，
 * 表现为封面一片空白。这里补一个桌面浏览器 UA + 同源 Referer，覆盖绝大多数
 * 只校验 Referer 是否为自身域名的图床。影视封面仍走内置网关，不受影响。
 */
private const val COVER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

fun coverRequest(context: Context, url: String, width: Int, height: Int): ImageRequest {
    val referer = runCatching {
        val uri = URI(url)
        uri.scheme?.let { scheme -> uri.host?.let { host -> "$scheme://$host/" } }
    }.getOrNull()
    return ImageRequest.Builder(context)
        .data(url)
        .setHeader("User-Agent", COVER_UA)
        .apply { if (!referer.isNullOrBlank()) setHeader("Referer", referer) }
        .size(width, height)
        .crossfade(true)
        .build()
}
