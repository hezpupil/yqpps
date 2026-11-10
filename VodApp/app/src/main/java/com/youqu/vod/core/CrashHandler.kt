package com.youqu.vod.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃捕获：把未捕获异常的堆栈写到私有目录，下次启动可直接在应用内查看并复制。
 *
 * 关键点：IJKPlayer 是 native 播放器，一部分崩溃发生在 JNI 层（SIGSEGV / SIGABRT），
 * 这类崩溃进程直接被信号杀死，**Java 的 Thread.setDefaultUncaughtExceptionHandler 抓不到**，
 * 也就不会留下任何日志。所以除 Java 异常外，这里额外用「存活标记 + 前台状态」来发现
 * 「上一次是否在前台异常退出」，让 native 崩溃也能在重开时弹一次浮层。
 */
object CrashHandler {

    private const val TAG = "VOD-CRASH"
    private const val FILE_NAME = "crash_log.txt"
    private const val PREFS = "crash_state"
    private const val KEY_PENDING = "pending"
    private const val KEY_PLAYER_CRASH = "player_crash"
    private const val KEY_FOREGROUND = "foreground"
    private const val SESSION_MARKER = "session_alive.marker"
    private const val MAX_CHARS = 24_000

    /** 堆栈里出现这些关键字，就认为崩溃发生在播放链路 */
    private val PLAYER_MARKERS = arrayOf(
        "com.youqu.vod.player",
        "com.youqu.vod.ui.player",
        "androidx.media3",
        "StreamProxy"
    )

    fun install(app: Application) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 探测上一次运行是否「在前台异常退出」：native 崩溃 / 信号杀死，Java handler 抓不到，
        // 只能靠这个存活标记 + 前台状态来发现。后台被系统回收不算崩溃（不弹窗）。
        val marker = File(app.filesDir, SESSION_MARKER)
        val prevForeground = prefs.getBoolean(KEY_FOREGROUND, true)
        if (marker.exists() && prevForeground) {
            prefs.edit().putBoolean(KEY_PENDING, true).apply()
        }
        // 本运行重新写存活标记；只有「干净退出」路径（clear）才会删它
        marker.delete()
        runCatching { marker.createNewFile() }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(app, thread, throwable) }
            Log.e(TAG, "uncaught on ${thread.name}", throwable)
            // 不删存活标记：Java 崩溃也要靠它 + pending 在下一次启动被发现
            if (previous != null) {
                runCatching { previous.uncaughtException(thread, throwable) }
            }
            Process.killProcess(Process.myPid())
        }
    }

    /** 应用回到前台（每次 onResume 调一次） */
    fun markForeground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOREGROUND, true).apply()
    }

    /** 用户主动离开 / 应用进后台（onUserLeaveHint、onStop 调一次） */
    fun markBackground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOREGROUND, false).apply()
    }

    /** 上次运行是否崩过（弹过窗就清掉） */
    fun hasPending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

    fun read(context: Context): String =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrDefault("")

    /** 清掉日志与「崩过」标记；同时复位降级标记，之后会重新尝试代理取流 */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        runCatching { File(context.filesDir, SESSION_MARKER).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, false)
            .putBoolean(KEY_PLAYER_CRASH, false)
            .apply()
    }

    /**
     * 播放链路是否崩过：为 true 时播放页不再启用本地代理，只走直连。
     * 清除崩溃日志（[clear]）会复位这个标记。
     */
    fun playerCrashed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PLAYER_CRASH, false)

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val head = buildString {
            append("=== ").append(stamp).append(" | thread=").append(thread.name)
            append(" | ").append(Build.MODEL)
            append(" | Android ").append(Build.VERSION.RELEASE).append(" (SDK ")
            append(Build.VERSION.SDK_INT).append(") ===\n")
        }
        val file = File(context.filesDir, FILE_NAME)
        val old = runCatching { file.readText() }.getOrDefault("")
        val merged = (head + stack + "\n" + old).let {
            if (it.length > MAX_CHARS) it.substring(0, MAX_CHARS) else it
        }
        runCatching { file.writeText(merged) }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PENDING, true).apply()
        if (PLAYER_MARKERS.any { stack.contains(it) }) {
            prefs.edit().putBoolean(KEY_PLAYER_CRASH, true).apply()
        }
    }
}
