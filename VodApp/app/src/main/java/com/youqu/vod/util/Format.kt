package com.youqu.vod.util

import com.youqu.vod.data.model.VideoItem

/** 网关字段可能是数字也可能是字符串，统一转 Int */
fun anyToInt(value: Any?): Int = when (value) {
    null -> 0
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull() ?: 0
    else -> 0
}

/** 网关字段统一转字符串 */
fun anyToString(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    else -> value.toString()
}

/** 视频 ID：可能是数字或字符串，统一成字符串用于路由与请求 */
val VideoItem.vid: String
    get() = anyToString(id)

/** 时长（秒） */
val VideoItem.durationSec: Int
    get() = anyToInt(duration)

/** 秒 → mm:ss / h:mm:ss */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    val ss = if (s < 10) "0$s" else s.toString()
    return if (h > 0) {
        val mm = if (m < 10) "0$m" else m.toString()
        "$h:$mm:$ss"
    } else {
        val mm = if (m < 10) "0$m" else m.toString()
        "$mm:$ss"
    }
}
