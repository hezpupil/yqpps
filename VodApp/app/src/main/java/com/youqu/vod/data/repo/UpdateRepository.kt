package com.youqu.vod.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 检查更新的三种结果 */
sealed interface UpdateCheck {
    /** 有新版本 */
    data class Available(val info: UpdateInfo) : UpdateCheck

    /** 已是最新（fromUser 决定是否要在界面上提示） */
    data class UpToDate(val fromUser: Boolean) : UpdateCheck

    /** 检查失败 */
    data class Failed(val message: String, val fromUser: Boolean) : UpdateCheck
}

/**
 * 版本更新仓库。
 *
 * 只做两件事：读本机版本号、拉服务端的 update.json 并比对版本号。
 * 解析用 org.json 手写而非 Moshi 反射——字段少、容错高，也省掉混淆时的 keep 规则。
 */
class UpdateRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val client: OkHttpClient
) {

    /** 本机 versionCode */
    val currentVersionCode: Int get() = readVersion().first

    /** 本机 versionName */
    val currentVersionName: String get() = readVersion().second

    suspend fun check(fromUser: Boolean): UpdateCheck = withContext(Dispatchers.IO) {
        val url = settings.updateUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext UpdateCheck.Failed("更新地址无效：$url", fromUser)
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Failed("更新服务返回 ${response.code}", fromUser)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheck.Failed("更新信息为空", fromUser)
                }
                val info = parse(body)
                    ?: return@withContext UpdateCheck.Failed("更新信息格式不正确", fromUser)

                if (info.versionCode > currentVersionCode) {
                    UpdateCheck.Available(info)
                } else {
                    UpdateCheck.UpToDate(fromUser)
                }
            }
        } catch (e: Exception) {
            UpdateCheck.Failed(friendlyError(e), fromUser)
        }
    }

    /** 解析 update.json，兼容顶层直出与 { ok, data } 包装两种结构 */
    private fun parse(raw: String): UpdateInfo? {
        val root = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return null
        val node = root.opt("data").let { if (it is JSONObject) it else root }

        val code = node.optInt("versionCode", 0).takeIf { it > 0 }
            ?: node.optInt("version_code", 0).takeIf { it > 0 }
            ?: return null

        val apkUrl = firstNonBlank(
            node.optString("apkUrl"),
            node.optString("apk_url"),
            node.optString("url")
        ) ?: return null

        if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) return null

        val name = firstNonBlank(node.optString("versionName"), node.optString("version_name"))
            ?: code.toString()

        return UpdateInfo(
            versionCode = code,
            versionName = name,
            apkUrl = apkUrl,
            changelog = node.optString("changelog").orEmpty(),
            force = node.optBoolean("force", false),
            size = node.optLong("size", 0L)
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    @Suppress("DEPRECATION")
    private fun readVersion(): Pair<Int, String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(pkg, 0)
        }
        val code = PackageInfoCompat.getLongVersionCode(info).toInt()
        return code to (info.versionName ?: code.toString())
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "连不上更新服务器"
        is SocketTimeoutException -> "更新检查超时"
        is IOException -> "网络异常：${e.message ?: "未知错误"}"
        else -> e.message ?: "未知错误"
    }
}
