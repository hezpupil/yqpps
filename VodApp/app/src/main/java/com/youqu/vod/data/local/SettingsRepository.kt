package com.youqu.vod.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 播放历史（字段与 iOS 端 PlayRecord 一致） */
data class PlayRecord(
    val vid: String,
    val title: String,
    val time: Long
) {
    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

/**
 * 轻量本地存储：网关地址、搜索历史。
 * 用 SharedPreferences 而非 DataStore，少一个依赖、少一处版本的构建风险。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 默认网关地址，可在「设置」里改成其他服务器地址 */
    var baseUrl: String
        get() {
            // 迁移守卫：旧版内置默认域名 app.91nb.lol、yq-app.91nb.lol 在升级后自动映射到新默认 yq-app.yqnb.lol。
            // 原因：SharedPreferences 跨版本更新不清除，旧设备里仍残留旧域名，
            // 若不映射，升级后首屏会一直走旧域名。用户手动设置的其他地址不受影响。
            val stored = prefs.getString(KEY_BASE_URL, null)
            val effective = stored ?: DEFAULT_BASE_URL
            return if (effective == OLD_DEFAULT_BASE_URL || effective == OLD2_DEFAULT_BASE_URL) DEFAULT_BASE_URL else effective
        }
        set(value) = prefs.edit()
            .putString(KEY_BASE_URL, GatewayNormalizer.normalize(value))
            .apply()

    /** 更新信息地址（一个静态 update.json），可在「设置」里改 */
    var updateUrl: String
        get() {
            // 同 baseUrl 的迁移守卫：旧版默认 app.91nb.lol/update.json、yq-app.91nb.lol/update.json 升级后自动映射到新默认。
            val stored = prefs.getString(KEY_UPDATE_URL, null)
            val effective = stored ?: DEFAULT_UPDATE_URL
            return if (effective == OLD_DEFAULT_UPDATE_URL || effective == OLD2_DEFAULT_UPDATE_URL) DEFAULT_UPDATE_URL else effective
        }
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value.trim()).apply()

    var searchHistory: List<String>
        get() {
            val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit()
            .putString(KEY_HISTORY, JSONArray().apply { value.forEach { put(it) } }.toString())
            .apply()

    fun addHistory(keyword: String) {
        val kw = keyword.trim()
        if (kw.isBlank()) return
        val next = (listOf(kw) + searchHistory.filter { it != kw }).take(MAX_HISTORY)
        searchHistory = next
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /** 播放历史：最新在前、按 vid 去重、上限 MAX_HISTORY 条 */
    var playHistory: List<PlayRecord>
        get() {
            val raw = prefs.getString(KEY_PLAY_HISTORY, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val vid = o.optString("vid")
                    val title = o.optString("title")
                    if (vid.isBlank() || title.isBlank()) {
                        null
                    } else {
                        PlayRecord(vid, title, o.optLong("time"))
                    }
                }
            }.getOrDefault(emptyList())
        }
        set(value) = prefs.edit()
            .putString(
                KEY_PLAY_HISTORY,
                JSONArray().apply {
                    value.forEach { r ->
                        put(
                            JSONObject()
                                .put("vid", r.vid)
                                .put("title", r.title)
                                .put("time", r.time)
                        )
                    }
                }.toString()
            )
            .apply()

    fun addPlayHistory(vid: String, title: String) {
        val id = vid.trim()
        val name = title.trim()
        if (id.isBlank() || name.isBlank()) return
        val next = listOf(PlayRecord(id, name, System.currentTimeMillis())) +
            playHistory.filter { it.vid != id }
        playHistory = next.take(MAX_HISTORY)
    }

    fun clearPlayHistory() {
        prefs.edit().remove(KEY_PLAY_HISTORY).apply()
    }

    private companion object {
        const val PREF_NAME = "vod_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_HISTORY = "search_history"
        const val KEY_PLAY_HISTORY = "play_history"
        const val KEY_UPDATE_URL = "update_url"
        const val MAX_HISTORY = 12
        // 线上网关地址（HTTPS）。应用内「设置」页可随时覆盖为其他地址，
        // 覆盖后以本地存储为准；如需强制回到此地址，清掉应用数据即可。
        // 注意：真实生效地址优先取 DomainRepository 从远端 yq_domain.txt 拉的覆盖值，
        // 此处仅为内置兜底（远程拉取失败/首次启动前使用）。
        const val DEFAULT_BASE_URL = "https://yq-app.yqnb.lol/"
        // 在线更新：把 update.json 与新版 APK 放在服务器任意可达位置即可，
        // 不必与 api.php 同目录。详见 README「在线更新」。
        // 网关域名由 yq-app.91nb.lol 迁到 yq-app.yqnb.lol
        //（安装包放 /app/apk/ 与 /app/ipa/，update.json 放站点根目录）。
        const val DEFAULT_UPDATE_URL = "https://yq-app.yqnb.lol/update.json"
        // 旧版内置默认域名/更新地址（仅用于升级迁移守卫，见下方 getter）。
        // 第一次迁移：app.91nb.lol → yq-app.91nb.lol；第二次迁移：yq-app.91nb.lol → yq-app.yqnb.lol。
        const val OLD_DEFAULT_BASE_URL = "https://app.91nb.lol/"
        const val OLD_DEFAULT_UPDATE_URL = "https://app.91nb.lol/update.json"
        const val OLD2_DEFAULT_BASE_URL = "https://yq-app.91nb.lol/"
        const val OLD2_DEFAULT_UPDATE_URL = "https://yq-app.91nb.lol/update.json"
    }
}

/** 与 GatewayUrls.normalize 同逻辑，避免 data 层反向依赖 remote 层 */
private object GatewayNormalizer {
    fun normalize(base: String): String {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
