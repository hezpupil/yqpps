package com.youqu.vod

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youqu.vod.core.CrashHandler
import com.youqu.vod.data.local.ActivationRepository
import com.youqu.vod.ui.activation.ActivationScreen
import com.youqu.vod.ui.activation.BlockedScreen
import com.youqu.vod.ui.components.CrashLogDialog
import com.youqu.vod.ui.nav.AppNavHost
import com.youqu.vod.ui.theme.VodTheme

/**
 * 激活门禁启动流程：
 * - 未激活：激活页（设备码 + 激活码云端验证），通过后记录本机标记。
 * - 已激活：每次启动向后台核对设备状态——仍有效直接进入；
 *   被管理员删除/解绑则拦截并提示；断网/服务器不可达一律拦截（fail-closed），
 *   提示连接网络后重新检查。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 用 applicationContext 在合成前就把崩溃日志读出来，避免依赖组合内的 LocalContext
        val rawLog = if (CrashHandler.hasPending(applicationContext)) {
            CrashHandler.read(applicationContext)
        } else {
            ""
        }
        // native 崩溃没有 Java 堆栈，给一段带机型/系统的提示，方便用户发回给我们
        val crashLog: String = if (rawLog.isNotBlank()) {
            rawLog
        } else if (CrashHandler.hasPending(applicationContext)) {
            "上次应用在播放页异常退出（很可能是播放器 native 崩溃，系统未留下 Java 堆栈）。\n\n" +
                "机型：${Build.MODEL}\n系统：Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n\n" +
                "请把这些信息，以及你当时在做什么（点了哪个视频、用的什么网络），发给我，方便定位。"
        } else {
            ""
        }

        setContent {
            VodTheme {
                Surface(modifier = Modifier) {
                    var showCrash by remember { mutableStateOf(crashLog) }

                    val activationRepo = remember { ActivationRepository(applicationContext) }
                    // gate: Checking=核对中 / Allowed=放行 / Blocked(msg)=被拦截 / NeedActivation=未激活
                    var gate by remember {
                        mutableStateOf<GateState>(
                            if (activationRepo.isActivated()) GateState.Checking else GateState.NeedActivation
                        )
                    }

                    when (val state = gate) {
                        is GateState.Allowed -> AppNavHost()

                        is GateState.NeedActivation -> ActivationScreen(
                            repository = activationRepo,
                            onActivated = { gate = GateState.Allowed }
                        )

                        is GateState.Blocked -> BlockedScreen(
                            message = state.msg,
                            buttonText = if (state.network) "重新检查" else "重新输入激活码",
                            onAction = {
                                // 断网：重新核对；被停用：走重新激活
                                gate = if (state.network) GateState.Checking else GateState.NeedActivation
                            }
                        )

                        is GateState.Checking -> {
                            // 启动状态核对：后台确认设备码仍有效即放行
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(16.dp))
                                // 与 iOS GateLoadingView 保持同一句提示
                                Text(
                                    text = "正在核对设备状态…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LaunchedEffect(Unit) {
                                // 每次打开刷新本机信息上报（失败静默）
                                val ctx = applicationContext
                                val appVersion = try {
                                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                                } catch (e: Exception) { "" }
                                activationRepo.report(
                                    model = Build.MODEL,
                                    os = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                                    app = appVersion
                                )
                                when (val r = activationRepo.checkStatus()) {
                                    is ActivationRepository.StatusResult.Allowed ->
                                        gate = GateState.Allowed
                                    is ActivationRepository.StatusResult.Blocked ->
                                        gate = GateState.Blocked(r.msg)
                                    is ActivationRepository.StatusResult.NetworkError ->
                                        // 断网/服务器不可达：一律拦截（fail-closed），
                                        // 提示连接网络后重新检查
                                        gate = GateState.Blocked(
                                            "无网络连接，无法验证设备状态。\n请连接网络后点「重新检查」。",
                                            network = true
                                        )
                                }
                            }
                        }
                    }

                    if (showCrash.isNotBlank()) {
                        CrashLogDialog(
                            log = showCrash,
                            onDismiss = {
                                CrashHandler.clear(applicationContext)
                                showCrash = ""
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入前台：标记 true，供「前台异常退出」探测使用
        CrashHandler.markForeground(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户主动离开（Home / 最近任务）：标记 false，避免被误判为崩溃
        CrashHandler.markBackground(this)
    }

    override fun onStop() {
        super.onStop()
        CrashHandler.markBackground(this)
    }
}

private sealed interface GateState {
    data object Checking : GateState
    data object Allowed : GateState
    data object NeedActivation : GateState

    /** network=true 表示断网拦截（按钮=重新检查），false 表示被后台停用（按钮=重新激活） */
    data class Blocked(val msg: String, val network: Boolean = false) : GateState
}
