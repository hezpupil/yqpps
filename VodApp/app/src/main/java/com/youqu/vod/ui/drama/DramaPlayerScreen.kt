package com.youqu.vod.ui.drama

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Brush
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
import com.youqu.vod.player.VideoSurface
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.util.formatDuration
import com.youqu.vod.util.numText
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.TextPrimary
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val DRAMA_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
private const val SWIPE_THRESHOLD = 120f
/** 判定拖动主轴的位移阈值（dp 换算前的像素量级） */
private const val DRAG_AXIS_SLOP = 24f
/** 水平拖满一屏对应的快进秒数 */
private const val SEEK_SECONDS_PER_SCREEN = 120f
/** 单次拖动最多快进/快退秒数 */
private const val SEEK_MAX_SECONDS = 90f
private val SPEED_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

/** 水平拖动距离换算成毫秒偏移，拖得越远越快，但封顶避免误操作跳太远 */
private fun seekDeltaMs(dragX: Float, widthPx: Float): Long {
    if (widthPx <= 0f) return 0L
    val seconds = (dragX / widthPx) * SEEK_SECONDS_PER_SCREEN
    return (seconds.coerceIn(-SEEK_MAX_SECONDS, SEEK_MAX_SECONDS) * 1000).toLong()
}

/**
 * 短剧独立播放页（对标 guoapp 竖屏沉浸播放）：
 * - 竖屏全屏，单一 ExoPlayer，切集只换 URL
 * - 画面区域上下滑动切集（上滑下一集 / 下滑上一集）
 * - 点击画面显隐控制层：顶部返回 + 标题 + 当前集；底部进度/倍速 + 选集
 * - 控制条只在上下两端用渐变遮罩，中间画面完全露出（不再复用通用 PlayerStage，避免黑层遮挡）
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

    // 进度轮询（PlayerState 的 setter 为 internal，同 module 可见）
    LaunchedEffect(controller) {
        while (true) {
            controller.state.positionMs = controller.currentPositionMs()
            controller.state.durationMs = controller.durationMs()
            delay(500)
        }
    }

    // playUrl 就绪后开播
    LaunchedEffect(playState) {
        if (playState is UiState.Success) {
            runCatching { controller.open((playState as UiState.Success<String>).data) }
        }
    }

    var showEpSheet by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // 播放中且长时间无操作，自动隐藏控制条
    LaunchedEffect(controlsVisible, controller.state.playing, controller.state.buffering) {
        if (controlsVisible && controller.state.playing && !controller.state.buffering) {
            delay(4_000)
            controlsVisible = false
        }
    }

    when (detailState) {
        is UiState.Loading, is UiState.Idle -> {
            Box(modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
        }
        is UiState.Error -> {
            Box(modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
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
                Box(modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
                    EmptyBox(text = "暂无可播放剧集")
                }
            } else {
                BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
                    // 手势与倍速共享状态
                    var speed by remember(controller) { mutableStateOf(1f) }
                    var boosting by remember { mutableStateOf(false) }
                    var speedBeforeBoost by remember { mutableStateOf(1f) }
                    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
                    var dragX by remember { mutableStateOf(0f) }
                    var dragY by remember { mutableStateOf(0f) }
                    var dragAxis by remember { mutableStateOf(0) } // 0=未定 1=水平快进 2=垂直切集
                    val widthPx = constraints.maxWidth.toFloat()

                    /*
                     * 按视频真实尺寸显示，不再一律拉成细长全屏：
                     * 取 ExoPlayer 回调的真实宽高比（含像素宽高比校正），等比缩放到容器内（fit），
                     * 剩余区域留黑边。横屏剧不会变形，竖屏剧也不会被拉伸。
                     */
                    val ratio = controller.state.videoRatio
                    val containerRatio =
                        if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 0f
                    val surfaceModifier = when {
                        ratio == null || ratio <= 0f || containerRatio <= 0f ->
                            Modifier.fillMaxSize()
                        ratio > containerRatio ->
                            Modifier.fillMaxWidth().aspectRatio(ratio)
                        else ->
                            Modifier.fillMaxHeight()
                                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    }

                    // ① 视频画面：等比居中，未知比例时才铺满
                    VideoSurface(controller, surfaceModifier.align(Alignment.Center))

                    // ② 手势层：单击显隐 / 长按 2 倍速 / 左右滑动快进 / 上下滑动切集
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragX = 0f
                                        dragY = 0f
                                        dragAxis = 0
                                    },
                                    onDragCancel = {
                                        dragX = 0f
                                        dragY = 0f
                                        dragAxis = 0
                                        seekPreviewMs = null
                                    },
                                    onDragEnd = {
                                        when (dragAxis) {
                                            1 -> {
                                                val delta = seekDeltaMs(dragX, widthPx)
                                                if (abs(delta) > 500L) {
                                                    val duration = controller.durationMs()
                                                    val base = controller.currentPositionMs()
                                                    val target = (base + delta).coerceIn(
                                                        0L,
                                                        if (duration > 0) duration else Long.MAX_VALUE
                                                    )
                                                    controller.seekTo(target)
                                                }
                                            }
                                            2 -> {
                                                if (abs(dragY) > SWIPE_THRESHOLD) {
                                                    vm.step(if (dragY < 0) 1 else -1)
                                                }
                                            }
                                        }
                                        dragX = 0f
                                        dragY = 0f
                                        dragAxis = 0
                                        seekPreviewMs = null
                                    }
                                ) { change, amount ->
                                    dragX += amount.x
                                    dragY += amount.y
                                    if (dragAxis == 0) {
                                        dragAxis = when {
                                            abs(dragX) > DRAG_AXIS_SLOP && abs(dragX) > abs(dragY) -> 1
                                            abs(dragY) > DRAG_AXIS_SLOP -> 2
                                            else -> 0
                                        }
                                    }
                                    if (dragAxis == 1) {
                                        val delta = seekDeltaMs(dragX, widthPx)
                                        val duration = controller.durationMs()
                                        val base = controller.currentPositionMs()
                                        seekPreviewMs = (base + delta).coerceIn(
                                            0L,
                                            if (duration > 0) duration else Long.MAX_VALUE
                                        )
                                    }
                                    change.consume()
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { controlsVisible = !controlsVisible },
                                    onLongPress = {
                                        if (!boosting) {
                                            boosting = true
                                            speedBeforeBoost = speed
                                            controller.setSpeed(2f)
                                        }
                                    },
                                    onPress = {
                                        tryAwaitRelease()
                                        if (boosting) {
                                            boosting = false
                                            controller.setSpeed(speedBeforeBoost)
                                        }
                                    }
                                )
                            }
                    )

                    // ③ 控制层：只在上下两端渐变遮罩，中间不覆盖视频
                    if (controlsVisible) {
                        // 顶部渐变 + 返回/标题/集数
                        Box(
                            modifier = Modifier.fillMaxWidth().height(110.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                                    )
                                )
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

                        // 底部渐变 + 进度/播放/倍速/选集
                        val duration = controller.state.durationMs
                        val position = controller.state.positionMs
                        val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                    )
                                )
                                .navigationBarsPadding()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Slider(
                                value = progress,
                                onValueChange = { value ->
                                    if (duration > 0) controller.seekTo((value * duration).toLong())
                                },
                                enabled = duration > 0,
                                modifier = Modifier.height(20.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentOrange,
                                    activeTrackColor = AccentOrange,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                                )
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { controller.toggle() }, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        imageVector = if (controller.state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (controller.state.playing) "暂停" else "播放",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    text = "${formatDuration((position / 1000).toInt())} / ${formatDuration((duration / 1000).toInt())}",
                                    color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                Text(
                                    text = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x",
                                    color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 12.dp).clickable {
                                        val idx = SPEED_STEPS.indexOf(speed)
                                        val next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.size]
                                        speed = next
                                        controller.setSpeed(next)
                                    }
                                )
                                Box(modifier = Modifier.weight(1f))
                                IconButton(onClick = { showEpSheet = true }) {
                                    Icon(Icons.Default.Apps, contentDescription = "选集", tint = Color.White)
                                }
                            }
                        }
                    }

                    // 长按 2 倍速提示
                    if (boosting) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.62f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp)
                        ) {
                            Text(
                                text = "2.0x 倍速播放中",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // 左右拖动快进/快退预览（松手才真正 seek）
                    seekPreviewMs?.let { target ->
                        val base = controller.currentPositionMs()
                        val delta = target - base
                        Surface(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = (if (delta >= 0) "快进 +" else "快退 -") +
                                        formatDuration((abs(delta) / 1000).toInt()),
                                    color = AccentOrange,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${formatDuration((base / 1000).toInt())} → " +
                                        formatDuration((target / 1000).toInt()),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // 缓冲指示（解析中 / 起播缓冲），只在无错误时显示
                    if ((playState is UiState.Loading || controller.state.buffering) &&
                        controller.state.error == null && playState !is UiState.Error
                    ) {
                        CircularProgressIndicator(
                            color = AccentOrange,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    // 取流解析失败提示（居中，不铺满，不遮挡其它区域）
                    if (playState is UiState.Error) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text((playState as UiState.Error).message, color = Color.White, fontSize = 13.sp)
                                Button(
                                    onClick = vm::load,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                                    modifier = Modifier.padding(top = 12.dp)
                                ) { Text("重试", color = Color.Black) }
                            }
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
