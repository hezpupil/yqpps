package com.youqu.vod.ui.drama

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youqu.vod.core.UiState
import com.youqu.vod.direct.ImageLoadGate
import com.youqu.vod.player.ExoPlayerController
import com.youqu.vod.player.PlayerStage
import com.youqu.vod.player.VideoSurface
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.util.numText
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.TextPrimary
import kotlin.math.abs

private const val DRAMA_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
private const val SWIPE_THRESHOLD = 120f

/**
 * 短剧独立播放页（对标 guoapp 竖屏沉浸播放）：
 * - 竖屏全屏，单一 ExoPlayer，切集只换 URL
 * - 画面区域上下滑动切集（上滑下一集 / 下滑上一集）
 * - 点击画面显隐控制层：顶部返回 + 标题 + 当前集；底部进度/倍速 + 选集
 * - 选集底部浮层；竖屏视频竖向铺满
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DramaPlayerScreen(
    vm: DramaPlayerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val detailState by vm.detail.collectAsStateWithLifecycle()
    val currentIndex by vm.currentIndex.collectAsStateWithLifecycle()
    val playState by vm.playUrl.collectAsStateWithLifecycle()

    val eps = (detailState as? UiState.Success)?.data?.eps.orEmpty()
    val title = (detailState as? UiState.Success)?.data?.drama?.title.orEmpty()

    // 单一 ExoPlayer 实例：切集只换 URL，避免多播放器资源爆炸
    val controller = remember { ExoPlayerController(context, DRAMA_UA, "") }
    DisposableEffect(controller) { onDispose { runCatching { controller.release() } } }
    DisposableEffect(Unit) {
        ImageLoadGate.beginPlayback()
        onDispose { ImageLoadGate.endPlayback() }
    }
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // playUrl 就绪后开播
    LaunchedEffect(playState) {
        if (playState is UiState.Success) {
            runCatching { controller.open((playState as UiState.Success<String>).data) }
        }
    }

    var showEpSheet by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    when (detailState) {
        is UiState.Loading, is UiState.Idle -> {
            Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
        }
        is UiState.Error -> {
            Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((detailState as UiState.Error).message, color = TextPrimary, fontSize = 14.sp)
                    Button(
                        onClick = vm::load,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("重试", color = Color.Black) }
                }
            }
        }
        is UiState.Success -> {
            if (eps.isEmpty()) {
                Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    EmptyBox(text = "暂无可播放剧集")
                }
            } else {
                Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
                    // ① 视频画面铺满（竖屏竖向放大由 ExoPlayer + 容器 fillMaxSize 保证）
                    VideoSurface(controller, Modifier.fillMaxSize())

                    // ② 画面手势层：单击显隐 + 上下滑切集（透明，不遮挡画面）
                    var dragY by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .clickable { controlsVisible = !controlsVisible }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragY = 0f },
                                    onDragEnd = {
                                        if (abs(dragY) > SWIPE_THRESHOLD) {
                                            vm.step(if (dragY < 0) 1 else -1)
                                        }
                                        dragY = 0f
                                    },
                                    onDragCancel = { dragY = 0f }
                                ) { _, amount ->
                                    dragY += amount
                                }
                            }
                    )

                    // ③ 控制层（在最上方，可点击；显隐由 controlsVisible 控制）
                    if (controlsVisible) {
                        // 顶部返回 + 标题
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .align(Alignment.TopCenter)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                                .align(Alignment.TopCenter),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                                    tint = Color.White)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(
                                    "第${numText(eps[currentIndex.coerceIn(eps.indices)].ep_no)}集",
                                    color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp
                                )
                            }
                        }

                        // 底部：复用通用 PlayerStage 的进度/倍速/播放暂停 + 选集入口
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            PlayerStage(
                                controller = controller,
                                surface = { _ -> Box(Modifier.size(0.dp)) },
                                fullscreen = true,
                                onToggleFullscreen = {},
                                onRetry = {
                                    val s = vm.playUrl.value
                                    if (s is UiState.Success) runCatching { controller.open(s.data) }
                                },
                                title = title,
                                onBack = onBack,
                                fallbackLabel = "选集",
                                onFallback = { showEpSheet = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 44.dp)
                            )
                            IconButton(
                                onClick = { showEpSheet = true },
                                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                            ) {
                                Icon(Icons.Default.Apps, contentDescription = "选集", tint = Color.White)
                            }
                        }
                    }

                    // 解析中 / 解析失败提示
                    if (playState is UiState.Loading) {
                        Box(modifier = Modifier.align(Alignment.Center)) {
                            CircularProgressIndicator(color = AccentOrange)
                        }
                    }
                    if (playState is UiState.Error) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text((playState as UiState.Error).message, color = Color.White, fontSize = 13.sp)
                        }
                    }

                    // 选集浮层
                    if (showEpSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showEpSheet = false },
                            containerColor = OledBlack
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                                Text(
                                    "选集（共${eps.size}集）", color = TextPrimary, fontSize = 15.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(6),
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                                    ) {
                                        itemsIndexed(eps) { index, ep ->
                                            val selected = index == currentIndex
                                            Box(
                                                modifier = Modifier.size(46.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (selected) AccentOrange else SurfaceDark)
                                                    .clickable {
                                                        vm.selectEpisode(index)
                                                        showEpSheet = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    numText(ep.ep_no).ifEmpty { "${index + 1}" },
                                                    color = if (selected) Color.Black else TextPrimary,
                                                    fontSize = 13.sp, maxLines = 1,
                                                    overflow = TextOverflow.Clip
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
}
