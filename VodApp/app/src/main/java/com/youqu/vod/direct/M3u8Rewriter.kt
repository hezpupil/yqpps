package com.youqu.vod.direct

import java.net.URI

/**
 * HLS 播放列表改写（等价于 PHP 的 m3u8.php）。
 *
 * 播放列表本身是**明文**（分片仍是标准 AES-128，由 ExoPlayer 自行解密）。
 * 但 KEY 与分片必须用 abab/{device} UA + Cookie 才能取，播放器无法自定义这些头，
 * 所以把 URL 全部改写到本地 /seg?u=<base64url> 由本地网关代取。
 */
object M3u8Rewriter {

    private val URI_REGEX = Regex("URI=\"([^\"]+)\"")

    /**
     * @param playlist 上游播放列表原文
     * @param playlistUrl 播放列表自身 URL（用于把相对地址解析成绝对地址）
     * @param localBase 本地网关根，如 http://127.0.0.1:19025
     */
    fun rewrite(playlist: String, playlistUrl: String, localBase: String): String {
        val base = runCatching { URI.create(playlistUrl) }.getOrNull()
        val sb = StringBuilder()
        playlist.replace("\r", "").split("\n").forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#") -> {
                    val m = URI_REGEX.find(line)
                    if (m != null) {
                        val abs = absolutize(m.groupValues[1], base)
                        sb.append(line.replace(m.groupValues[1], segUrl(abs, localBase)))
                    } else {
                        sb.append(line)
                    }
                    sb.append('\n')
                }
                else -> {
                    val abs = absolutize(line, base)
                    sb.append(segUrl(abs, localBase)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun segUrl(absoluteUrl: String, localBase: String): String =
        "${localBase.trimEnd('/')}/seg?u=" + b64url(absoluteUrl)

    /** 相对地址解析：优先按播放列表 host，KEY 的 "/" 开头形式按 mail.xxcjpt.com 补齐 */
    private fun absolutize(raw: String, base: URI?): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("/")) {
            val host = base?.host
                ?.takeIf { it in DirectConfig.SEGMENT_HOSTS }
                ?.let { "${base.scheme}://$it" }
                ?: DirectConfig.KEY_FALLBACK_HOST
            return host + raw
        }
        val b = base ?: return raw
        val parent = b.toString().substringBeforeLast('/') + "/"
        return runCatching { URI.create(parent).resolve(raw).toString() }.getOrDefault(raw)
    }
}
