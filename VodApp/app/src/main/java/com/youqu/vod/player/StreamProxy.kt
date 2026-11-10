package com.youqu.vod.player

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地取流代理（127.0.0.1）：播放器只连本机，真实请求由系统 HttpURLConnection 发出。
 *
 * 为什么要这一层：
 * 1. 播放列表里的分片地址带一次性 token（`java=<时间戳>-...`），播放器稍晚取分片时
 *    token 可能已失效，上游直接返回 4xx → 播放器侧就是 ERROR_CODE_IO_BAD_HTTP_STATUS。
 *    代理遇到 4xx 会**重新拉一次播放列表按行号取新地址**再请求（播放器做不到这件事）。
 * 2. 请求头完全可控：统一带 UA 与 Referer，避免上游按 UA/Referer 拒绝。
 * 3. 失败自动重试（5xx 与网络错误最多 3 次），弱网直接扛过去。
 *
 * 地址形式：播放列表里的每条地址都被改写成 `/p?v=<vid>&i=<行号>`。
 * 用行号而不是把原始长地址塞进 query，请求更短，也避免百分号编码在链路中被二次处理。
 */
class StreamProxy(
    gatewayBase: String,
    private val userAgent: String
) {

    /** 网关目录地址，统一补上结尾斜杠，避免拼出 https://x.comm3u8.php 这种坏地址 */
    private val gateway: String = gatewayBase.trim().let {
        if (it.isEmpty()) it else if (it.endsWith("/")) it else "$it/"
    }

    /** 网关地址是否可用：地址非法时代理不启用，播放页退回直连 */
    val gatewayValid: Boolean
        get() = gateway.startsWith("http://") || gateway.startsWith("https://")

    // 注意：这里用系统 HttpURLConnection 而非 OkHttp。
    // 实测同一设备/同一 m3u8.php：ExoPlayer 直连（系统 HTTP 栈）能到达应用层（4xx），
    // 而 OkHttp 默认 TLS 指纹被前置 Cloudflare 在到达应用层之前挡成 502。
    // HttpURLConnection 使用平台 SSL（与 ExoPlayer 直连完全相同的栈），不再被拦。

    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    /** vid -> 播放列表的原始行（按行号索引取地址用） */
    private val playlists = ConcurrentHashMap<String, List<String>>()
    @Volatile
    private var lastVid: String? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    /**
     * 最近一次上游（网关）返回的非成功状态码与阶段，供播放页错误浮层展示，
     * 帮助定位到底是「播放列表拿不到」还是「某个分片/KEY 拿不到」、以及具体 HTTP 码。
     */
    @Volatile
    var lastUpstream: String? = null
        private set

    fun lastUpstreamInfo(): String? = lastUpstream

    /** 播放器实际使用的地址 */
    fun playlistUrl(vid: String): String =
        "http://127.0.0.1:$port/m3u8?v=" + URLEncoder.encode(vid, "UTF-8")

    /** 启动监听。返回 false 表示端口都不可用，调用方应退回直连地址。 */
    fun start(): Boolean {
        if (!gatewayValid) return false
        if (running.getAndSet(true)) return serverSocket != null
        var ss: ServerSocket? = null
        for (p in PORT_CANDIDATES) {
            ss = try {
                ServerSocket(p, 64, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                null
            }
            if (ss != null) break
        }
        if (ss == null) {
            running.set(false)
            return false
        }
        serverSocket = ss
        runCatching { pool.submit { acceptLoop(ss) } }
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        playlists.clear()
        lastVid = null
    }

    /** 彻底退出：连同线程池一起收掉，避免多次进出播放页堆积线程 */
    fun shutdown() {
        stop()
        runCatching { pool.shutdownNow() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            runCatching { pool.submit { serveSocket(socket) } }
        }
    }

    private fun serveSocket(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (running.get()) {
                val req = readRequest(reader) ?: break
                handle(req, out)
                out.flush()
                if (!req.keepAlive) break
            }
        } catch (e: Exception) {
            // 客户端断开等都属于正常结束
        } finally {
            runCatching { socket.close() }
        }
    }

    private class Req(val path: String, val query: Map<String, String>, val range: String?, val keepAlive: Boolean)

    private fun readRequest(reader: BufferedReader): Req? {
        val line = reader.readLine() ?: return null
        if (line.isBlank()) return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val target = parts[1]
        val qIndex = target.indexOf('?')
        val path = if (qIndex >= 0) target.substring(0, qIndex) else target
        val query = parseQuery(if (qIndex >= 0) target.substring(qIndex + 1) else "")

        var range: String? = null
        var keepAlive = false
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            val lower = h.lowercase(Locale.US)
            if (lower.startsWith("range:")) range = h.substringAfter(':').trim()
            if (lower.startsWith("connection:")) keepAlive = lower.contains("keep-alive")
        }
        return Req(path, query, range, keepAlive)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                map[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
        }
        return map
    }

    private fun handle(req: Req, out: OutputStream) {
        when (req.path) {
            "/m3u8" -> {
                val vid = req.query["v"].orEmpty().ifBlank { req.query["vid"].orEmpty() }
                if (vid.isBlank()) {
                    writeSimple(out, 400, "missing vid")
                    return
                }
                val lines = loadPlaylist(vid, force = true) ?: run {
                    writeSimple(out, 502, "playlist unavailable")
                    return
                }
                lastVid = vid
                val bytes = rewrite(lines, vid).toByteArray(Charsets.UTF_8)
                writeHead(
                    out, 200, "OK", mapOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to bytes.size.toString(),
                        "Cache-Control" to "no-store",
                        "Connection" to "keep-alive"
                    )
                )
                out.write(bytes)
            }

            "/p" -> {
                val vid = req.query["v"].orEmpty().ifBlank { lastVid.orEmpty() }
                val index = req.query["i"]?.toIntOrNull()
                if (vid.isBlank() || index == null) {
                    writeSimple(out, 400, "missing v or i")
                    return
                }
                stream(out, vid, index, req.range)
            }

            else -> writeSimple(out, 404, "not found")
        }
    }

    /** 播放列表：优先用缓存；force 或拿不到时重新拉一份 */
    private fun loadPlaylist(vid: String, force: Boolean): List<String>? {
        if (!force) playlists[vid]?.let { return it }
        val text = fetchText(gateway + "m3u8.php?vid=" + URLEncoder.encode(vid, "UTF-8"), "playlist") ?: return null
        val lines = text.split("\n").map { it.trim() }
        playlists[vid] = lines
        return lines
    }

    /** 把播放列表里的地址改写成指向本代理的短地址（只带 vid 与行号） */
    private fun rewrite(lines: List<String>, vid: String): String {
        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri == null) {
                        sb.append(line).append('\n')
                    } else {
                        sb.append(line.replace(uri, localUrl(vid, index))).append('\n')
                    }
                }

                line.startsWith("#") || line.isEmpty() -> sb.append(line).append('\n')

                else -> sb.append(localUrl(vid, index)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun localUrl(vid: String, index: Int): String =
        "http://127.0.0.1:$port/p?v=" + URLEncoder.encode(vid, "UTF-8") + "&i=" + index

    /** 按行号取原始地址；拿不到就重新拉一次播放列表 */
    private fun resolve(vid: String, index: Int, force: Boolean = false): String? {
        val lines = loadPlaylist(vid, force) ?: return null
        val line = lines.getOrNull(index) ?: return null
        val raw = if (line.startsWith("#EXT-X-KEY")) {
            Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
        } else {
            line
        }
        if (raw.isNullOrBlank() || raw.startsWith("#")) return null
        return if (raw.startsWith("http")) raw else gateway.trimEnd('/') + "/" + raw
    }

    /** 取一个分片/KEY；4xx 时重新拉播放列表按行号换地址再试一次（token 刷新的关键一步） */
    private fun stream(out: OutputStream, vid: String, index: Int, range: String?) {
        val stage = "segment#$index"
        var url = resolve(vid, index)
        var result = url?.let { fetch(it, range, stage) }

        if (result == null) {
            // 换一份刚拉下来的播放列表再取一次地址
            val refreshed = resolve(vid, index, force = true)
            if (refreshed != null && refreshed != url) {
                result = fetch(refreshed, range, "$stage(refresh)")
            }
        }
        if (result == null) {
            writeSimple(out, 502, "upstream unavailable")
            return
        }
        writeHead(
            out, result.code, if (result.code == 200) "OK" else "Partial Content", buildMap {
                put("Content-Type", result.contentType ?: "application/octet-stream")
                put("Accept-Ranges", "bytes")
                result.contentLength?.let { put("Content-Length", it.toString()) }
                result.contentRange?.let { put("Content-Range", it) }
                put("Connection", "keep-alive")
            }
        )
        result.stream.use { it.copyTo(out) }
    }

    private class Fetched(
        val code: Int,
        val contentType: String?,
        val contentLength: Long?,
        val contentRange: String?,
        val stream: InputStream
    )

    private fun fetch(url: String, range: String?, stage: String): Fetched? {
        repeat(MAX_RETRY) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    return null
                }
            }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", gateway)
                    setRequestProperty("Accept", "*/*")
                    if (range != null) setRequestProperty("Range", range)
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream ?: run { conn.disconnect(); return@repeat }
                    return Fetched(
                        code = code,
                        contentType = conn.getHeaderField("Content-Type"),
                        contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
                            ?: conn.contentLength.toLong().takeIf { it >= 0 },
                        contentRange = conn.getHeaderField("Content-Range"),
                        stream = body
                    )
                }
                // 非成功：抓错误体一并记录，再判断
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                }.getOrDefault("")
                conn.disconnect()
                lastUpstream = "$stage → HTTP $code" + if (errBody.isNotBlank()) {
                    " | ${errBody.replace("\n", " ").take(160)}"
                } else ""
                // 4xx 交给上层刷新 token；5xx 与网络错误继续重试
                if (code >= 500) return@repeat
                return null
            } catch (e: IOException) {
                lastUpstream = "$stage → 网络异常：${e.message?.take(60) ?: e.javaClass.simpleName}"
                return@repeat
            } catch (e: IllegalArgumentException) {
                // 地址本身不合法（网关为空等），重试没有意义
                lastUpstream = "$stage → 地址非法"
                return null
            } catch (e: Exception) {
                lastUpstream = "$stage → 异常：${e.message?.take(60) ?: e.javaClass.simpleName}"
                return@repeat
            }
        }
        return null
    }

    private fun fetchText(url: String, stage: String): String? {
        repeat(MAX_RETRY) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    return null
                }
            }
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", gateway)
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                // 非成功：抓错误体（可能是 Cloudflare 的 502 页、也可能是网关自己的提示），
                // 一并写进诊断，便于区分「Cloudflare 拦的」还是「网关上游挂了」。
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                }.getOrDefault("")
                conn.disconnect()
                lastUpstream = "$stage → HTTP $code" + if (errBody.isNotBlank()) {
                    " | ${errBody.replace("\n", " ").take(160)}"
                } else ""
                if (code >= 500) return@repeat
                return null
            } catch (e: IOException) {
                lastUpstream = "$stage → 网络异常：${e.message?.take(60) ?: e.javaClass.simpleName}"
                return@repeat
            } catch (e: IllegalArgumentException) {
                lastUpstream = "$stage → 地址非法"
                return null
            } catch (e: Exception) {
                lastUpstream = "$stage → 异常：${e.message?.take(60) ?: e.javaClass.simpleName}"
                return@repeat
            }
        }
        return null
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeSimple(out: OutputStream, code: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        writeHead(
            out, code, message, mapOf(
                "Content-Type" to "text/plain",
                "Content-Length" to bytes.size.toString(),
                "Connection" to "close"
            )
        )
        out.write(bytes)
    }

    private companion object {
        val PORT_CANDIDATES = intArrayOf(18925, 18926, 18927, 18928, 18929)
        const val MAX_RETRY = 3
        const val RETRY_DELAY_MS = 400L
    }
}
