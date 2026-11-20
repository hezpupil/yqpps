package com.youqu.vod.ui.drama

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.model.DramaEpisode
import com.youqu.vod.direct.ImageLoadGate
import com.youqu.vod.player.ExoPlayerController
import com.youqu.vod.player.PlayerStage
import com.youqu.vod.player.VideoSurface
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.util.numText
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary

private const val UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DramaDetailScreen(
    state: UiState<DramaDetailData>,
    baseUrl: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is UiState.Loading, is UiState.Idle -> {
            Box(modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = AccentOrange)
            }
        }
        is UiState.Error -> ErrorBox(message = state.message, onRetry = onRetry, modifier = modifier.fillMaxSize())
        is UiState.Success -> {
            val data = state.data
            val context = LocalContext.current
            val activity = context as? Activity
            val controller = remember { ExoPlayerController(context, UA, baseUrl) }
            DisposableEffect(controller) { onDispose { runCatching { controller.release() } } }
            // 播放页在前台期间，封面回源让路给 HLS 分片
            DisposableEffect(Unit) {
                ImageLoadGate.beginPlayback()
                onDispose { ImageLoadGate.endPlayback() }
            }

            var selectedEp by remember { mutableStateOf<DramaEpisode?>(null) }
            // 默认播第一集
            LaunchedEffect(data) {
                val first = data.eps?.firstOrNull { !it.src.isNullOrBlank() }
                if (first != null && selectedEp == null) selectedEp = first
            }
            LaunchedEffect(selectedEp) {
                selectedEp?.src?.takeIf { it.isNotBlank() }?.let { src ->
                    runCatching { controller.open(src) }
                }
            }

            // 视频宽高比：>=1 横屏视频，<1 竖屏视频（短剧大多 9:16）
            val ratio = controller.state.videoRatio
            val isPortraitVideo = ratio != null && ratio > 0f && ratio < 1f
            val inlineRatio = when {
                ratio != null && ratio > 0f -> ratio            // 已知真实比例：按真实比例
                else -> 16f / 9f                                 // 未起播：先按横屏占位
            }

            var fullscreen by remember { mutableStateOf(false) }
            var showEpSheet by remember { mutableStateOf(false) }

            fun applyFullscreen(enable: Boolean) {
                fullscreen = enable
                val act = activity ?: return
                // 竖屏视频：全屏不旋转，保持竖屏铺满；横屏视频：转横屏（原行为）
                act.requestedOrientation = when {
                    !enable -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    isPortraitVideo -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                val window = act.window ?: return
                WindowCompat.setDecorFitsSystemWindows(window, !enable)
                val insets = WindowInsetsControllerCompat(window, window.decorView)
                if (enable) {
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                    insets.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    insets.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            DisposableEffect(Unit) {
                onDispose {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            val eps = data.eps.orEmpty()

            fun replay() {
                selectedEp?.src?.let { runCatching { controller.open(it) } }
            }

            val stage: @Composable (Modifier) -> Unit = { m ->
                PlayerStage(
                    controller = controller,
                    surface = { mm -> VideoSurface(controller, mm) },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { applyFullscreen(!fullscreen) },
                    onRetry = ::replay,
                    modifier = m,
                    title = data.drama?.title,
                    onBack = if (fullscreen) onBack else null,
                    fallbackLabel = "选集",
                    onFallback = { showEpSheet = true }
                )
            }

            if (fullscreen) {
                // 全屏：播放器铺满整个屏幕（竖屏视频竖向放大，横屏视频已是横屏画面）
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    stage(Modifier.fillMaxSize())
                }
            } else {
                Column(
                    modifier = modifier.fillMaxSize().background(OledBlack)
                ) {
                    // 顶部返回条
                    Box(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                                tint = Color.White)
                        }
                    }

                    // 播放器：按视频真实比例内嵌（竖屏视频显示竖屏区域）
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(inlineRatio)
                            .background(Color.Black)
                    ) {
                        stage(Modifier.fillMaxSize())
                    }

                    // 标题 + 简介（可滚动，占满剩余空间）
                    Column(
                        modifier = Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(data.drama?.title ?: "短剧", color = TextPrimary, fontSize = 17.sp)
                        val intro = data.drama?.intro
                        if (!intro.isNullOrBlank()) {
                            Text(intro, color = TextSecondary, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        val epText = data.drama?.ep_text
                        if (!epText.isNullOrBlank()) {
                            Text(epText, color = AccentOrange, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }

                    // 底部固定的选集入口栏：点击弹浮层选集
                    Surface(
                        onClick = { showEpSheet = true },
                        color = SurfaceDark,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Apps, contentDescription = null,
                                tint = AccentOrange, modifier = Modifier.size(18.dp))
                            Text(
                                text = "选集" + if (selectedEp?.ep_no != null) {
                                    " · 第${numText(selectedEp?.ep_no)}集"
                                } else {
                                    ""
                                },
                                color = TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 8.dp).weight(1f)
                            )
                            Text("共${eps.size}集", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 选集浮层：从底部弹出，竖屏横屏全屏状态下都可用
            if (showEpSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showEpSheet = false },
                    containerColor = OledBlack
                ) {
                    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Text(
                            "选集（共${eps.size}集）",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        if (eps.isEmpty()) {
                            EmptyBox(text = "暂无剧集", modifier = Modifier.fillMaxWidth().height(160.dp))
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(6),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                        .padding(horizontal = 16.dp)
                                ) {
                                    itemsIndexed(eps) { _, ep ->
                                        val isSelected = selectedEp?.id == ep.id
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isSelected) AccentOrange else SurfaceDark)
                                                .clickable {
                                                    selectedEp = ep
                                                    showEpSheet = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                numText(ep.ep_no).ifEmpty { "-" },
                                                color = if (isSelected) Color.Black else TextPrimary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
