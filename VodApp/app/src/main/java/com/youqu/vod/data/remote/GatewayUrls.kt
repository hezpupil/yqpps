package com.youqu.vod.data.remote

import android.util.Base64
import com.youqu.vod.direct.DirectRuntime
import java.net.URLEncoder

/**
 * 网关地址拼装。
 *
 * 优先走 APP 内置网关（DirectRuntime.localBase，见 com.youqu.vod.direct）：
 * 直连上游 + 本地解码/解密，服务端只保留 token.php 下发凭证。
 * 内置网关未就绪时自动回退到原 PHP 网关（base 下的 api.php / image.php / m3u8.php），
 * 保证出问题时老行为仍可用，便于排障与灰度。
 *
 * 约定：baseUrl 必须以 "/" 结尾，例如 https://video.example.com/crawler/
 */
object GatewayUrls {

    fun normalize(base: String): String {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** 当前生效的网关根：内置网关优先，否则服务器网关。两者都必须以 "/" 结尾 */
    private fun root(base: String): String =
        normalize(DirectRuntime.localBase.takeIf { it.isNotEmpty() } ?: base)

    /** 内置网关就绪时端点路径与 PHP 文件名不同 */
    private fun endpoint(localPath: String, phpName: String): String =
        if (DirectRuntime.enabled) localPath else phpName

    /**
     * 组装接口地址。
     * 网关同时支持 GET 查询串与 POST 表单，这里统一用 GET。
     */
    fun api(base: String, action: String, vararg params: Pair<String, String>): String {
        val sb = StringBuilder(root(base))
            .append(endpoint("api", "api.php"))
            .append("?action=").append(action)
        params.forEach { (k, v) ->
            sb.append('&').append(enc(k)).append('=').append(enc(v))
        }
        return sb.toString()
    }

    /** 封面图：?u=<base64url(原始图片地址)>，与 H5 端 b64url() 规则一致 */
    fun image(base: String, rawImage: String): String {
        if (rawImage.isBlank()) return ""
        val encoded = Base64.encodeToString(
            rawImage.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return root(base) + endpoint("img", "image.php") + "?u=" + encoded
    }

    /** 播放地址：网关已把 KEY 与分片改写为代取地址 */
    fun play(base: String, vid: String): String {
        return root(base) + endpoint("m3u8", "m3u8.php") + "?vid=" + enc(vid)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
