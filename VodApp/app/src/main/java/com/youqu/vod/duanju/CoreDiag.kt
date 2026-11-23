package com.youqu.vod.duanju

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 短剧核心自检入口（测试包专用，验证 Go 核心 so 加载与红果取数）。
 * 顶栏的小扳手图标，点击后异步调用核心并展示结果，不阻塞 UI 线程。
 */
@Composable
fun CoreDiagAction() {
    var result by remember { mutableStateOf("点击右上角扳手自检 Go 短剧核心") }
    var show by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    IconButton(onClick = {
        show = true
        result = "自检中…（首次加载模块可能需要几秒）"
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    if (!NativeCore.isAvailable) {
                        "❌ 核心 so 未加载：检查 jniLibs 是否打进 libyouqu_duanju.so"
                    } else {
                        val init = NativeCore.initialize(context)
                        val cats = NativeCore.request("""{"action":"catalog","source":"hongguo","category":"short_play","page":1}""")
                        "initialize: ${init.take(300)}\n\ncatalog: ${cats.take(700)}"
                    }
                }.getOrElse { "❌ 异常：${it.message}" }
            }
            result = out
        }
    }) {
        Icon(Icons.Default.Build, contentDescription = "短剧核心自检", tint = MaterialTheme.colorScheme.primary)
    }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Go 短剧核心自检") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(result, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } }
        )
    }
}
