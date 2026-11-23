package com.youqu.vod.ui.modules

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.duanju.IndexModule
import com.youqu.vod.duanju.ModulePreferences
import com.youqu.vod.duanju.ModuleRepository
import com.youqu.vod.duanju.ModuleStore
import com.youqu.vod.duanju.SourceModule
import com.youqu.vod.duanju.isNewerVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ModulesUiState(
    val tab: Int = 0,
    val installed: List<SourceModule> = emptyList(),
    val store: ModuleStore = ModuleStore(emptyList(), ""),
    val loadingInstalled: Boolean = true,
    val loadingIndex: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingInstall: SourceModule? = null,
    val pendingUninstall: SourceModule? = null,
    val addressDialog: Boolean = false,
    val urlInput: String = "",
    val adultChecked: Boolean = false,
    val preferredSource: String? = null,
    val adultConfirmed: Boolean = false,
    val enabled: Map<String, Boolean> = emptyMap()
)

class ModulesViewModel(
    private val repository: ModuleRepository,
    private val preferences: ModulePreferences,
    private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()

    private val tabs = listOf("已安装", "在线仓库", "内置", "手动")

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loadingInstalled = true) }
            when (val r = repository.installed()) {
                is ApiResult.Ok -> _state.update { it.copy(installed = r.value, loadingInstalled = false) }
                is ApiResult.Err -> _state.update {
                    it.copy(loadingInstalled = false, message = "读取已安装模块失败：${r.message}")
                }
            }
            syncPreferences()
            loadIndex()
        }
    }

    fun loadIndex() {
        viewModelScope.launch {
            _state.update { it.copy(loadingIndex = true) }
            when (val r = repository.store()) {
                is ApiResult.Ok -> _state.update { it.copy(store = r.value, loadingIndex = false) }
                is ApiResult.Err -> _state.update {
                    it.copy(
                        loadingIndex = false,
                        store = it.store.copy(warning = it.store.warning.ifBlank { r.message })
                    )
                }
            }
        }
    }

    fun setTab(tab: Int) {
        if (tab in tabs.indices) _state.update { it.copy(tab = tab) }
    }

    /** 已安装模块的新版本（来自索引） */
    fun updateFor(module: SourceModule): IndexModule? =
        _state.value.store.items.firstOrNull { it.id == module.id && isNewerVersion(it.version, module.version) }

    fun isInstalled(id: String): Boolean = _state.value.installed.any { it.id == id }

    fun installFromIndex(entry: IndexModule) {
        prepare { repository.prepareIndexed(entry.id, entry.bundled) }
    }

    fun installFromUrl(url: String) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            _state.update { it.copy(message = "下载地址必须以 http:// 或 https:// 开头") }
            return
        }
        prepare { repository.prepareUrl(url.trim()) }
    }

    fun installFromFile(uri: Uri) {
        val path = copyToCache(uri)
        if (path == null) {
            _state.update { it.copy(message = "无法读取该文件，或文件超过 8MB") }
            return
        }
        prepare { repository.prepareFile(path) }
    }

    private fun prepare(call: suspend () -> ApiResult<SourceModule>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val r = call()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        busy = false,
                        pendingInstall = r.value,
                        adultChecked = false,
                        adultConfirmed = preferences.adultConfirmed
                    )
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, message = "准备安装失败：${r.message}") }
            }
        }
    }

    fun confirmInstall() {
        val pending = _state.value.pendingInstall ?: return
        if (pending.adult && !preferences.adultConfirmed && !_state.value.adultChecked) {
            _state.update { it.copy(message = "安装限制级模块前请先确认已年满 18 周岁") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, pendingInstall = null) }
            when (val r = repository.install(pending.token)) {
                is ApiResult.Ok -> {
                    if (pending.adult) preferences.confirmAdult()
                    _state.update { it.copy(busy = false, message = "已安装：${pending.displayName}") }
                    refresh()
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, message = "安装失败：${r.message}") }
            }
        }
    }

    fun requestUninstall(module: SourceModule) {
        _state.update { it.copy(pendingUninstall = module) }
    }

    fun confirmUninstall() {
        val pending = _state.value.pendingUninstall ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, pendingUninstall = null) }
            when (val r = repository.uninstall(pending.id)) {
                is ApiResult.Ok -> {
                    if (preferences.preferredSource == pending.id) preferences.setPreferredSource(null)
                    _state.update { it.copy(busy = false, message = "已卸载：${pending.displayName}") }
                    refresh()
                }
                is ApiResult.Err -> _state.update { it.copy(busy = false, message = "卸载失败：${r.message}") }
            }
        }
    }

    fun toggleEnabled(module: SourceModule) {
        preferences.setEnabled(module.id, !preferences.isEnabled(module.id))
        syncPreferences()
    }

    fun setPreferred(module: SourceModule) {
        preferences.setPreferredSource(module.id)
        syncPreferences()
        _state.update { it.copy(message = "已将 ${module.displayName} 设为默认短剧源") }
    }

    fun showAddressDialog() {
        _state.update { it.copy(addressDialog = true) }
    }

    fun setUrlInput(value: String) {
        _state.update { it.copy(urlInput = value) }
    }

    fun dismissAddressDialog() {
        _state.update { it.copy(addressDialog = false) }
    }

    fun setAdultChecked(value: Boolean) {
        _state.update { it.copy(adultChecked = value) }
    }

    fun dismissPending() {
        _state.update { it.copy(pendingInstall = null, pendingUninstall = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun syncPreferences() {
        _state.update {
            val enabled = it.installed.associate { m -> m.id to preferences.isEnabled(m.id) }
            it.copy(
                preferredSource = preferences.preferredSource,
                adultConfirmed = preferences.adultConfirmed,
                enabled = enabled
            )
        }
    }

    private fun copyToCache(uri: Uri): String? = try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val target = File(context.cacheDir, "module-import.zip")
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        if (target.length() > 8L * 1024 * 1024) {
            target.delete()
            null
        } else target.absolutePath
    } catch (t: Throwable) {
        null
    }
}
