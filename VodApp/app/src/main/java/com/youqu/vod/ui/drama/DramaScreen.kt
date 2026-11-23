package com.youqu.vod.ui.drama

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.data.repo.DramaPageResult
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.OutlineChip
import com.youqu.vod.ui.components.SectionTitle
import com.youqu.vod.ui.components.SearchField
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary

@Composable
fun DramaScreen(
    listState: UiState<DramaPageResult>,
    hotState: UiState<List<String>>,
    baseUrl: String,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDramaClick: (DramaItem) -> Unit,
    onModulesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().background(OledBlack)
    ) {
        SimpleTopBar(
            title = "短剧",
            actions = {
                Surface(
                    onClick = onModulesClick,
                    color = SurfaceDark2,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "模块",
                        color = AccentOrange,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        )

        SearchField(
            value = keyword,
            onValueChange = onKeywordChange,
            onSubmit = { if (keyword.isNotBlank()) onSearch(keyword.trim()) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        SectionTitle(text = "热门搜索")
        when (hotState) {
            is UiState.Success -> {
                val words = hotState.data
                if (words.isEmpty()) {
                    Text("暂无热搜词", color = TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp))
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        itemsIndexed(words) { _, w ->
                            OutlineChip(
                                text = w,
                                onClick = { onSearch(w) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
            is UiState.Error, is UiState.Loading, is UiState.Idle -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    itemsIndexed((hotState as? UiState.Success)?.data ?: listOf("", "", "")) { _, _ ->
                        OutlineChip(text = "      ", onClick = {}, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }

        SectionTitle(text = "短剧列表")
        when (listState) {
            is UiState.Loading, is UiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentOrange)
                }
            }
            is UiState.Error -> ErrorBox(message = listState.message, onRetry = onRetry,
                modifier = Modifier.weight(1f))
            is UiState.Success -> {
                val page = listState.data
                if (page.list.isEmpty()) {
                    EmptyBox(text = "暂无短剧", modifier = Modifier.weight(1f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItemsIndexed(page.list) { index, item ->
                            DramaCard(
                                item = item,
                                coverUrl = item.cover.orEmpty().let { c ->
                                    if (c.startsWith("http", ignoreCase = true)) c
                                    else GatewayUrls.image(baseUrl, c)
                                },
                                onClick = { onDramaClick(item) }
                            )
                            if (index == page.list.size - 1 && page.more) {
                                LaunchedEffect(Unit) { onLoadMore() }
                            }
                        }
                        if (page.more) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = AccentOrange)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DramaCard(
    item: DramaItem,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .background(SurfaceDark2),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl.isNotBlank()) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val request = androidx.compose.runtime.remember(coverUrl, ctx) {
                    ImageRequest.Builder(ctx).data(coverUrl).size(360, 640).crossfade(true).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // 底部集数标签
            val ep = item.ep_text
            if (!ep.isNullOrBlank()) {
                Text(
                    text = ep,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = item.title?.takeIf { it.isNotBlank() } ?: "未命名",
            color = TextPrimary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}
