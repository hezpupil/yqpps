package com.youqu.vod.direct

import android.content.Context
import android.util.Base64
import com.youqu.vod.data.local.SettingsRepository
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 凭证提供者（C 方案：App 用自身网络出口登录上游）。
 *
 * 背景：服务器出口 IP 被上游 volc-dcdn WAF 拦截（伪装 404），无法再代 App 登录。
 * 新流程：
 *   一、向 token.php?action=credential 请求【加密的账号密码】+ device + base_url
 *      （服务器不访问上游，不受 WAF 影响）；
 *   二、App 在内存里用 CREDENTIAL_AES_KEY 解密得到 username/password；
 *   三、App 用【自己的网络出口】POST {base_url}/java/v2/login 换 token；
 *   四、账号密码仅存在于本次调用的局部变量，用完即弃，绝不落盘、不进缓存。
 *
 * 缓存的是会话凭证 token/device/baseUrl（不是账号密码），失效时整体重来一遍。
 */
class TokenProvider(
    context: Context,
    private val settings: SettingsRepository
) {
    @Suppress("unused")
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

    /** credential 接口一次请求的完整结果（账号密码是密文，解密后才成为明文） */
    private class CredPayload(
        val cipher: String,
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
                // 一、一次请求拿齐：加密账号密码 + device + base_url
                val payload = fetchCredential(base)
                // 二、解密（账号密码只在这个局部作用域里存在）
                val (username, password) = decryptSecret(payload.cipher)
                // 三、用手机出口登录上游换 token
                val token = loginUpstream(payload.baseUrl, payload.device, username, password)

                val credential = Credential(
                    token = token,
                    device = payload.device,
                    baseUrl = payload.baseUrl
                )
                cached = credential
                return credential
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            "无法获取访问凭证：$lastError（请确认服务器 token.php 已部署 C 方案 credential 接口）"
        )
    }

    /** 请求 token.php?action=credential 并解析全部字段 */
    private fun fetchCredential(base: String): CredPayload {
        val json = callToken(base, "credential")
        val cipher = json.optString("cipher", "")
        if (cipher.isBlank()) {
            throw IOException("token.php 未返回 cipher，请在服务器部署 C 方案 credential 接口")
        }
        val device = json.optString("device", "").trim().ifBlank { randomDevice() }
        val baseUrl = json.optString("base_url", DirectConfig.DEFAULT_UPSTREAM)
            .trim().trimEnd('/').ifBlank { DirectConfig.DEFAULT_UPSTREAM }
        return CredPayload(cipher = cipher, device = device, baseUrl = baseUrl)
    }

    /** cipher 单独走一次解密，返回 (username, password) */
    private fun decryptSecret(cipherB64: String): Pair<String, String> {
        val raw = Base64.decode(cipherB64, Base64.DEFAULT)
        val key = SecretKeySpec(
            DirectConfig.CREDENTIAL_AES_KEY.toByteArray(Charsets.UTF_8), "AES"
        )
        val dec = Cipher.getInstance("AES/ECB/PKCS5Padding")
        dec.init(Cipher.DECRYPT_MODE, key)
        val plain = String(dec.doFinal(raw), Charsets.UTF_8)
        val obj = JSONObject(plain)
        val u = obj.optString("u", "")
        val p = obj.optString("p", "")
        if (u.isBlank() || p.isBlank()) throw IOException("凭证内容为空（key 是否与服务器一致？）")
        return u to p
    }

    /** 通用 token.php 调用 */
    private fun callToken(base: String, action: String): JSONObject {
        val url = buildString {
            append(base)
            append(if (base.contains('?')) '&' else '?')
            append("action=").append(action).append('&')
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
            if (!resp.isSuccessful) throw IOException("token.php HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "token.php 返回失败"))
            }
            return json
        }
    }

    /**
     * 用【手机自身网络出口】登录上游：POST /java/v2/login（x-www-form-urlencoded）。
     * 请求头与 HAR 抓包一致：Cookie device + UA 尾 abab/{device} + Origin。
     * 响应是倒序 Base64 的 JSON，取 data.token（32 位）。
     */
    private fun loginUpstream(
        baseUrl: String, device: String, username: String, password: String
    ): String {
        val url = baseUrl.trimEnd('/') + "/java/v2/login"
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("username", username)
            .add("password", password)
            .build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Cookie", "device=$device")
            .header("User-Agent", DirectConfig.apiUserAgent(device))
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("上游登录 HTTP ${resp.code}")
            val decoded = decodeUpstream(body)
            val code = decoded.optInt("code", -1)
            if (code != 1 && code != 0 && code != 200) {
                throw IOException("上游登录被拒 code=$code ${decoded.optString("message", "")}")
            }
            val token = decoded.optJSONObject("data")?.optString("token", "").orEmpty()
            if (token.isBlank()) throw IOException("上游登录成功但未返回 token")
            return token
        }
    }

    /** 上游响应：倒序 → Base64 → UTF-8 JSON */
    private fun decodeUpstream(raw: String): JSONObject {
        val reversed = raw.trim().reversed()
        val jsonText = try {
            String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            String(Base64.decode(reversed.filter { !it.isWhitespace() }, Base64.DEFAULT), Charsets.UTF_8)
        }
        return JSONObject(jsonText)
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    /** device 兜底：20 位小写字母数字，与上游 userCode 规则一致 */
    private fun randomDevice(): String {
        val pool = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..20).map { pool.random() }.joinToString("")
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
