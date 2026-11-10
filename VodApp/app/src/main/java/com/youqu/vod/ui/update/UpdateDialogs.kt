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

/**
 * 发现新版本 —— 【强制更新，不允许取消】。
 *
 * - 返回键、点弹窗外、「稍后再说」全部不生效 / 不存在；
 * - 正文只展示更新日志（与 iOS 一致），不展示下载地址、安装包大小等内部信息；
 * - 只保留一个「立即更新」按钮。
 * 配合 UpdateViewModel 在下载完成后仍保留 available，用户在安装界面按返回也绕不过去。
 */
@Composable
fun UpdateFoundDialog(
    info: UpdateInfo,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 强制更新：忽略所有关闭请求 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
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
                Text(
                    text = if (info.changelog.isNotBlank()) info.changelog
                    else "本次更新包含体验优化与问题修复。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Text(
                    text = "请更新后继续使用",
                    fontSize = 12.sp,
                    color = AccentOrange,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("立即更新", color = AccentOrange, fontSize = 14.sp)
            }
        }
        // 刻意不提供 dismissButton：不允许「稍后再说」
    )
}

/** 下载进度：同样不可取消 */
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
