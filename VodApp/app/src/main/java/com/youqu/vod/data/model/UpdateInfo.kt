package com.youqu.vod.data.model

/**
 * 更新信息。对应服务端 update.json 的字段。
 *
 * 解析时兼容驼峰与下划线两种写法，便于你用任意风格的 JSON。
 */
data class UpdateInfo(
    /** 版本号（整数），大于本地 versionCode 才提示更新 */
    val versionCode: Int,
    /** 版本名，展示用 */
    val versionName: String,
    /** APK 下载地址，必须是 http/https 直链 */
    val apkUrl: String,
    /** 更新说明，支持 \n 换行 */
    val changelog: String,
    /** 是否强制更新（true 时对话框不可取消） */
    val force: Boolean,
    /** 包体积（字节），0 表示未知 */
    val size: Long
)
