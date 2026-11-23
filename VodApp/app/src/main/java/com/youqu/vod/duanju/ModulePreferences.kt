package com.youqu.vod.duanju

import android.content.Context

/**
 * 短剧模块的本地偏好：启用状态、默认源、成人模块确认。
 *
 * 与 guoapp 一致，核心只负责模块的装卸，不持久化 UI 层的启用开关。
 */
class ModulePreferences(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("youqu_module_prefs", Context.MODE_PRIVATE)

    fun isEnabled(id: String): Boolean = sp.getBoolean("enabled:$id", true)

    fun setEnabled(id: String, enabled: Boolean) {
        sp.edit().putBoolean("enabled:$id", enabled).apply()
    }

    /** null 表示自动选择（优先红果，其次按安装顺序） */
    val preferredSource: String?
        get() = sp.getString("preferred_source", null)?.takeIf { it.isNotBlank() }

    fun setPreferredSource(id: String?) {
        sp.edit().putString("preferred_source", id).apply()
    }

    /** 用户是否确认过「仅限成年用户安装限制级模块」 */
    val adultConfirmed: Boolean
        get() = sp.getBoolean("adult_confirmed", false)

    fun confirmAdult() {
        sp.edit().putBoolean("adult_confirmed", true).apply()
    }
}
