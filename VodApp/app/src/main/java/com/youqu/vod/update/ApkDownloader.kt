package com.youqu.vod.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * APK 下载与安装。
 *
 * 下载到应用私有 cache 目录（无需任何存储权限），再通过 FileProvider 授权给系统安装器，
 * 不往公共目录写文件，Android 10+ 的分区存储也不会出问题。
 */
class ApkDownloader(
    private val context: Context,
    private val client: OkHttpClient
) {

    /**
     * 流式下载，进度回调值为 0~100（总大小未知时只回调一次 0）。
     * 每次下载前清空旧文件，避免磁盘堆积。
     */
    suspend fun download(url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR_NAME)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, FILE_NAME)

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载失败：服务器无响应内容")

            totalLength = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var done = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        if (totalLength > 0) {
                            val percent = (done * 100 / totalLength).toInt().coerceAtMost(100)
                            if (percent != lastReported) {
                                lastReported = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
            }
        }

        if (!file.exists() || file.length() == 0L) throw IOException("下载失败：文件为空")
        if (totalLength > 0 && file.length() != totalLength) throw IOException("下载不完整，请重试")
        file
    }

    /** 最近一次下载的目标文件大小（字节），0 表示服务端未提供 */
    var totalLength: Long = 0L
        private set

    /** 是否已被允许安装未知来源应用（Android 8.0+） */
    fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** 跳转到「允许安装未知应用」设置页 */
    fun openInstallPermissionSetting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 调起系统安装界面 */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private companion object {
        const val DIR_NAME = "update"
        const val FILE_NAME = "update.apk"
    }
}
