package com.youqu.vod.ui.drama

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.repo.DramaPageResult
import com.youqu.vod.duanju.DramaSourceOption
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.SearchField
import com.youqu.vod.ui.components.SectionTitle
import com.youqu.vod.ui.components.coverRequest
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.BorderDark
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DramaScreen(
    listState: UiState<DramaPageResult>,
    keyword: String,
    currentSource: String?,
    currentSourceId: String?,
    sources: List<DramaSourceOption>,
    onKeywordChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSwitchSource: (String) -> Unit,
    onDramaClick: (DramaItem) -> Unit,
    onModulesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSourceSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().background(OledBlack)
    ) {
        DramaTopBar(
            currentSource = currentSource,
            onRefresh = onRefresh,
            onSourceClick = { showSourceSheet = true },
            onModulesClick = onModulesClick
        )

        SearchField(
            value = keyword,
            onValueChange = onKeywordChange,
            onSubmit = { if (keyword.isNotBlank()) onSearch(keyword.trim()) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        SectionTitle(text = "短剧列表")
        when (listState) {
            is UiState.Loading, is UiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentOrange)
                }
            }
            is UiState.Error -> ErrorBox(
                message = listState.message,
                onRetry = onRetry,
                modifier = Modifier.weight(1f)
            )
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
                        gridItemsIndexed(page.list) { _, item ->
                            DramaCard(
                                item = item,
                                coverUrl = item.cover.orEmpty(),
                                onClick = { onDramaClick(item) }
                            )
                        }
                        if (page.more) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = AccentOrange)
                                }
                                // 只在底部加载条触发一次；模块忽略分页时会由 ViewModel 关掉 more
                                LaunchedEffect(page.list.size) { onLoadMore() }
                            }
                        }
                    }
                }
            }
        }
    }

    // 切换数据源浮层：多个模块同时启用时，列表只展示当前选中的这一个源
    if (showSourceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            containerColor = OledBlack
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(
                    "切换短剧源",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "同时启用多个模块时，短剧列表只显示当前选中源的内容",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (sources.isEmpty()) {
                    Text(
                        "没有可用的短剧模块，请先到模块管理安装并启用",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    sources.forEach { option ->
                        val selected = option.id == currentSourceId
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    showSourceSheet = false
                                    if (!selected) onSwitchSource(option.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                option.displayName,
                                color = if (selected) AccentOrange else TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (option.adult) {
                                Text(
                                    "限制级",
                                    color = AccentOrange,
                                    fontSize = 10.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentOrange.copy(alpha = 0.14f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "当前使用",
                                    tint = AccentOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 短剧顶栏：左上角直接标出当前在用的模块，点一下即可切换。
 * 同时保留刷新与模块管理入口。
 */
@Composable
private fun DramaTopBar(
    currentSource: String?,
    onRefresh: () -> Unit,
    onSourceClick: () -> Unit,
    onModulesClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(OledBlack)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "短剧",
            color = AccentOrange,
            fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        // 当前模块标识：不知道源时不显示，避免误导
        Surface(
            onClick = onSourceClick,
            color = SurfaceDark2,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (currentSource != null) AccentOrange else BorderDark)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = currentSource ?: "无可用源",
                    color = if (currentSource != null) TextPrimary else TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 104.dp)
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "切换源",
                    tint = TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = AccentOrange)
        }
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
                    coverRequest(ctx, coverUrl, 360, 640)
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
