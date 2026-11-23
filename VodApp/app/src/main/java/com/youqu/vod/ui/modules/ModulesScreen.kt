package com.youqu.vod.ui.modules

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youqu.vod.duanju.IndexModule
import com.youqu.vod.duanju.SourceModule
import com.youqu.vod.ui.components.SimpleTopBar
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.SurfaceDark2
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextSecondary
import com.youqu.vod.ui.theme.TextTertiary

private val labels = listOf("已安装", "在线仓库", "内置", "手动")
private val WarningRed = Color(0xFFFF6B6B)

@Composable
fun ModulesScreen(
    vm: ModulesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) vm.installFromFile(uri)
    }

    LaunchedEffect(s.message) {
        s.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(OledBlack)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SimpleTopBar(
                title = "短剧模块",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                }
            )

            TabRow(selectedTabIndex = s.tab, containerColor = SurfaceDark, contentColor = TextPrimary) {
                labels.forEachIndexed { index, label ->
                    Tab(
                        selected = s.tab == index,
                        onClick = { vm.setTab(index) },
                        text = { Text(label, fontSize = 12.sp) },
                        selectedContentColor = AccentOrange,
                        unselectedContentColor = TextTertiary
                    )
                }
            }

            when (s.tab) {
                0 -> InstalledTab(s, vm)
                1 -> CatalogTab(s, vm, bundled = false)
                2 -> CatalogTab(s, vm, bundled = true)
                3 -> ManualTab(vm, onPickFile = { filePicker.launch("*/*") })
            }
        }

        if (s.busy) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = SurfaceDark2, shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = AccentOrange)
                        Text("正在处理模块…", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    s.pendingInstall?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissPending,
            title = { Text(if (pending.previous.isNotBlank()) "更新 ${pending.displayName}？" else "安装 ${pending.displayName}？") },
            text = {
                Column {
                    Text("ID：${pending.id}", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        text = if (pending.previous.isNotBlank()) "版本：${pending.previous} → ${pending.version}" else "版本：${pending.version}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    if (pending.description.isNotBlank()) {
                        Text(pending.description, color = TextPrimary, fontSize = 12.sp)
                    }
                    if (pending.adult) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "限制级模块：仅限成年用户自行安装与使用，内容风险由使用者自行承担。",
                            color = WarningRed,
                            fontSize = 12.sp
                        )
                        if (!s.adultConfirmed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = s.adultChecked, onCheckedChange = { vm.setAdultChecked(it) })
                                Text("我已年满 18 周岁", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                    if (pending.hosts.isNotEmpty()) {
                        Text(
                            "访问域名：${pending.hosts.take(4).joinToString("、")}",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmInstall) { Text("确认安装") } },
            dismissButton = { TextButton(onClick = vm::dismissPending) { Text("取消") } }
        )
    }

    s.pendingUninstall?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissPending,
            title = { Text("卸载 ${pending.displayName}？") },
            text = { Text("模块将从本机删除，追剧记录与缓存不会被清除。", color = TextSecondary, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = vm::confirmUninstall) { Text("确认卸载") } },
            dismissButton = { TextButton(onClick = vm::dismissPending) { Text("取消") } }
        )
    }

    if (s.addressDialog) {
        AlertDialog(
            onDismissRequest = vm::dismissAddressDialog,
            title = { Text("输入模块下载地址") },
            text = {
                Column {
                    Text("支持指向 manifest.json + main.js 打包的 zip 文件，最大 8MB。", color = TextTertiary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = s.urlInput,
                        onValueChange = vm::setUrlInput,
                        singleLine = true,
                        placeholder = { Text("https://example.com/module.zip", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = s.urlInput.trim()
                    vm.dismissAddressDialog()
                    vm.installFromUrl(url)
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = vm::dismissAddressDialog) { Text("取消") } }
        )
    }
}

@Composable
private fun InstalledTab(s: ModulesUiState, vm: ModulesViewModel) {
    if (s.loadingInstalled) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AccentOrange)
        }
        return
    }
    if (s.installed.isEmpty()) {
        EmptyHint("还没有安装短剧模块", "去「在线仓库」或「内置」安装一个，就能在短剧页看到内容。")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(s.installed, key = { it.id }) { module ->
            val update = vm.updateFor(module)
            val enabled = s.enabled[module.id] != false
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(module.displayName, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (module.adult) AdultBadge()
                        Switch(checked = enabled, onCheckedChange = { vm.toggleEnabled(module) })
                    }
                    Text("${module.id}  ·  v${module.version}", color = TextTertiary, fontSize = 11.sp)
                    if (module.description.isNotBlank()) {
                        Text(module.description, color = TextSecondary, fontSize = 12.sp)
                    }
                    if (module.isBroken) {
                        Text("加载失败：${module.error}", color = WarningRed, fontSize = 11.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (update != null) {
                            SmallButton("更新到 ${update.version}") { vm.installFromIndex(update) }
                        }
                        if (s.preferredSource != module.id) {
                            SmallButton("设为默认") { vm.setPreferred(module) }
                        } else {
                            Text("当前默认", color = AccentOrange, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                        SmallButton("卸载", warning = true) { vm.requestUninstall(module) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogTab(s: ModulesUiState, vm: ModulesViewModel, bundled: Boolean) {
    Column(Modifier.fillMaxSize()) {
        if (!bundled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                SmallButton("刷新仓库") { vm.loadIndex() }
            }
        }
        if (s.store.warning.isNotBlank()) {
            Text(
                s.store.warning,
                color = WarningRed,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        if (s.loadingIndex) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentOrange)
            }
            return
        }
        val entries = s.store.items.filter { it.bundled == bundled && !vm.isInstalled(it.id) }
        if (entries.isEmpty()) {
            EmptyHint(
                if (bundled) "内置模块都已安装" else "在线仓库暂无可安装模块",
                if (bundled) "也可以在「手动」里安装本地 zip。" else "检查网络后点「刷新仓库」重试。"
            )
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.id }) { entry -> IndexCard(entry, vm) }
        }
    }
}

@Composable
private fun IndexCard(entry: IndexModule, vm: ModulesViewModel) {
    Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.displayName, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (entry.adult) AdultBadge()
            }
            Text("${entry.id}  ·  v${entry.version}", color = TextTertiary, fontSize = 11.sp)
            if (entry.description.isNotBlank()) {
                Text(entry.description, color = TextSecondary, fontSize = 12.sp)
            }
            if (entry.adult) {
                Text("限制级：仅限成年用户自行安装，内容风险自负。", color = WarningRed, fontSize = 11.sp)
            }
            SmallButton("安装") { vm.installFromIndex(entry) }
        }
    }
}

@Composable
private fun ManualTab(vm: ModulesViewModel, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("手动安装模块", color = TextPrimary, fontSize = 15.sp)
        Text(
            "模块是包含 manifest.json 与 main.js 的 zip 文件（最大 8MB）。安装前会显示模块详情，限制级模块需成年用户确认。",
            color = TextTertiary,
            fontSize = 12.sp
        )
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("选择本地 zip 文件")
        }
        OutlinedButton(onClick = vm::showAddressDialog, modifier = Modifier.fillMaxWidth()) {
            Text("输入网络 zip 地址")
        }
    }
}

@Composable
private fun SmallButton(text: String, warning: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (warning) SurfaceDark2 else SurfaceDark,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text,
            color = if (warning) WarningRed else AccentOrange,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun AdultBadge() {
    Surface(color = WarningRed, shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 6.dp)) {
        Text("限制级", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
    }
}

@Composable
private fun EmptyHint(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = TextSecondary, fontSize = 14.sp)
        Text(hint, color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
