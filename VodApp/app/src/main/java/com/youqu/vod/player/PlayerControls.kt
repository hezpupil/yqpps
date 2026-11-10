package com.youqu.vod.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextTertiary
import com.youqu.vod.util.formatDuration
import kotlinx.coroutines.delay

/** 手势灵敏度：约 12px = 1 秒，与 H5 端一致 */
private const val PX_PER_SECOND = 12f
/** 控制条自动隐藏时间 */
private const val CONTROLS_TIMEOUT_MS = 4_000L
private val SPEED_STEPS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
/** 长按临时倍速：按住 2.0x，松手恢复按住前的常驻倍速 */
private const val LONG_PRESS_SPEED = 2f

/**
 * 播放器区域：内核画面 + 统一控制层。
 * 控制层自绘，IJK 与 ExoPlayer 共用，避免两套交互。
 */
@Composable
fun PlayerStage(
    controller: PlaybackController,
    surface: @Composable (Modifier) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    /** 不为空时在失败浮层上多一个「换直连播放」之类的降级按钮 */
    fallbackLabel: String? = null,
    onFallback: (() -> Unit)? = null
) {
    val state = controller.state
    var controlsVisible by remember(controller) { mutableStateOf(true) }
    var scrubbing by remember(controller) { mutableStateOf(false) }
    var scrubMs by remember(controller) { mutableStateOf(0L) }
    var dragSeconds by remember(controller) { mutableStateOf(0f) }
    var dragging by remember(controller) { mutableStateOf(false) }
    // 长按临时倍速：记住长按前的常驻倍速，抬手时写回（setSpeed 会持久化 lastSpeed）
    var longPressing by remember(controller) { mutableStateOf(false) }
    var speedBeforeLongPress by remember(controller) { mutableStateOf(1f) }

    // 进度轮询：两个内核都没有进度回调，统一 500ms 采样一次
    LaunchedEffect(controller) {
        while (true) {
            if (!scrubbing) {
                state.positionMs = controller.currentPositionMs()
                state.durationMs = controller.durationMs()
            }
            delay(500)
        }
    }

    // 播放中且无操作，4 秒后自动隐藏控制条
    LaunchedEffect(controlsVisible, scrubbing, dragging, longPressing, state.playing, state.buffering) {
        if (controlsVisible && !scrubbing && !dragging && !longPressing && state.playing && !state.buffering) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(state.error) {
        if (state.error != null) controlsVisible = true
    }

    val duration = state.durationMs
    val position = if (scrubbing) scrubMs else state.positionMs
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(controller) {
                detectTapGestures(
                    onPress = {
                        // onPress 在独立协程里等抬手，与 onLongPress 并行；
                        // 无论长按是否触发，抬手都恢复常驻倍速。
                        tryAwaitRelease()
                        if (longPressing) {
                            controller.setSpeed(speedBeforeLongPress)
                            longPressing = false
                        }
                    },
                    onLongPress = {
                        // 按住画面进入临时 2 倍速；底部速度文字同步显示 2x
                        if (!longPressing) {
                            speedBeforeLongPress = state.speed
                            longPressing = true
                            controller.setSpeed(LONG_PRESS_SPEED)
                        }
                    },
                    onTap = { controlsVisible = !controlsVisible }
                )
            }
            .pointerInput(controller) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        // 长按与滑动互斥：开始左右滑动先退出临时倍速
                        if (longPressing) {
                            controller.setSpeed(speedBeforeLongPress)
                            longPressing = false
                        }
                        dragSeconds = 0f; dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        if (dragSeconds != 0f) {
                            controller.seekTo(state.positionMs + (dragSeconds * 1000).toLong())
                        }
                        dragSeconds = 0f
                    },
                    onDragCancel = { dragging = false; dragSeconds = 0f },
                    onHorizontalDrag = { _, amount ->
                        // amount 为正向右划（快进）、负向左划（快退）；seek 与浮层展示均以此为基准
                        dragSeconds += amount / PX_PER_SECOND
                    }
                )
            }
    ) {
        // 画面按视频真实宽高比自适应居中（高度撑满/宽度撑满取其一），不拉伸不裁切；
        // 尺寸未知（尚未收到 onVideoSizeChanged）时先铺满容器。
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = state.videoRatio
            if (ratio != null && ratio > 0f) {
                surface(Modifier.aspectRatio(ratio))
            } else {
                surface(Modifier.fillMaxSize())
            }
        }

        // 顶部条：全屏时才有，带返回与标题
        AnimatedVisibility(
            visible = controlsVisible && fullscreen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Text("‹", color = Color.White, fontSize = 26.sp)
                    }
                }
                Text(
                    text = title.orEmpty(),
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
            }
        }

        // 缓冲指示
        if (state.buffering && state.error == null) {
            CircularProgressIndicator(
                color = AccentOrange,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 拖动快进浮层
        if (dragging) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                val seconds = dragSeconds.toInt()
                val sign = if (seconds >= 0) "+" else ""
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "$sign${seconds}s", color = Color.White, fontSize = 18.sp)
                    Text(
                        text = "${
                            formatDuration(
                                ((state.positionMs / 1000).toInt() + seconds).coerceAtLeast(0)
                            )
                        } / ${formatDuration((duration / 1000).toInt())}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 长按临时倍速浮层（与拖动浮层互斥，居中提示）
        if (longPressing && !dragging) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    text = "${LONG_PRESS_SPEED}x 倍速中 · 松手恢复",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        // 底部控制条
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { controller.toggle() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "${formatDuration((position / 1000).toInt())} / ${
                            formatDuration((duration / 1000).toInt())
                        }",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = "${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}x",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .pointerInput(controller) {
                                detectTapGestures(onTap = {
                                    val idx = SPEED_STEPS.indexOf(state.speed)
                                    val next = SPEED_STEPS[(idx + 1) % SPEED_STEPS.size]
                                    controller.setSpeed(next)
                                })
                            }
                    )
                    Box(modifier = Modifier.weight(1f))
                    Surface(
                        onClick = onToggleFullscreen,
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (fullscreen) "退出全屏" else "全屏",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                Slider(
                    value = progress,
                    onValueChange = { value ->
                        if (duration > 0) {
                            scrubbing = true
                            scrubMs = (value * duration).toLong()
                        }
                    },
                    onValueChangeFinished = {
                        if (scrubbing) {
                            controller.seekTo(scrubMs)
                            scrubbing = false
                        }
                    },
                    enabled = duration > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentOrange,
                        activeTrackColor = AccentOrange,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
            }
        }

        // 失败覆盖层：给出原因 + 重试 + 换内核
        if (state.error != null) {
            Surface(color = Color.Black.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "播放失败", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        text = state.error.orEmpty(),
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                    if (state.diagnostic != null) {
                        Text(
                            text = state.diagnostic.orEmpty(),
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            onClick = onRetry,
                            color = AccentOrange,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = "重试",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                        }
                        if (fallbackLabel != null && onFallback != null) {
                            Surface(
                                onClick = onFallback,
                                color = Color.White.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = fallbackLabel,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
