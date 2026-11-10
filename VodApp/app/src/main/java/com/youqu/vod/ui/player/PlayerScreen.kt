package com.youqu.vod.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.ShowData
import com.youqu.vod.direct.ImageLoadGate
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.player.ExoPlayerController
import com.youqu.vod.player.VideoSurface
import com.youqu.vod.player.PlayerStage
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.SectionTitle
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.components.VideoCard
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.util.anyToInt
import com.youqu.vod.util.formatDuration
import kotlinx.coroutines.delay

/** 心跳上报间隔 */
private const val HEARTBEAT_INTERVAL_MS = 30_000L
/** 部分 CDN 会按 UA 做分发，这里伪装成常见移动端 UA */
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

@Composable
fun PlayerScreen(
    state: UiState<ShowData>,
    videoId: String,
    baseUrl: String,
    onRetry: () -> Unit,
    onHeartbeat: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val controller = remember(videoId) {
        ExoPlayerController(context, USER_AGENT, baseUrl)
    }
    DisposableEffect(controller) {
        onDispose { runCatching { controller.release() } }
    }

    // 播放页在前台期间，让封面回源给 HLS 分片让路（并发收窄 + 起播保护窗口）
    DisposableEffect(Unit) {
        ImageLoadGate.beginPlayback()
        onDispose { ImageLoadGate.endPlayback() }
    }

    // 播放地址（1.0.12 起与网页版完全同源）：优先用 show 返回的 src——CDN 直连、自带一次性
    // 签名（形如 https://mail.xxcjpt.com/...?java=...），不再经过网关 m3u8.php/stream.php
    // 这道会在服务端取上游失败时回 502 的代理。src 为空的老网关回退到 m3u8.php?vid=
    // （其分片 stream.php?u= 为相对地址，ExoPlayer 会按播放列表地址自动解析）。
    var lastSrc by remember(videoId) { mutableStateOf<String?>(null) }
    val src = (state as? UiState.Success)?.data?.video?.src?.takeIf { it.isNotBlank() }
    LaunchedEffect(src) { if (src != null) lastSrc = src }
    val playUrl = lastSrc ?: GatewayUrls.play(baseUrl, videoId)

    LaunchedEffect(playUrl) {
        runCatching { controller.open(playUrl) }
            .onFailure { controller.state.markError("起播异常：${it.message ?: it.javaClass.simpleName}") }
    }

    // 错误诊断：展示当前播放源主机（1.0.12 起与网页版同源直连，不再有本地代理的上游码可看）。
    LaunchedEffect(controller.state.error) {
        if (controller.state.error == null) {
            controller.state.diagnostic = null
            return@LaunchedEffect
        }
        val host = runCatching { java.net.URI(playUrl).host }.getOrNull() ?: playUrl
        controller.state.diagnostic = "播放源：$host（与网页版同源直连）"
    }

    val playcode = (state as? UiState.Success)?.data?.playcode
    LaunchedEffect(playcode) {
        if (playcode.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            onHeartbeat(playcode)
        }
    }

    // 试看检测：实际时长明显短于详情标注时长，多半是登录态失效下发的试看流
    var previewWarning by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(state, videoId) {
        val declared = anyToInt((state as? UiState.Success)?.data?.video?.duration)
        if (declared <= 120) return@LaunchedEffect
        delay(5_000L)
        val realMs = controller.durationMs()
        if (realMs > 0 && realMs < declared * 600L) {
            previewWarning = true
        }
    }

    val title = (state as? UiState.Success)?.data?.video?.title ?: "加载中…"
    var fullscreen by remember { mutableStateOf(false) }

    fun applyFullscreen(enable: Boolean) {
        fullscreen = enable
        val act = activity ?: return
        act.requestedOrientation = if (enable) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val window = act.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, !enable)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun replay() {
        // src 带一次性签名（?java= 时间戳 token）会过期：重播时重新拉一次 show 取新地址。
        // 加载成功后 lastSrc 变化，上面的 LaunchedEffect(playUrl) 会自动重新起播。
        onRetry()
    }

    val stage: @Composable (Modifier) -> Unit = { m ->
        PlayerStage(
            controller = controller,
            surface = { surfaceModifier -> VideoSurface(controller, surfaceModifier) },
            fullscreen = fullscreen,
            title = title,
            onBack = if (fullscreen) onBack else null,
            onToggleFullscreen = { applyFullscreen(!fullscreen) },
            onRetry = ::replay,
            modifier = m
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        if (!fullscreen) {
            SimpleTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = "首页", tint = TextPrimary)
                    }
                }
            )
        }

        if (fullscreen) {
            stage(Modifier.fillMaxSize())
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                stage(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }

            when (state) {
                is UiState.Loading, is UiState.Idle -> item {
                    Box(modifier = Modifier.height(120.dp)) {
                        EmptyBox(text = "加载中…")
                    }
                }

                is UiState.Error -> item {
                    ErrorBox(
                        message = state.message,
                        onRetry = onRetry,
                        modifier = Modifier.height(220.dp)
                    )
                }

                is UiState.Success -> {
                    val data = state.data
                    item {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(text = data.video?.title ?: "未命名", color = TextPrimary, fontSize = 16.sp)
                            Text(
                                text = "时长 ${formatDuration(anyToInt(data.video?.duration))}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )

                            if (previewWarning) {
                                Surface(
                                    color = SurfaceDark2,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    Text(
                                        text = "检测到本片为试看片段，登录状态可能已失效；" +
                                            "请在服务端刷新登录（refresh.php）后重新播放。",
                                        color = AccentOrange,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (data.guess.isNullOrEmpty()) {
                        item { EmptyBox(text = "暂无猜你喜欢", modifier = Modifier.height(120.dp)) }
                    } else {
                        item { SectionTitle(text = "猜你喜欢") }
                        // 一行两个、往下排列：按 2 个一组切分，仍走 Lazy 列表，封面只加载可见项
                        items(data.guess.chunked(2)) { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowItems.forEach { item ->
                                    VideoCard(
                                        item = item,
                                        coverUrl = GatewayUrls.image(baseUrl, item.image.orEmpty()),
                                        onClick = { onVideoClick(item) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            item { Box(modifier = Modifier.height(16.dp)) }
        }
    }
}
