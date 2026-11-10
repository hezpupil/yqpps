package com.youqu.vod.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.data.remote.GatewayUrls
import com.youqu.vod.data.repo.SearchResult
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.SearchField
import com.youqu.vod.ui.components.SkeletonGrid
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.components.VideoGrid
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.ui.theme.TextTertiary

/** 排序：1=最久，3=最热，9=最新（与网关 order 字段一致） */
private val ORDER_TABS = listOf(1 to "最久", 3 to "最热", 9 to "最新")

@Composable
fun SearchScreen(
    state: UiState<SearchResult>,
    keyword: String,
    order: Int,
    baseUrl: String,
    onRetry: () -> Unit,
    onSearchSubmit: (String) -> Unit,
    onOrderChange: (Int) -> Unit,
    onPageChange: (Int) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable(keyword) { mutableStateOf(keyword) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        SimpleTopBar(
            title = keyword,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
            }
        )

        SearchField(
            value = input,
            onValueChange = { input = it },
            onSubmit = { onSearchSubmit(input.trim()) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ORDER_TABS.forEach { (value, label) ->
                val selected = value == order
                Surface(
                    onClick = { if (!selected) onOrderChange(value) },
                    color = if (selected) AccentOrange else SurfaceDark2,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            if (state is UiState.Success) {
                Text(
                    text = "共 ${state.data.pages} 页",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        when (state) {
            is UiState.Loading, is UiState.Idle -> SkeletonGrid(count = 6, modifier = Modifier.weight(1f))
            is UiState.Error -> ErrorBox(message = state.message, onRetry = onRetry, modifier = Modifier.weight(1f))
            is UiState.Success -> {
                if (state.data.list.isEmpty()) {
                    EmptyBox(text = "没有找到相关视频", modifier = Modifier.weight(1f))
                } else {
                    VideoGrid(
                        items = state.data.list,
                        coverUrlOf = { item -> GatewayUrls.image(baseUrl, item.image.orEmpty()) },
                        onItemClick = { item -> onVideoClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (state is UiState.Success && state.data.list.isNotEmpty()) {
            Pager(
                page = state.data.page,
                pages = state.data.pages,
                onPageChange = onPageChange
            )
        }
    }
}

@Composable
private fun Pager(page: Int, pages: Int, onPageChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OledBlack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            onClick = { if (page > 1) onPageChange(page - 1) },
            color = if (page > 1) SurfaceDark2 else SurfaceDark2.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "上一页",
                color = if (page > 1) TextPrimary else TextTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }

        Text(text = "$page / $pages", color = TextSecondary, fontSize = 13.sp)

        Surface(
            onClick = { if (page < pages) onPageChange(page + 1) },
            color = if (page < pages) AccentOrange else SurfaceDark2.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "下一页",
                color = if (page < pages) Color.White else TextTertiary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}

