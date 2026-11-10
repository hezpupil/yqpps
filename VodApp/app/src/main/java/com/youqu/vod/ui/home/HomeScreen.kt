package com.youqu.vod.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.HotWord
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.OutlineChip
import com.youqu.vod.ui.components.SearchField
import com.youqu.vod.ui.components.SectionTitle
import com.youqu.vod.ui.components.SkeletonGrid
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.components.VideoGrid
import com.youqu.vod.ui.theme.OledBlack

@Composable
fun HomeScreen(
    hotState: UiState<List<HotWord>>,
    recentState: UiState<List<VideoItem>>,
    baseUrl: String,
    onRetry: () -> Unit,
    onSearch: (String) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var keyword by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        SimpleTopBar(title = "友趣")

        SearchField(
            value = keyword,
            onValueChange = { keyword = it },
            onSubmit = {
                if (keyword.isNotBlank()) onSearch(keyword.trim())
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        SectionTitle(text = "热门搜索")

        when (hotState) {
            is UiState.Loading, is UiState.Idle -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(6) {
                        OutlineChip(text = "      ", onClick = {}, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }

            is UiState.Error -> {
                EmptyBox(
                    text = "热搜加载失败：${hotState.message}",
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f, fill = false)
                )
            }

            is UiState.Success -> {
                val words = hotState.data
                if (words.isEmpty()) {
                    EmptyBox(text = "暂无热搜词")
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        itemsIndexed(words) { _, word ->
                            val text = word.word ?: return@itemsIndexed
                            OutlineChip(
                                text = text,
                                onClick = { onSearch(text) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        SectionTitle(text = "最新更新")

        when (recentState) {
            is UiState.Loading, is UiState.Idle -> SkeletonGrid(count = 6, modifier = Modifier.weight(1f))
            is UiState.Error -> ErrorBox(message = recentState.message, onRetry = onRetry, modifier = Modifier.weight(1f))
            is UiState.Success -> {
                if (recentState.data.isEmpty()) {
                    EmptyBox(text = "暂无最新更新", modifier = Modifier.weight(1f))
                } else {
                    VideoGrid(
                        items = recentState.data,
                        coverUrlOf = { item -> GatewayUrls.image(baseUrl, item.image.orEmpty()) },
                        onItemClick = { item -> onVideoClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
