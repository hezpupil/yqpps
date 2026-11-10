package com.youqu.vod.ui.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youqu.vod.data.local.ActivationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 激活门禁页：首次启动（未激活设备）阻塞在此。
 *
 * 交互流：
 * 1. 大号展示本机设备码 + 一键复制，用户把设备码发给管理员；
 * 2. 管理员在 PHP 后台生成/绑定激活码并告知用户；
 * 3. 用户输入激活码 → 云端验证（设备码+激活码一起上送）→ 通过后进入 App；
 * 4. 连续输错 5 次进入 30 秒冷却，防穷举。
 */
@Composable
fun ActivationScreen(
    repository: ActivationRepository,
    onActivated: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val deviceCode = remember { repository.deviceCode() }
    val context = androidx.compose.ui.platform.LocalContext.current

    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var checkingWhitelist by remember { mutableStateOf(false) }
    var failCount by remember { mutableIntStateOf(0) }
    var cooldown by remember { mutableIntStateOf(0) }

    // 打开即自动上报本机信息 + 机器码，后台「待开通设备」即可见（失败静默）
    LaunchedEffect(Unit) {
        repository.report(
            model = android.os.Build.MODEL,
            os = "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
            app = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (e: Exception) { "" }
        )
    }

    // 管理员已在后台「直接开通」本机：点此核对状态后免激活码进入
    fun checkDirectEntry() {
        if (verifying || checkingWhitelist || cooldown > 0) return
        checkingWhitelist = true
        error = null
        scope.launch {
            when (val r = repository.checkStatus()) {
                is ActivationRepository.StatusResult.Allowed -> {
                    repository.markActivated()
                    checkingWhitelist = false
                    onActivated()
                }
                is ActivationRepository.StatusResult.Blocked -> {
                    checkingWhitelist = false
                    error = r.msg
                }
                is ActivationRepository.StatusResult.NetworkError -> {
                    checkingWhitelist = false
                    error = "无网络连接，无法验证，请检查网络"
                }
            }
        }
    }

    // 冷却倒计时
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    fun tryActivate() {
        if (cooldown > 0 || verifying) return
        verifying = true
        error = null
        scope.launch {
            when (val result = repository.activate(code)) {
                is ActivationRepository.ActivateResult.Success -> {
                    repository.markActivated()
                    verifying = false
                    onActivated()
                }
                is ActivationRepository.ActivateResult.Failure -> {
                    verifying = false
                    failCount += 1
                    error = result.msg
                    // 仅在「激活码不正确」类错误上累计失败次数；网络类错误不计入冷却
                    if (failCount >= MAX_FAILS) {
                        cooldown = COOLDOWN_SECONDS
                        failCount = 0
                    }
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("友趣", style = MaterialTheme.typography.headlineMedium)
            Text(
                "本设备需要激活后方可使用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))
            Text(
                "本机设备码",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                deviceCode,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(deviceCode)) }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制设备码",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "把设备码发给管理员获取激活码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("激活码") },
                placeholder = { Text("XXXX-XXXX-XXXX") },
                singleLine = true,
                isError = error != null,
                enabled = !verifying && cooldown == 0,
                supportingText = {
                    when {
                        cooldown > 0 -> Text("错误次数过多，冷却中，剩余 ${cooldown}s", color = MaterialTheme.colorScheme.error)
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        else -> Text("输入管理员提供的激活码")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { tryActivate() }),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { tryActivate() },
                enabled = !verifying && cooldown == 0 && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (verifying) {
                    Box(Modifier.size(18.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("验证中…")
                } else {
                    Text("验证并进入")
                }
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { checkDirectEntry() },
                enabled = !checkingWhitelist && cooldown == 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (checkingWhitelist) {
                    Box(Modifier.size(18.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("核对中…")
                } else {
                    Text("我已被管理员开通，直接进入")
                }
            }
        }
    }
}

private const val MAX_FAILS = 5
private const val COOLDOWN_SECONDS = 30

/**
 * 拦截页：已激活设备在启动状态核对中未放行。
 * - 被管理员删除/解绑：提示原因，提供重新激活入口；
 * - 断网：提示连接网络，提供重新检查入口。
 */
@Composable
fun BlockedScreen(
    message: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Text("无法进入", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
        }
    }
}
