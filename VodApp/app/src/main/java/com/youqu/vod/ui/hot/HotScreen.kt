package com.youqu.vod.ui.hot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.HotWord
import com.youqu.vod.ui.components.EmptyBox
import com.youqu.vod.ui.components.ErrorBox
import com.youqu.vod.ui.components.LoadingBox
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary

/**
 * 热搜页：关键词以圆角标签形式直接堆叠（FlowRow 自动换行），
 * 不带排名、不带序号，点一下直接搜索。
 */
@Composable
fun HotScreen(
    state: UiState<List<HotWord>>,
    onRetry: () -> Unit,
    onWordClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
    ) {
        SimpleTopBar(title = "热搜榜")

        when (state) {
            is UiState.Loading, is UiState.Idle -> LoadingBox(modifier = Modifier.weight(1f))
            is UiState.Error -> ErrorBox(message = state.message, onRetry = onRetry, modifier = Modifier.weight(1f))
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    EmptyBox(text = "暂无热搜数据", modifier = Modifier.weight(1f))
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            state.data.forEach { word ->
                                val text = word.word
                                if (!text.isNullOrBlank()) {
                                    HotChip(text = text, onClick = { onWordClick(text) })
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
private fun HotChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = SurfaceDark2,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
