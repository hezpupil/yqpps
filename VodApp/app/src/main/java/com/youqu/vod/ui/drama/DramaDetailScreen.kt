package com.youqu.vod.ui.drama

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.model.DramaEpisode
import com.youqu.vod.player.ExoPlayerController
import com.youqu.vod.player.PlayerStage
import com.youqu.vod.player.VideoSurface
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary

private const val UA =
    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

@OptIn(ExperimentalLayoutApi::class)
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
            val controller = remember { ExoPlayerController(context, UA, baseUrl) }
            DisposableEffect(controller) { onDispose { runCatching { controller.release() } } }

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

            Column(
                modifier = modifier.fillMaxSize().background(OledBlack)
                    .verticalScroll(rememberScrollState())
            ) {
                // 顶部返回条
                Box(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            tint = Color.White)
                    }
                }

                // 播放器：16:9 内嵌，控制层与影视页同款
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .background(Color.Black)
                ) {
                    PlayerStage(
                        controller = controller,
                        surface = { m -> VideoSurface(controller, m) },
                        fullscreen = false,
                        onToggleFullscreen = {},
                        onRetry = { selectedEp?.src?.let { runCatching { controller.open(it) } } },
                        modifier = Modifier.fillMaxSize(),
                        title = data.drama?.title
                    )
                }

                // 标题 + 简介
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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

                // 选集
                Text("选集", color = TextPrimary, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                val eps = data.eps.orEmpty()
                if (eps.isEmpty()) {
                    EmptyBox(text = "暂无剧集", modifier = Modifier.fillMaxWidth().padding(24.dp))
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        eps.forEach { ep ->
                            val isSelected = selectedEp?.id == ep.id
                            val no = ep.ep_no?.toString() ?: "-"
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) AccentOrange else SurfaceDark)
                                    .then(
                                        if (isSelected) Modifier
                                        else Modifier.border(1.dp, Color.DarkGray, RoundedCornerShape(6.dp))
                                    )
                                    .clickable { selectedEp = ep },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(no, color = if (isSelected) Color.Black else TextPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(16.dp))
                }
            }
        }
    }
}
