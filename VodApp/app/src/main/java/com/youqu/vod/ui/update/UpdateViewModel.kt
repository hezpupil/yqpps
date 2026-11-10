package com.youqu.vod.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.data.model.UpdateInfo
import com.youqu.vod.data.repo.UpdateCheck
import com.youqu.vod.data.repo.UpdateRepository
import com.youqu.vod.update.ApkDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 更新流程的界面状态 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 发现的新版本，非空时弹出更新对话框 */
    val available: UpdateInfo? = null,
    /** 下载进度 0~100，非空表示正在下载 */
    val progress: Int? = null,
    /** 需要提示给用户的一句话（已在最新版本、下载失败等），弹 Toast 后清空 */
    val message: String? = null,
    /** 当前版本号，界面展示用 */
    val versionLabel: String = ""
)

class UpdateViewModel(
    private val repository: UpdateRepository,
    private val downloader: ApkDownloader
) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateUiState(versionLabel = "${repository.currentVersionName} (${repository.currentVersionCode})")
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * 检查更新。
     * @param fromUser 是否是用户主动点击触发（自动检查失败时静默，不打扰用户）
     */
    fun check(fromUser: Boolean) {
        val s = _state.value
        if (s.checking || s.progress != null) return

        viewModelScope.launch {
            _state.update { it.copy(checking = true, message = null) }
            when (val result = repository.check(fromUser)) {
                is UpdateCheck.Available ->
                    _state.update { it.copy(checking = false, available = result.info) }

                is UpdateCheck.UpToDate ->
                    _state.update {
                        it.copy(
                            checking = false,
                            message = if (result.fromUser) "已是最新版本" else null
                        )
                    }

                is UpdateCheck.Failed ->
                    _state.update {
                        it.copy(
                            checking = false,
                            message = if (result.fromUser) result.message else null
                        )
                    }
            }
        }
    }

    /** 下载并安装。未获得「安装未知应用」授权时先跳设置页。
     *  强制更新下成功也不清除 available —— 用户在安装界面按返回后弹窗会重新出现，
     *  不能借此跳过更新。 */
    fun downloadAndInstall() {
        val info = _state.value.available ?: return
        viewModelScope.launch {
            _state.update { it.copy(progress = 0, message = null) }
            runCatching {
                downloader.download(info.apkUrl) { percent ->
                    _state.update { it.copy(progress = percent) }
                }
            }.onSuccess { file ->
                // 保留 available：安装界面被取消后强制更新弹窗仍在
                _state.update { it.copy(progress = null) }
                installOrRequestPermission(file)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        progress = null,
                        message = "下载失败：${e.message ?: "请稍后重试"}"
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun installOrRequestPermission(file: java.io.File) {
        if (!downloader.canInstall()) {
            downloader.openInstallPermissionSetting()
            _state.update { it.copy(message = "请允许「安装未知应用」后重新下载") }
            return
        }
        runCatching { downloader.install(file) }
            .onFailure { _state.update { it.copy(message = "无法打开安装界面") } }
    }
}
