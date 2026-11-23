package com.youqu.vod.ui.drama

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.youqu.vod.ui.components.coverRequest
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.util.numText
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary

/**
 * 短剧详情页（对标 guoapp）：纯信息页，不内嵌播放器。
 * 顶部封面 + 标题/集数/评分 + 简介 + 剧集网格，点任一集进入独立播放页。
 */
@Composable
fun DramaDetailScreen(
    state: UiState<DramaDetailData>,
    coverUrl: String = "",
    onPlayEpisode: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is UiState.Loading, is UiState.Idle -> {
            Box(modifier.fillMaxSize().background(OledBlack), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
        }
        is UiState.Error -> ErrorBox(message = state.message, onRetry = onRetry, modifier = modifier.fillMaxSize())
        is UiState.Success -> {
            val data = state.data
            val drama = data.drama
            val eps = data.eps.orEmpty()
            val cover = drama?.cover?.takeIf { it.isNotBlank() } ?: coverUrl

            Column(modifier = modifier.fillMaxSize().background(OledBlack)) {
                // 顶部返回条（叠在封面上）
                Box(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            tint = Color.White)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                ) {
                    // 封面横幅
                    if (cover.isNotBlank()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                                .background(SurfaceDark2),
                            contentAlignment = Alignment.Center
                        ) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val request = androidx.compose.runtime.remember(cover, ctx) {
                                coverRequest(ctx, cover, 720, 480)
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = drama?.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // 标题 + 元信息
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(drama?.title ?: "短剧", color = TextPrimary, fontSize = 20.sp)
                        val epText = drama?.ep_text
                        if (!epText.isNullOrBlank()) {
                            Text(epText, color = AccentOrange, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        val intro = drama?.intro
                        if (!intro.isNullOrBlank()) {
                            Text(intro, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    }

                    // 开始播放按钮
                    if (eps.isNotEmpty()) {
                        Button(
                            onClick = { onPlayEpisode(0) },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Text("开始播放", color = Color.Black)
                        }
                    }

                    // 选集网格
                    Text(
                        "选集（共${eps.size}集）",
                        color = TextPrimary, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                    if (eps.isEmpty()) {
                        EmptyBox(text = "暂无剧集", modifier = Modifier.fillMaxWidth().height(160.dp))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            modifier = Modifier.fillMaxWidth().height(360.dp)
                                .navigationBarsPadding(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            userScrollEnabled = false
                        ) {
                            itemsIndexed(eps) { index, ep ->
                                Box(
                                    modifier = Modifier.size(46.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SurfaceDark)
                                        .clickable { onPlayEpisode(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        numText(ep.ep_no).ifEmpty { "${index + 1}" },
                                        color = TextPrimary, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
