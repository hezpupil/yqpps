package com.youqu.vod.direct

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.youqu.vod.data.local.SettingsRepository
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 凭证提供者（凭证仓库模式）。
 *
 * 服务器出口被上游 volc-dcdn WAF 拦截（伪装 404），服务器不再代 App 登录上游。
 * token.php 退化为纯「token 仓库」：只存取 token，不访问上游。闭环如下：
 *
 *   一、get() 先向仓库取缓存 token（可能是本机或其他设备之前回传的），有效直接用；
 *   二、仓库为空 / need_refresh → 调 credential 接口拿【加密的账号密码】+ device + base_url；
 *   三、App 在内存解密账号密码，用【手机自身网络出口】POST /java/v2/login 换 token；
 *   四、把新 token 通过 action=save 回传仓库，供后期 / 其他设备复用；
 *   五、App 用 token 请求上游鉴权失效时，UpstreamClient 调 get(forceRefresh=true)：
 *       先 action=invalidate 上报旧 token 失效，再走二~四重新登录并覆盖仓库。
 *
 * 账号密码只存在于解密后的局部变量，用完即弃，绝不落盘、不进缓存。
 * 本地 cached 只存会话凭证 token/device/baseUrl。
 */
class TokenProvider(
    context: Context,
    private val settings: SettingsRepository
) {
    private val appContext = context.applicationContext

    /**
     * 本地设备码持久化（模拟浏览器：首次打开现场生成，之后 cookie 持久化复用）。
     * 不再依赖服务器 device_id.txt 的固定值——该值从服务器出口反复登录失败，
     * 极可能已被上游 WAF 关联标记为风险设备（激活 200 但登录伪装 404）。
     */
    private val devicePrefs = appContext.getSharedPreferences("yq_direct_device", Context.MODE_PRIVATE)

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

    /** 最近一次设备激活结果，供登录失败诊断（"激活OK"/"激活HTTP xxx"） */
    @Volatile
    private var lastActivate = "未激活"

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
                if (forceRefresh) {
                    // 鉴权失效：上报旧 token，让仓库标记 need_refresh；不再信任仓库缓存。
                    cached?.token?.let { old -> runCatching { reportInvalid(base, old) } }
                    cached = null
                } else {
                    // 常规取用：优先复用仓库缓存 token（含其他设备回传的）。
                    // 仓库读取失败不致命（兼容旧版 token.php /  transient 网络），回退到 credential 登录。
                    runCatching { fetchRepositoryToken(base) }.getOrNull()?.let { repo ->
                        cached = repo
                        return repo
                    }
                }

                // 仓库无有效 token / 强制刷新 → 手机端登录换新 token 并回传仓库。
                val payload = fetchCredential(base)
                val (username, password) = decryptSecret(payload.cipher)

                // 多候选 device：本地生成的干净 device 优先（模拟浏览器现场生成），
                // 服务器 credential 下发的 device 兜底。任一成功即用其 token/device。
                val candidates = deviceCandidates(payload.device)
                var lastLoginError = "无候选 device"
                var token: String? = null
                var usedDevice = ""
                // 第一轮：OkHttp 直连（快）
                for (dev in candidates) {
                    try {
                        token = loginUpstream(payload.baseUrl, dev, username, password)
                        usedDevice = dev
                        break
                    } catch (e: Exception) {
                        lastLoginError = e.message ?: e.javaClass.simpleName
                    }
                }
                // 第二轮：OkHttp 全失败 → WebView 浏览器网络栈兜底（绕 WAF 的 TLS/JA3 指纹拦截）
                if (token == null) {
                    lastLoginError = "OkHttp: $lastLoginError"
                    for (dev in candidates) {
                        try {
                            token = loginViaWebView(payload.baseUrl, dev, username, password)
                            usedDevice = dev
                            break
                        } catch (e: Exception) {
                            lastLoginError += " | WebView: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }
                val finalToken = token ?: throw IOException(
                    "上游登录失败(已试${candidates.size}个device) · $lastLoginError"
                )
                runCatching { saveToken(base, finalToken, usedDevice) }

                val credential = Credential(
                    token = finalToken,
                    device = usedDevice,
                    baseUrl = payload.baseUrl
                )
                cached = credential
                return credential
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw IllegalStateException(
            "无法获取访问凭证：$lastError（请确认服务器 token.php 已部署凭证仓库版本）"
        )
    }

    /** 从仓库取缓存 token；need_refresh 或空 token 返回 null */
    private fun fetchRepositoryToken(base: String): Credential? {
        val json = callToken(base, "get")
        if (json.optBoolean("need_refresh", false)) return null
        val token = json.optString("token", "").trim()
        if (token.isBlank()) return null
        val device = json.optString("device", "").trim().ifBlank { randomDevice() }
        val baseUrl = json.optString("base_url", DirectConfig.DEFAULT_UPSTREAM)
            .trim().trimEnd('/').ifBlank { DirectConfig.DEFAULT_UPSTREAM }
        return Credential(token, device, baseUrl)
    }

    /** 请求 token.php?action=credential 并解析全部字段 */
    private fun fetchCredential(base: String): CredPayload {
        val json = callToken(base, "credential")
        val cipher = json.optString("cipher", "")
        if (cipher.isBlank()) {
            throw IOException("token.php 未返回 cipher，请在服务器部署凭证仓库版本 token.php")
        }
        val device = json.optString("device", "").trim().ifBlank { randomDevice() }
        val baseUrl = json.optString("base_url", DirectConfig.DEFAULT_UPSTREAM)
            .trim().trimEnd('/').ifBlank { DirectConfig.DEFAULT_UPSTREAM }
        return CredPayload(cipher = cipher, device = device, baseUrl = baseUrl)
    }

    /** 上报旧 token 失效（尽力而为，失败不影响刷新主流程） */
    private fun reportInvalid(base: String, token: String) {
        callToken(base, "invalidate", mapOf("token" to token))
    }

    /** 回传新 token 到仓库（POST form，避免 token 出现在 URL 日志里） */
    private fun saveToken(base: String, token: String, device: String) {
        val url = buildString {
            append(base)
            append(if (base.contains('?')) '&' else '?')
            append("action=save&_t=").append(System.currentTimeMillis())
            if (DirectConfig.TOKEN_ACCESS_KEY.isNotEmpty()) {
                append("&key=").append(DirectConfig.TOKEN_ACCESS_KEY)
            }
        }
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("token", token)
            .add("device", device)
            .build()
        val req = Request.Builder().url(url).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("token.php save HTTP ${resp.code}")
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("error", "token.php save 失败"))
            }
        }
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

    /** 通用 token.php GET 调用 */
    private fun callToken(
        base: String,
        action: String,
        extra: Map<String, String> = emptyMap()
    ): JSONObject {
        val url = buildString {
            append(base)
            append(if (base.contains('?')) '&' else '?')
            append("action=").append(action).append('&')
            append("_t=").append(System.currentTimeMillis())
            if (DirectConfig.TOKEN_ACCESS_KEY.isNotEmpty()) {
                append("&key=").append(DirectConfig.TOKEN_ACCESS_KEY)
            }
            extra.forEach { (k, v) ->
                append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
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
        // 关键：登录前必须先用 device 请求 /java/device/{渠道}/{device} 完成设备注册。
        // HAR（11:42 抓包）证明浏览器启动顺序为：/java/device/hezi1/{device} → /java/v2/login。
        // 未注册的 device 直接登录会被上游伪装成 404（WAF），这正是"上游登录 HTTP 404"的根因。
        activateDevice(baseUrl, device)

        val url = baseUrl.trimEnd('/') + "/java/v2/login"
        val form = FormBody.Builder(Charsets.UTF_8)
            .add("username", username)
            .add("password", password)
            .build()
        // 请求头严格对齐手机浏览器抓包（HAR 11:43 登录 200 的那条）：
        // - 不带 Referer（抓包里没有，WAF 可能据此判定为非浏览器）
        // - 带 Accept-Encoding / Connection，贴近真实 Safari
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Cookie", "device=$device")
            .header("User-Agent", DirectConfig.apiUserAgent(device))
            .header("Origin", baseUrl)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Connection", "keep-alive")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 带上设备激活状态 + 响应体片段，一次看清是 WAF 拦截页还是上游真实 404
                val snippet = body.take(160).replace(Regex("\\s+"), " ")
                val devTail = device.takeLast(6)
                throw IOException("上游登录 HTTP ${resp.code} [$lastActivate device=…$devTail]${if (snippet.isNotBlank()) " · $snippet" else ""}")
            }
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

    /**
     * 设备注册：GET /java/device/{渠道}/{device}?av1=&h265=
     * 与 HAR 11:42:10 那条 200 请求逐字段一致（UA 带 abab 后缀、无 Cookie、无 Referer）。
     * 尽力而为：激活失败不直接抛错，仍尝试登录；但记录响应体便于诊断。
     */
    private fun activateDevice(baseUrl: String, device: String) {
        val url = baseUrl.trimEnd('/') + "/java/device/" +
            DirectConfig.DEVICE_CHANNEL + "/$device?av1=&h265="
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DirectConfig.apiUserAgent(device))
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Connection", "keep-alive")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                lastActivate = if (resp.isSuccessful) "激活OK" else {
                    val snippet = (resp.body?.string().orEmpty()).take(80).replace(Regex("\\s+"), " ")
                    "激活HTTP${resp.code}${if (snippet.isNotBlank()) ":$snippet" else ""}"
                }
                android.util.Log.w("TokenProvider", "设备激活结果：$lastActivate")
            }
        } catch (e: Exception) {
            lastActivate = "激活异常:${e.message}"
            android.util.Log.w("TokenProvider", "设备激活失败（继续尝试登录）：${e.message}")
        }
    }

    /**
     * 用 WebView（Chromium 网络栈）执行登录，绕过 WAF 对 OkHttp 的 TLS/JA3 指纹拦截。
     * 证据：两个 device 都 404、激活 OK、请求头/账号全对齐 → 只剩指纹差异。
     * 流程：加载真实同源首页（拿到浏览器 TLS 指纹 + 会话）→ 页面内 fetch 激活 + 登录 →
     * JS 把响应原文回传 → 复用 decodeUpstream 解出 token。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loginViaWebView(
        baseUrl: String, device: String, username: String, password: String
    ): String {
        val root = baseUrl.trimEnd('/')
        val holder = arrayOfNulls<String>(2) // [0]=status, [1]=body
        val latch = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val webRef = arrayOfNulls<WebView>(1)

        // 账号密码用 URLEncoder 编码后拼进表单，避免特殊字符破坏 JS/表单
        val encUser = java.net.URLEncoder.encode(username, "UTF-8")
        val encPass = java.net.URLEncoder.encode(password, "UTF-8")

        main.post {
            try {
                val wv = WebView(appContext)
                webRef[0] = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.userAgentString = DirectConfig.apiUserAgent(device)
                wv.settings.domStorageEnabled = true
                wv.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE

                wv.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onResult(status: String, body: String) {
                        holder[0] = status
                        holder[1] = body
                        latch.countDown()
                    }
                }, "YqBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 首页加载完成（含浏览器 TLS 会话），页面内 fetch 走同源真实浏览器栈
                        val js = """
                            (async function(){
                              try {
                                var bridge = window.YqBridge;
                                // 先激活设备（与抓包一致），再登录
                                await fetch('/java/device/${DirectConfig.DEVICE_CHANNEL}/$device?av1=&h265=');
                                // Cookie 不手动设（forbidden header）：激活的 Set-Cookie 由 WebView
                                // 自动保存并在同源 fetch 时带回，完全复刻 Safari 行为
                                var r = await fetch('/java/v2/login', {
                                  method: 'POST',
                                  headers: {
                                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                                  },
                                  body: 'username=$encUser&password=$encPass'
                                });
                                var t = await r.text();
                                bridge.onResult(String(r.status), t);
                              } catch (e) {
                                bridge.onResult('JSERR', String(e));
                              }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        holder[0] = "PAGEERR"
                        holder[1] = "code=${error?.errorCode} ${error?.description}"
                        latch.countDown()
                    }
                }

                wv.loadUrl("$root/?ch=${DirectConfig.DEVICE_CHANNEL}&v=202611")
            } catch (e: Exception) {
                holder[0] = "WEBERR"
                holder[1] = e.message ?: e.javaClass.simpleName
                latch.countDown()
            }
        }

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw IOException("WebView 登录超时(30s)")
            val status = holder[0] ?: throw IOException("WebView 登录无结果")
            val body = holder[1].orEmpty()
            if (status != "200") {
                throw IOException("WebView 登录 HTTP $status device=…${device.takeLast(6)} · ${body.take(120)}")
            }
            val decoded = decodeUpstream(body)
            val code = decoded.optInt("code", -1)
            if (code != 1 && code != 0 && code != 200) {
                throw IOException("WebView 登录被拒 code=$code ${decoded.optString("message", "")}")
            }
            val token = decoded.optJSONObject("data")?.optString("token", "").orEmpty()
            if (token.isBlank()) throw IOException("WebView 登录成功但未返回 token")
            return token
        } finally {
            main.post { webRef[0]?.destroy() }
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

    /**
     * 登录 device 候选（按优先级）：
     *  1. 本地生成并持久化的干净 device（首选，模拟浏览器现场生成，避免固定 device 被风控）
     *  2. 服务器 credential 下发的 device（兜底）
     * 两者去重，保证登录失败时能自动换设备重试。
     */
    private fun deviceCandidates(serverDevice: String): List<String> {
        val result = LinkedHashSet<String>()
        val local = localDevice()
        if (local.isNotBlank()) result.add(local)
        if (serverDevice.isNotBlank()) result.add(serverDevice.trim())
        return result.toList()
    }

    /**
     * 本地 device：首次调用生成 20 位随机串并持久化，之后固定复用（像浏览器 cookie）。
     * 被风控时可通过 clearLocalDevice() 强制换一个。
     */
    private fun localDevice(): String {
        val saved = devicePrefs.getString("device_id", "").orEmpty().trim()
        if (Regex("^[a-z0-9]{20}$").matches(saved)) return saved
        val fresh = randomDevice()
        devicePrefs.edit().putString("device_id", fresh).apply()
        return fresh
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
