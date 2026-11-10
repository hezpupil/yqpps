package com.youqu.vod.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.util.durationSec
import com.youqu.vod.util.formatDuration
import kotlinx.coroutines.delay

/** 卡片进入视口后延迟这么久才发图片请求：快速滑动经过的卡片不会触发回源 */
private const val COVER_ARM_DELAY_MS = 120L

@Composable
fun VideoCard(
    item: VideoItem,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val screenHeightPx = context.resources.displayMetrics.heightPixels.toFloat()

    // 懒加载三段式：视口可见 → 停留 COVER_ARM_DELAY_MS 才发起请求 → 离屏立即取消。
    // 未发起 / 加载中显示默认小动图，成功或失败由 Coil 回调撤掉，避免动画空转。
    var visible by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    // 封面到了就撤掉小动图，避免几十个 InfiniteTransition 常驻空转
    var showMotion by remember(coverUrl) { mutableStateOf(true) }
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(COVER_ARM_DELAY_MS)
            armed = true
        } else {
            armed = false
            // 离屏后回来重新展示小动图，直到封面（通常命中 Coil 缓存）再次出现
            showMotion = true
        }
    }
    val noCover = coverUrl.isBlank()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(SurfaceDark2)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.localToWindow(Offset.Zero)
                    val bottom = topLeft.y + coordinates.size.height
                    val onScreen = topLeft.y < screenHeightPx && bottom > 0f
                    if (visible != onScreen) visible = onScreen
                }
        ) {
            // 底层：默认小动图（矢量循环动画，不需要额外 GIF 资源，也不占网络）
            if (showMotion && !coverFailed && !noCover) {
                CoverLoadingMotion(modifier = Modifier.align(Alignment.Center))
            }
            if (coverFailed || noCover) {
                Text(
                    text = "暂无封面",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (armed && !noCover) {
                // 只按卡片实际显示尺寸解码（480×270），避免整图进内存。
                // 注意：LocalContext.current 是 @Composable 调用，不能在 remember 的 lambda 里取
                val request = remember(coverUrl, context) {
                    ImageRequest.Builder(context)
                        .data(coverUrl)
                        .size(480, 270)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    // 不设 placeholder：加载中透出底层小动图，加载完成由回调撤掉动图
                    onSuccess = { _ ->
                        showMotion = false
                        coverFailed = false
                    },
                    onError = { _ ->
                        showMotion = false
                        coverFailed = true
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            val seconds = item.durationSec
            if (seconds > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = formatDuration(seconds),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Text(
            text = item.title?.takeIf { it.isNotBlank() } ?: "未命名",
            color = TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )

        if (!item.category.isNullOrBlank()) {
            Text(
                text = "分类 ${item.category}",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
        }
    }
}

/**
 * 封面未加载时的默认「小动图」：橙色旋转圆环 + 三颗呼吸小点。
 * 纯 Canvas 矢量动画，零资源、零解码开销，列表里几十个同时转也不会拖慢滑动。
 * iOS 端 CoverLoadingMotion 与之同款。
 */
@Composable
private fun CoverLoadingMotion(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cover-loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring"
    )
    val dot0 by transition.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "dot0"
    )
    val dot1 by transition.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(tween(520, delayMillis = 160), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by transition.animateFloat(
        0.2f, 1f,
        infiniteRepeatable(tween(520, delayMillis = 320), RepeatMode.Reverse),
        label = "dot2"
    )

    Canvas(modifier = modifier.size(40.dp)) {
        drawArc(
            color = AccentOrange,
            startAngle = angle,
            sweepAngle = 250f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
        val gap = 7.dp.toPx()
        val radius = 2.dp.toPx()
        val y = center.y
        drawCircle(AccentOrange.copy(alpha = dot0), radius, Offset(center.x - gap, y))
        drawCircle(AccentOrange.copy(alpha = dot1), radius, Offset(center.x, y))
        drawCircle(AccentOrange.copy(alpha = dot2), radius, Offset(center.x + gap, y))
    }
}

@Composable
fun VideoGrid(
    items: List<VideoItem>,
    coverUrlOf: (VideoItem) -> String,
    onItemClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items.size) { index ->
            val item = items[index]
            VideoCard(
                item = item,
                coverUrl = coverUrlOf(item),
                onClick = { onItemClick(item) }
            )
        }
    }
}

@Composable
fun SkeletonGrid(count: Int = 6, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(count) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceDark2),
                    contentAlignment = Alignment.Center
                ) {
                    // 首屏骨架屏也用同一套默认小动图，视觉与懒加载占位一致
                    CoverLoadingMotion()
                }
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.8f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceDark2)
                )
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentOrange)
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = TextSecondary, fontSize = 14.sp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = message, color = TextSecondary, fontSize = 14.sp)
        Surface(
            onClick = onRetry,
            color = AccentOrange,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = "重试",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentOrange)
        )
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 15.sp,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
