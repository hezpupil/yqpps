package com.youqu.vod.direct

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 直连上游站点的 HTTP 客户端（等价于 PHP 的 src/HttpClient.php + Decoder.php）。
 *
 * 关键点（漏一个就会 403 / 解码失败）：
 * - 所有请求带 Cookie: device={device}
 * - User-Agent 尾部必须拼 " abab/{device}"
 * - Origin / Referer 指向上游站点自身
 * - 响应体是「倒序 Base64」，strrev 后 Base64 解码得到 JSON
 * - code==1 表示成功（兼容 0 / 200）
 */
class UpstreamClient(
    private val tokens: TokenProvider
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 上游解码后的完整响应：{"code":1,"message":"","data":{...}} */
    class UpstreamResult(
        val json: JSONObject,
        val raw: String
    ) {
        val code: Int get() = json.optInt("code", -1)
        val message: String get() = json.optString("message", "")
        val data: JSONObject? get() = json.optJSONObject("data")
        val success: Boolean get() = code == 1 || code == 0 || code == 200

        /** 与 PHP Auth::isAuthExpired 一致 */
        val authExpired: Boolean
            get() {
                if (code in intArrayOf(401, 403, 4001, 4010)) return true
                val m = message.lowercase()
                return listOf("token", "登录", "login", "auth").any { m.contains(it) }
            }
    }

    /** 构造公共请求头（业务接口） */
    private fun headers(cred: TokenProvider.Credential): Map<String, String> = mapOf(
        "Cookie" to "device=${cred.device}",
        "User-Agent" to DirectConfig.apiUserAgent(cred.device),
        "Origin" to cred.baseUrl,
        "Referer" to "${cred.baseUrl}/",
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh-Hans;q=0.9"
    )

    /** 解码响应体：倒序 → Base64 → UTF-8 JSON */
    fun decode(raw: String): UpstreamResult {
        val trimmed = raw.trim()
        val reversed = trimmed.reversed()
        val jsonText = try {
            String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            // 宽松重试：去掉可能的空白/换行
            val clean = reversed.filter { !it.isWhitespace() }
            String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
        }
        return UpstreamResult(JSONObject(jsonText), trimmed)
    }

    /** 业务 GET */
    fun get(path: String, query: Map<String, String> = emptyMap()): UpstreamResult =
        execute(needAuth = false) { cred ->
            Request.Builder().url(buildUrl(cred.baseUrl, path, query)).get()
        }

    /** 业务 POST（multipart/form-data，与 PHP buildMultipartBody 完全一致） */
    fun postMultipart(
        path: String,
        fields: Map<String, String>,
        query: Map<String, String> = emptyMap(),
        needAuth: Boolean = false
    ): UpstreamResult = execute(needAuth = needAuth) { cred ->
        val allFields = if (needAuth) fields + ("token" to cred.token) else fields
        val boundary = "----WebKitFormBoundary" + randomBoundary()
        val body = buildMultipartBody(allFields, boundary)
        Request.Builder()
            .url(buildUrl(cred.baseUrl, path, query))
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
    }

    /** 统一执行：注入公共头 → 发请求 → 解码 → 鉴权失效自动刷新 token 重试一次 */
    private fun execute(
        needAuth: Boolean,
        builder: (TokenProvider.Credential) -> Request.Builder
    ): UpstreamResult {
        var cred = tokens.get()
        repeat(2) { attempt ->
            val req = builder(cred).also { b ->
                headers(cred).forEach { (k, v) -> b.header(k, v) }
            }.build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (attempt == 0 && resp.code in 401..403) {
                            cred = tokens.get(forceRefresh = true)
                            return@use
                        }
                        throw IOException("上游 HTTP ${resp.code}")
                    }
                    val raw = resp.body?.string().orEmpty()
                    val result = decode(raw)
                    if (attempt == 0 && (result.authExpired || (needAuth && !result.success))) {
                        cred = tokens.get(forceRefresh = true)
                        return@use
                    }
                    return result
                }
            } catch (e: IOException) {
                if (attempt == 1) throw e
            }
        }
        throw IOException("上游请求失败")
    }

    /** 拉文本（播放列表 / 图片混淆体），带 abab UA 与 device Cookie */
    fun fetchRaw(url: String, extraHeaders: Map<String, String> = emptyMap()): Response {
        val cred = tokens.get()
        val req = Request.Builder().url(url).get().apply {
            header("User-Agent", DirectConfig.apiUserAgent(cred.device))
            header("Cookie", "device=${cred.device}")
            header("Accept", "*/*")
            // 禁止 OkHttp 透明 gzip：否则上游 Content-Length 是压缩后长度，
            // 而本地网关转发的是解压后的字节，两者不一致会让播放器卡住。
            header("Accept-Encoding", "identity")
            extraHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(req).execute()
    }

    /** 图片回源专用：UA 不带 abab，Referer 指向上游站点 */
    fun fetchImage(url: String): Response {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", DirectConfig.IPHONE_UA)
            .header("Referer", "${DirectConfig.DEFAULT_UPSTREAM}/")
            .header("Accept", "*/*")
            .build()
        return client.newCall(req).execute()
    }

    private fun buildUrl(base: String, path: String, query: Map<String, String>): String {
        val sb = StringBuilder(base).append(path)
        if (query.isNotEmpty()) {
            sb.append('?')
            query.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append('&')
                sb.append(java.net.URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        return sb.toString()
    }

    private fun buildMultipartBody(fields: Map<String, String>, boundary: String): String {
        val sb = StringBuilder()
        fields.forEach { (name, value) ->
            sb.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString()
    }

    private fun randomBoundary(): String {
        val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { pool.random() }.joinToString("")
    }
}
