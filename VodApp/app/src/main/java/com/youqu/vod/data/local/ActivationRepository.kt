package com.youqu.vod.data.local

import android.content.Context
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 设备激活门禁（云端验证）：
 *
 * 1. 取本机设备标识（ANDROID_ID，异常时回落到首次随机生成的 UUID），
 *    格式化为「设备码」XXXX-XXXX-XXXX-XXXX 展示给用户。
 * 2. 用户把设备码发给管理员；管理员在 PHP 管理后台生成/绑定激活码。
 * 3. 用户输入激活码 → App 把「设备码 + 激活码」POST 到云端验证接口，
 *    由服务端判定，App 本地不做任何加密比对（防本地破解）。
 * 4. 云端验证通过后，本地记录「已激活设备码」，此后启动直接放行不再联网。
 *    清数据 / 恢复出厂会清掉标记或改变设备码 → 需要重新走云端验证。
 *
 * 安全说明：
 * - 验证地址与请求令牌硬编码在 APK 内，不提供界面修改入口，
 *   防止把 App 指向伪造服务器绕过验证。
 * - 管理后台可随时解绑/删除设备，云端始终保有最终控制权。
 */
class ActivationRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 本机设备码（展示用，含连字符），如 9A3C-1F20-B7D4-88E2 */
    fun deviceCode(): String = formatGroups(deviceCodeRaw())

    /** 本机是否已激活过（首次云端验证通过后本机放行） */
    fun isActivated(): Boolean {
        val saved = prefs.getString(KEY_ACTIVATED_FOR, null) ?: return false
        return saved == deviceCodeRaw()
    }

    /**
     * 云端验证激活码。耗时网络调用，必须在协程中调用。
     * 成功：服务端确认「设备码-激活码」匹配（必要时完成绑定）并记录本机激活标记。
     * 失败：返回服务端给出的原因（激活码不存在/已绑定其他设备/网络异常等）。
     */
    suspend fun activate(input: String): ActivateResult = withContext(Dispatchers.IO) {
        val code = normalize(input)
        if (code.length != CODE_LEN) {
            return@withContext ActivateResult.Failure("激活码格式不正确（12 位字母数字）")
        }
        val form = FormBody.Builder()
            .add("device", deviceCodeRaw())
            .add("code", code)
            .build()
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("X-Act-Token", REQUEST_TOKEN)
            .post(form)
            .build()
        val result = runCatching {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                when {
                    json.optBoolean("ok", false) -> ActivateResult.Success
                    else -> ActivateResult.Failure(
                        json.optString("msg", "验证失败").ifBlank { "验证失败" }
                    )
                }
            }
        }
        result.getOrElse { e ->
            ActivateResult.Failure("无法连接验证服务器，请检查网络后重试")
        }
    }

    /** 云端验证成功后调用：持久化本机激活标记 */
    fun markActivated() {
        prefs.edit().putString(KEY_ACTIVATED_FOR, deviceCodeRaw()).apply()
    }

    /**
     * 状态核对（App 每次启动调用）：本机设备码是否仍绑定在有效激活码上。
     * 管理后台删除/解绑后，本方法返回 Blocked，App 拦截进入。
     * 断网/服务器不可达返回 NetworkError，App 端同样拦截并提示连接网络。
     */
    suspend fun checkStatus(): StatusResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("action", "status")
            .add("device", deviceCodeRaw())
            .build()
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("X-Act-Token", REQUEST_TOKEN)
            .post(form)
            .build()
        val result = runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optBoolean("ok", false)) {
                    StatusResult.Allowed
                } else {
                    StatusResult.Blocked(
                        json.optString("msg", "设备未激活或已被管理员停用")
                            .ifBlank { "设备未激活或已被管理员停用" }
                    )
                }
            }
        }
        result.getOrElse { StatusResult.NetworkError }
    }

    /**
     * 设备信息上报（App 每次打开自动调用）：把机型/系统/版本连同机器码
     * 上送到后台「待开通设备」，管理员可直接开通或生成激活码绑定。
     * 失败静默（返回 false），不影响界面流程。
     */
    suspend fun report(model: String, os: String, app: String): Boolean = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("action", "report")
            .add("device", deviceCodeRaw())
            .add("model", model.take(64))
            .add("os", os.take(64))
            .add("app", app.take(64))
            .build()
        val request = Request.Builder()
            .url(VERIFY_URL)
            .header("X-Act-Token", REQUEST_TOKEN)
            .post(form)
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
            }
        }.getOrDefault(false)
    }

    /** 清除激活标记（调试用；正式界面不暴露） */
    fun deactivate() {
        prefs.edit().remove(KEY_ACTIVATED_FOR).apply()
    }

    // ---------- 内部实现 ----------

    /** 设备码原始值：16 位大写 hex（无连字符） */
    private fun deviceCodeRaw(): String {
        val saved = prefs.getString(KEY_DEVICE_ID, null)
        if (saved != null) return saved
        var raw = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.uppercase()?.trim().orEmpty()
        // ANDROID_ID 仅保留 hex，异常设备（空/全 0/含非 hex）回落到随机 UUID（首次生成后固定）
        val hexOnly = raw.filter { it.isDigit() || it in 'A'..'F' }
        if (hexOnly.length != DEVICE_LEN || hexOnly == "0".repeat(DEVICE_LEN)) {
            raw = UUID.randomUUID().toString().replace("-", "").uppercase().take(DEVICE_LEN)
        } else {
            raw = hexOnly
        }
        prefs.edit().putString(KEY_DEVICE_ID, raw).apply()
        return raw
    }

    private fun formatGroups(raw: String): String {
        val sb = StringBuilder()
        raw.chunked(4).forEachIndexed { i, part ->
            if (i > 0) sb.append('-')
            sb.append(part)
        }
        return sb.toString()
    }

    sealed interface ActivateResult {
        data object Success : ActivateResult
        data class Failure(val msg: String) : ActivateResult
    }

    /** 启动状态核对结果 */
    sealed interface StatusResult {
        /** 设备码仍有效，放行 */
        data object Allowed : StatusResult
        /** 被管理员删除/解绑，拦截并提示 */
        data class Blocked(val msg: String) : StatusResult
        /** 网络异常（服务器不可达/超时），不算被停用 */
        data object NetworkError : StatusResult
    }

    companion object {
        private const val PREF_NAME = "activation_gate"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACTIVATED_FOR = "activated_for"

        private const val DEVICE_LEN = 16   // 设备码 16 hex
        private const val CODE_LEN = 12     // 激活码 12 位字母数字

        /**
         * 云端验证接口地址（api.php 部署后的完整 URL）。
         * 刻意不做成界面可配置项——否则可被指向伪造服务器绕过验证。
         * 部署路径变化时改这里重新打包即可。
         */
        private const val VERIFY_URL = "https://yq-app.yqnb.lol/act/api.php"

        /** 请求令牌，需与 api.php 内 ACT_TOKEN 一致；防止接口被第三方穷举调用 */
        private const val REQUEST_TOKEN = "yq-act-9f3K7bQ2mW"
    }
}

/**
 * 输入归一化：去连字符/空格、转大写，保留全部字母数字。
 * 必须与后台 act_normalize_code 保持一致：激活码字符集为
 * 「避开 0/O/1/I 的字母数字」，包含 G、K、M 等非 A-F 字母，
 * 若误用 A-F 过滤会删掉这些字符导致长度不足而报「格式不正确」。
 */
private fun normalize(s: String): String =
    s.trim().uppercase().filter { it.isLetterOrDigit() }
