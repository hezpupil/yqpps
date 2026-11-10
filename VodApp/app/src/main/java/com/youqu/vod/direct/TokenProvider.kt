package com.youqu.vod.direct

import android.content.Context
import android.util.Base64
import com.youqu.vod.data.local.SettingsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 凭证提供者：向服务器 token.php 取 token + device。
 *
 * 账号密码只在服务器。APP 拿到的是一次性会话凭证，可缓存在内存（不落盘，减少泄露面），
 * 失效时带 refresh=1 强制服务器重新登录。
 */
class TokenProvider(
    context: Context,
    private val settings: SettingsRepository
) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class Credential(
        val token: String,
        val device: String,
        val baseUrl: String
    )

    @Volatile
    private var cached: Credential? = null

    @Synchronized
    fun get(forceRefresh: Boolean = false): Credential {
        if (!forceRefresh) cached?.let { return it }

        val urls = linkedSetOf(
            DirectConfig.tokenUrl(settings),
            DirectConfig.FALLBACK_TOKEN_URL
        )
        var lastError = "未知错误"
        for (base in urls) {
            try {
                val url = buildString {
                    append(base)
                    append(if (base.contains('?')) '&' else '?')
                    if (forceRefresh) append("refresh=1&")
                    append("_t=").append(System.currentTimeMillis())
                    if (DirectConfig.TOKEN_ACCESS_KEY.isNotEmpty()) {
                        append("&key=").append(DirectConfig.TOKEN_ACCESS_KEY)
                    }
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "token.php HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        lastError = json.optString("error", "token.php 返回失败")
                        return@use
                    }
                    val token = json.optString("token", "")
                    val device = json.optString("device", "")
                    if (token.isBlank() || device.isBlank()) {
                        lastError = "token.php 未返回 token/device"
                        return@use
                    }
                    val credential = Credential(
                        token = token,
                        device = device,
                        baseUrl = json.optString("base_url", DirectConfig.DEFAULT_UPSTREAM)
                            .trim().trimEnd('/')
                    )
                    cached = credential
                    return credential
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException("无法获取访问凭证：$lastError（请确认服务器已部署 token.php）")
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }
}

/** base64url 编码（与 PHP b64url() 规则一致） */
fun b64url(s: String): String = Base64.encodeToString(
    s.toByteArray(Charsets.UTF_8),
    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
)

/** base64url 解码 */
fun b64urlDecode(s: String): String? = runCatching {
    val padded = s.replace('-', '+').replace('_', '/').let {
        val pad = it.length % 4
        if (pad == 0) it else it + "=".repeat(4 - pad)
    }
    String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()
