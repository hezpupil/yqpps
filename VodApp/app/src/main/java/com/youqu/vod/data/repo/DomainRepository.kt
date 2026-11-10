package com.youqu.vod.data.repo

import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.data.remote.GatewayUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远程网关域名。
 *
 * 域名不写死在 APP 里，而是每次启动从远端拉一份纯文本，
 * 服务端换域名时无需重新发版。拉取失败则沿用上次成功的地址（或内置兜底地址）。
 */
class DomainRepository(
    private val settings: SettingsRepository,
    private val client: OkHttpClient
) {

    /**
     * 拉取远程域名并写入本地配置。
     * @return 生效的网关地址；拉取失败时返回 null（调用方保持原样即可）
     */
    suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val remote = runCatching {
            val request = Request.Builder()
                .url(DOMAIN_URL)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            }
        }.getOrNull()

        val normalized = remote?.let { GatewayUrls.normalize(it) }
        if (normalized != null) {
            settings.baseUrl = normalized
        }
        normalized
    }

    private val mutex = Mutex()
    private var loaded = false

    /**
     * 进程内只真正请求一次；后续调用立即返回，不会重复打网络。
     * 首页加载前调用它，可确保首次请求用的是最新域名。
     */
    suspend fun ensure(): String? {
        if (loaded) return null
        mutex.withLock {
            if (loaded) return null
            loaded = true
            return refresh()
        }
    }

    companion object {
        /** 远程网关域名地址（纯文本，一行一个域名，取第一行有效值） */
        const val DOMAIN_URL = "https://www.hez70.com/api/yq_domain.txt"
    }
}
