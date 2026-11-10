package com.youqu.vod.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.youqu.vod.data.model.UpdateInfo
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.ui.theme.TextTertiary

/** 体积（字节）转成 MB 展示文案，0 表示服务端未提供 */
private fun formatSize(bytes: Long): String? {
    if (bytes <= 0) return null
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 发现新版本 */
@Composable
fun UpdateFoundDialog(
    info: UpdateInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!info.force) onDismiss() },
        // 强制更新时不允许点外面或返回键取消
        properties = DialogProperties(
            dismissOnBackPress = !info.force,
            dismissOnClickOutside = !info.force
        ),
        shape = RoundedCornerShape(16.dp),
        containerColor = SurfaceDark2,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(
                text = "发现新版本 ${info.versionName}",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                val size = formatSize(info.size)
                if (size != null) {
                    Text(text = "安装包大小：$size", fontSize = 13.sp, color = TextTertiary)
                }
                if (info.changelog.isNotBlank()) {
                    Text(
                        text = info.changelog,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (info.force) {
                    Text(
                        text = "该版本为重要更新，需要升级后才能继续使用",
                        fontSize = 12.sp,
                        color = AccentOrange,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("立即更新", color = AccentOrange, fontSize = 14.sp)
            }
        },
        dismissButton = {
            if (!info.force) {
                TextButton(onClick = onDismiss) {
                    Text("稍后再说", color = TextTertiary, fontSize = 14.sp)
                }
            }
        }
    )
}

/** 下载进度 */
@Composable
fun DownloadProgressDialog(progress: Int) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark2,
            contentColor = TextPrimary
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = "正在下载更新…", fontSize = 15.sp, color = TextPrimary)
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    color = AccentOrange,
                    trackColor = SurfaceDark
                )
                Text(
                    text = "$progress%",
                    fontSize = 13.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
