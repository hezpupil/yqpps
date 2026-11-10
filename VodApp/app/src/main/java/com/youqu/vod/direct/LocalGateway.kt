package com.youqu.vod.direct

import android.util.Log
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APP 内置网关：把 PHP 的 api.php / image.php / m3u8.php / stream.php 四个端点
 * 原样搬进客户端，让现有 Retrofit + VodRepository + 所有页面零改动即可直连上游。
 *
 * 路由：
 *   /api?action=...   → 直连上游业务接口，返回与原 api.php 完全一致的 ApiEnvelope JSON
 *   /img?u=...        → 图片回源 + AES-128-ECB 解密，返回真实图片字节
 *   /m3u8?vid=...     → 取最新签名 src + 拉播放列表 + 改写 KEY/分片到 /seg
 *   /seg?u=...        → 带 abab UA / device Cookie 代取 KEY 或分片，透传 Range 与 206
 *   /ping             → 健康检查
 *
 * 账号密码不在这里：token 由服务器 token.php 下发（见 TokenProvider）。
 */
class LocalGateway(
    private val tokens: TokenProvider,
    private val upstream: UpstreamClient
) {
    @Volatile
    var localBase: String = ""
        private set

    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    /** 启动，返回形如 http://127.0.0.1:19025 的根地址；失败返回 null */
    fun start(): String? {
        if (running.getAndSet(true)) {
            return localBase.takeIf { it.isNotEmpty() }
        }
        var ss: ServerSocket? = null
        for (p in DirectConfig.PORTS) {
            ss = try {
                ServerSocket(p, 64, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                null
            }
            if (ss != null) break
        }
        if (ss == null) {
            running.set(false)
            Log.w(TAG, "本地网关端口均不可用")
            return null
        }
        serverSocket = ss
        val base = "http://127.0.0.1:${ss.localPort}"
        localBase = base
        pool.submit { acceptLoop(ss) }
        Log.i(TAG, "本地网关已启动：$base")
        return base
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        localBase = ""
    }

    fun shutdown() {
        stop()
        runCatching { pool.shutdownNow() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            pool.submit { serveSocket(socket) }
        }
    }

    private class Req(
        val path: String,
        val query: Map<String, String>,
        val range: String?,
        val keepAlive: Boolean
    )

    private fun serveSocket(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (running.get()) {
                val req = readRequest(reader) ?: break
                var keepAlive = req.keepAlive
                try {
                    keepAlive = handle(req, out)
                } catch (e: Exception) {
                    Log.w(TAG, "handle ${req.path} 失败：${e.message}")
                    runCatching { writeSimple(out, 502, "local gateway error: ${e.message}") }
                    keepAlive = false
                }
                out.flush()
                if (!keepAlive) break
            }
        } catch (e: Exception) {
            // 客户端主动断开属正常结束
        } finally {
            runCatching { socket.close() }
        }
    }

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
        // HTTP/1.1 默认持久连接，只有显式 Connection: close 才关闭
        var keepAlive = true
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            val lower = h.lowercase(Locale.US)
            if (lower.startsWith("range:")) range = h.substringAfter(':').trim()
            if (lower.startsWith("connection:")) keepAlive = !lower.contains("close")
        }
        return Req(path, query, range, keepAlive)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        raw.split('&').forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) {
                runCatching {
                    map[URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                }
            }
        }
        return map
    }

    /** @return 是否保持连接（分片流式转发一律关闭，避免无 Content-Length 时播放器死等） */
    private fun handle(req: Req, out: OutputStream): Boolean {
        return when (req.path) {
            "/ping" -> {
                writeJson(out, 200, JSONObject().put("ok", true).put("service", "local-gateway"))
                req.keepAlive
            }
            "/api" -> { handleApi(req, out); req.keepAlive }
            "/img" -> { handleImage(req, out); req.keepAlive }
            "/m3u8" -> { handleM3u8(req, out); req.keepAlive }
            "/seg" -> { handleSeg(req, out); false }
            else -> { writeSimple(out, 404, "not found"); false }
        }
    }

    /* ---------------- /api：与原 api.php 输出结构一致 ---------------- */

    private fun handleApi(req: Req, out: OutputStream) {
        val action = req.query["action"].orEmpty()
        val result: JSONObject = try {
            when (action) {
                "index" -> envelope(upstream.get("/java/index/index"))
                "recent" -> envelope(upstream.get("/java/index/recent"))
                "hot" -> envelope(upstream.get("/java/index/hot"))

                "search" -> {
                    val kw = req.query["kw"].orEmpty()
                    val page = (req.query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val order = req.query["order"]?.toIntOrNull() ?: 9
                    val t = System.currentTimeMillis().toString()
                    envelope(
                        upstream.postMultipart(
                            path = "/java/so/title",
                            fields = linkedMapOf(
                                "order" to order.toString(),
                                "page" to page.toString(),
                                "kw" to kw,
                                "spm" to "home.hot"
                            ),
                            query = mapOf("page" to page.toString(), "order" to order.toString(), "t" to t)
                        )
                    ).also { injectPage(it, page) }
                }

                "show" -> {
                    val vid = req.query["vid"].orEmpty()
                    val env = envelope(
                        upstream.postMultipart(
                            path = "/java/show/$vid",
                            fields = linkedMapOf("vid" to vid, "spm" to "show.guess"),
                            needAuth = true
                        )
                    )
                    // 关键：把带签名的 src 改写到本地 /m3u8，让播放器走本地代取
                    env.optJSONObject("data")?.optJSONObject("video")?.let { video ->
                        video.put("src", "${localBase}/m3u8?vid=" + java.net.URLEncoder.encode(vid, "UTF-8"))
                    }
                    env
                }

                "playtime" -> {
                    val playcode = req.query["playcode"].orEmpty()
                    val seconds = req.query["seconds"] ?: "3"
                    envelope(
                        upstream.postMultipart(
                            path = "/java/vod/playtime",
                            fields = linkedMapOf("playcode" to playcode, "seconds" to seconds),
                            query = mapOf("c" to playcode)
                        )
                    )
                }

                "device_active" -> envelope(
                    upstream.postMultipart(path = "/java/device/active", fields = emptyMap(), needAuth = true)
                )

                "login", "refresh" -> {
                    tokens.get(forceRefresh = true)
                    JSONObject().put("ok", true).put("code", 1).put("message", "已刷新凭证").put("data", JSONObject())
                }

                "status" -> {
                    val c = tokens.get()
                    JSONObject().put("ok", true).put("code", 1).put("data", JSONObject()
                        .put("device", c.device)
                        .put("base_url", c.baseUrl)
                        .put("has_token", true))
                }

                else -> JSONObject().put("ok", false).put("error", "未知 action: $action")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        writeJson(out, 200, result)
    }

    /** 把解码后的上游响应转成 api.php 的 {"ok","code","message","data"} 结构 */
    private fun envelope(r: UpstreamClient.UpstreamResult): JSONObject {
        val out = JSONObject().put("ok", r.success)
        if (!r.success) {
            out.put("error", "上游返回 code=${r.code} ${r.message}")
        }
        out.put("code", r.code)
        out.put("message", r.message)
        val inner = r.data ?: JSONObject()
        out.put("data", inner)
        return out
    }

    /** 上游若不回 page/pages，用请求值兜底，避免分页逻辑拿到 0 */
    private fun injectPage(env: JSONObject, page: Int) {
        val d = env.optJSONObject("data") ?: return
        if (d.optInt("page", 0) <= 0) d.put("page", page)
        if (d.optInt("pages", 0) <= 0) d.put("pages", 1)
    }

    /* ---------------- /img：图片解密 ---------------- */

    private fun handleImage(req: Req, out: OutputStream) {
        val raw = req.query["u"].orEmpty()
        val url = b64urlDecode(raw)
        if (url.isNullOrBlank()) {
            writeSimple(out, 400, "missing u")
            return
        }
        val host = hostOf(url)
        if (host != DirectConfig.IMAGE_HOST || !url.startsWith("https://")) {
            writeSimple(out, 403, "host not allowed")
            return
        }

        // 命中内存缓存就直接回包，不产生任何上游流量（列表来回滚动时效果最明显）
        ImageByteCache.get(raw)?.let { writeImage(out, it); return }

        // 懒加载回源闸门：限制并发、播放起播窗口内让路给 HLS 分片
        val fetched: ImageDecryptor.Image? = ImageLoadGate.withSlot {
            try {
                upstream.fetchImage(url).use { r ->
                    if (!r.isSuccessful) {
                        Log.w(TAG, "image upstream HTTP ${r.code}")
                        null
                    } else {
                        r.body?.bytes()?.let { ImageDecryptor.decrypt(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "image fetch failed：${e.message}")
                null
            }
        }
        if (fetched == null) {
            writeSimple(out, 502, "image fetch failed")
        } else {
            ImageByteCache.put(raw, fetched)
            writeImage(out, fetched)
        }
    }

    private fun writeImage(out: OutputStream, image: ImageDecryptor.Image) {
        writeHead(
            out, 200, "OK", mapOf(
                "Content-Type" to image.mime,
                "Content-Length" to image.bytes.size.toString(),
                "Cache-Control" to "private, max-age=604800, immutable",
                "X-Content-Type-Options" to "nosniff",
                "Connection" to "keep-alive"
            )
        )
        out.write(image.bytes)
    }

    /* ---------------- /m3u8：播放列表改写 ---------------- */

    private fun handleM3u8(req: Req, out: OutputStream) {
        val vid = req.query["vid"].orEmpty().ifBlank { req.query["v"].orEmpty() }
        if (vid.isBlank()) {
            writeSimple(out, 400, "missing vid")
            return
        }
        // 每次播放重新取 show：src 带一次性签名，会过期
        val src = try {
            val r = upstream.postMultipart(
                path = "/java/show/$vid",
                fields = linkedMapOf("vid" to vid, "spm" to "show.guess"),
                needAuth = true
            )
            val d = r.data ?: r.json
            d.optJSONObject("video")?.optString("src").orEmpty()
                .ifBlank { d.optString("src") }
                .ifBlank { r.json.optJSONObject("data")?.optJSONObject("video")?.optString("src").orEmpty() }
        } catch (e: Exception) {
            ""
        }
        if (src.isBlank()) {
            writeSimple(out, 502, "no src in show response")
            return
        }
        val resp = try {
            upstream.fetchRaw(src)
        } catch (e: Exception) {
            writeSimple(out, 502, "playlist fetch failed")
            return
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                writeSimple(out, 502, "playlist upstream HTTP ${r.code}")
                return
            }
            val text = r.body?.string().orEmpty()
            if (!text.trimStart().startsWith("#EXTM3U")) {
                writeSimple(out, 502, "invalid playlist")
                return
            }
            val rewritten = M3u8Rewriter.rewrite(text, src, localBase)
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            writeHead(
                out, 200, "OK", mapOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to bytes.size.toString(),
                    "Cache-Control" to "no-store",
                    "Access-Control-Allow-Origin" to "*",
                    "Connection" to "keep-alive"
                )
            )
            out.write(bytes)
        }
    }

    /* ---------------- /seg：KEY / 分片代取 ---------------- */

    private fun handleSeg(req: Req, out: OutputStream) {
        val raw = req.query["u"].orEmpty()
        val url = b64urlDecode(raw)
        if (url.isNullOrBlank()) {
            writeSimple(out, 400, "missing u")
            return
        }
        val host = hostOf(url)
        val allowed = host in DirectConfig.SEGMENT_HOSTS ||
            host.endsWith(".100njp.com") || host.endsWith(".xxcjpt.com")
        if (!allowed || !url.startsWith("https://")) {
            writeSimple(out, 403, "host not allowed")
            return
        }
        val extra = req.range?.let { mapOf("Range" to it) } ?: emptyMap()
        val resp: Response = try {
            upstream.fetchRaw(url, extra)
        } catch (e: Exception) {
            writeSimple(out, 502, "segment fetch failed")
            return
        }
        resp.use { r ->
            val body = r.body ?: run {
                writeSimple(out, 502, "empty segment body")
                return
            }
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = r.header("Content-Type") ?: "application/octet-stream"
            headers["Accept-Ranges"] = "bytes"
            r.header("Content-Range")?.let { headers["Content-Range"] = it }
            r.header("Content-Length")?.let { headers["Content-Length"] = it }
            // 上游可能不返回 Content-Length（chunked）；此时必须显式 close，
            // 否则播放器会一直等下一个响应，表现为起播后卡住。
            headers["Connection"] = "close"
            val reason = if (r.code == 206) "Partial Content" else "OK"
            writeHead(out, r.code, reason, headers)
            BufferedInputStream(body.byteStream(), 64 * 1024).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            }
        }
    }

    /* ---------------- HTTP 输出工具 ---------------- */

    private fun writeJson(out: OutputStream, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        writeHead(
            out, code, "OK", mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "keep-alive"
            )
        )
        out.write(bytes)
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
        // reason phrase 只能是 ASCII，否则 ISO_8859_1 编码会写出非法字节
        val reason = if (code == 200) "OK" else "Error"
        writeHead(
            out, code, reason, mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Connection" to "close"
            )
        )
        out.write(bytes)
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI.create(url).host?.lowercase(Locale.US).orEmpty()
    }.getOrDefault("")

    private companion object {
        const val TAG = "LocalGateway"
    }
}
