package com.youqu.vod.duanju

import org.json.JSONArray
import org.json.JSONObject

/** Go 核心返回的已安装模块 / 安装预览（nativeModuleInfo） */
data class SourceModule(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val hosts: List<String>,
    val capabilities: List<String>,
    val adult: Boolean,
    val installed: Boolean,
    val previous: String,
    val token: String,
    val error: String
) {
    val displayName: String get() = name.ifBlank { id }
    val isBroken: Boolean get() = error.isNotBlank()
    companion object
}

/** 在线仓库 / 内置条目（moduleIndexEntry） */
data class IndexModule(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val adult: Boolean,
    val bundled: Boolean,
    val repo: String,
    val tag: String
) {
    val displayName: String get() = name.ifBlank { id }
    companion object
}

data class ModuleStore(
    val items: List<IndexModule>,
    val warning: String
)

internal fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { opt(it)?.toString()?.takeIf { v -> v.isNotBlank() && v != "null" } }

internal fun SourceModule.Companion.from(o: JSONObject): SourceModule = SourceModule(
    id = o.optString("id"),
    name = o.optString("name"),
    version = o.optString("version"),
    description = o.optString("description"),
    hosts = (o.optJSONArray("hosts") ?: JSONArray()).strings(),
    capabilities = (o.optJSONArray("capabilities") ?: JSONArray()).strings(),
    adult = o.optBoolean("adult"),
    installed = o.optBoolean("installed"),
    previous = o.optString("previous"),
    token = o.optString("token"),
    error = o.optString("error")
)

internal fun IndexModule.Companion.from(o: JSONObject): IndexModule = IndexModule(
    id = o.optString("id"),
    name = o.optString("name"),
    version = o.optString("version"),
    description = o.optString("description"),
    adult = o.optBoolean("adult"),
    bundled = o.optBoolean("bundled"),
    repo = o.optString("repo"),
    tag = o.optString("tag")
)

/** a.b.c 版本比较，不合法时返回 false */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parse(v: String): List<Int> = v.trim().split('.').map { it.toIntOrNull() ?: 0 }
    val a = parse(candidate)
    val b = parse(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x > y) return true
        if (x < y) return false
    }
    return false
}
