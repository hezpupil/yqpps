package com.youqu.vod.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youqu.vod.core.CrashHandler
import com.youqu.vod.data.local.PlayRecord
import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * 设置页：与 iOS 端保持相同的分区顺序与信息结构。
 * 版本更新 / 设备信息 / 访问凭证 / 搜索记录 / 播放记录 / 崩溃日志。
 * 搜索记录只出现一个标题，标签用 FlowRow 横排自动换行。
 * 搜索记录与播放记录均可点击：分别重新发起搜索、重新播放（与 iOS 一致）。
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    versionLabel: String,
    checking: Boolean,
    onCheckUpdate: () -> Unit,
    deviceCode: String,
    onRefreshToken: suspend () -> String,
    onDeviceActive: suspend () -> Boolean,
    onHistoryClick: (String) -> Unit,
    onPlayClick: (PlayRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf(settings.searchHistory) }
    var plays by remember { mutableStateOf(settings.playHistory) }
    var tokenInfo by remember { mutableStateOf("—") }
    var refreshing by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 只在崩过时出现，平时不占地方
    var crashLog by remember { mutableStateOf(CrashHandler.read(context)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
    ) {
        SimpleTopBar(title = "设置")

        /* ---------- 版本更新 ---------- */

        SectionTitle("版本更新")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "当前版本", color = TextSecondary, fontSize = 13.sp)
                Text(
                    text = versionLabel,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                onClick = onCheckUpdate,
                enabled = !checking,
                color = if (checking) SurfaceDark else AccentOrange,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = if (checking) "检查中…" else "检查更新",
                    color = if (checking) TextTertiary else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        Text(
            text = "每次启动会自动检查一次，发现新版本会弹窗提示。",
            color = TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        /* ---------- 设备信息 ---------- */

        SectionTitle("设备信息")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "机器码", color = TextSecondary, fontSize = 13.sp)
                Text(
                    text = deviceCode,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                onClick = {
                    clipboard.setText(AnnotatedString(deviceCode))
                    message = "机器码已复制"
                },
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "复制",
                    color = AccentOrange,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        /* ---------- 访问凭证 ---------- */

        SectionTitle("访问凭证")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(
                text = if (refreshing) "刷新中…" else "强制刷新访问凭证",
                enabled = !refreshing
            ) {
                scope.launch {
                    refreshing = true
                    tokenInfo = onRefreshToken()
                    message = if (tokenInfo.startsWith("刷新失败")) "凭证刷新失败" else "凭证已刷新"
                    refreshing = false
                }
            }
            ActionChip(
                text = if (activating) "上报中…" else "上报设备激活",
                enabled = !activating
            ) {
                scope.launch {
                    activating = true
                    val ok = onDeviceActive()
                    message = if (ok) "设备激活成功" else "设备激活失败，请检查网络"
                    activating = false
                }
            }
        }
        Text(
            text = tokenInfo,
            color = TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        /* ---------- 搜索记录（横排胶囊） ---------- */

        SectionTitle("搜索记录")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            ClearChip(text = "清空搜索记录") {
                settings.clearHistory()
                history = emptyList()
                message = "搜索记录已清空"
            }
        }
        if (history.isEmpty()) {
            Text(
                text = "暂无搜索记录",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEach { word ->
                    // 点击关键词重新搜索（导航到独立搜索结果页，返回后设置页状态不丢）
                    HistoryChip(text = word) { onHistoryClick(word) }
                }
            }
        }

        /* ---------- 播放记录 ---------- */

        SectionTitle("播放记录")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            ClearChip(text = "清空播放记录") {
                settings.clearPlayHistory()
                plays = emptyList()
                message = "播放记录已清空"
            }
        }
        if (plays.isEmpty()) {
            Text(
                text = "暂无播放记录",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            plays.forEach { record ->
                // 点击整行重新播放
                PlayRecordRow(record = record) { onPlayClick(record) }
            }
        }

        /* ---------- 崩溃日志（仅在崩过时出现） ---------- */

        if (crashLog.isNotBlank()) {
            SectionTitle("崩溃日志")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "最近一次崩溃", color = TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { clipboard.setText(AnnotatedString(crashLog)) },
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "复制",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            CrashHandler.clear(context)
                            crashLog = ""
                        },
                        color = SurfaceDark,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "清除",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Text(
                text = crashLog,
                color = TextTertiary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        message?.let {
            Text(
                text = it,
                color = AccentOrange,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ActionChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (enabled) AccentOrange else SurfaceDark,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ClearChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun HistoryChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = SurfaceDark2,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun PlayRecordRow(record: PlayRecord, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = AccentOrange,
                modifier = Modifier.size(18.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = record.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.timeText,
                    color = TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 箭头提示整行可点击，与 iOS 播放记录右侧 chevron 对齐
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
