package com.youqu.vod.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.repo.DramaPageResult
import com.youqu.vod.duanju.DramaSourceOption
import com.youqu.vod.duanju.ModuleDramaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧页：列表 + 搜索 + 数据源切换。
 * searchKeyword 非空时展示搜索结果，否则展示首页列表。两者共用同一套 DramaScreen。
 *
 * 加载策略（切页零等待）：
 * - 页面先渲染，数据在 IO 线程拉取；
 * - 已有列表时不回退到 Loading，静默刷新，避免切回来白屏转圈。
 */
class DramaViewModel(
    private val repository: ModuleDramaRepository,
    private val initialKeyword: String = ""
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<DramaPageResult>>(UiState.Loading)
    val list: StateFlow<UiState<DramaPageResult>> = _list.asStateFlow()

    private val _hot = MutableStateFlow<UiState<List<String>>>(UiState.Loading)
    val hot: StateFlow<UiState<List<String>>> = _hot.asStateFlow()

    /** 可切换的数据源（已启用且可用） */
    private val _sources = MutableStateFlow<List<DramaSourceOption>>(emptyList())
    val sources: StateFlow<List<DramaSourceOption>> = _sources.asStateFlow()

    /** 当前生效源显示名，用于顶栏标识 */
    private val _currentSource = MutableStateFlow<String?>(null)
    val currentSource: StateFlow<String?> = _currentSource.asStateFlow()

    /** 当前生效源 id，用于在切换列表里标记选中项 */
    private val _currentSourceId = MutableStateFlow<String?>(null)
    val currentSourceId: StateFlow<String?> = _currentSourceId.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    /** 当前生效的搜索词（空表示列表模式） */
    private var keyword: String = initialKeyword.trim()

    private var page = 1
    private var loadingMore = false
    /** 已加载数据对应的源版本，回到前台时比对，避免无谓重载 */
    private var seenRevision = Long.MIN_VALUE
    /** 第三方模块可能忽略 page 参数，限制总量避免无限追加把页面拖崩 */
    private val maxItems = 300

    init { load() }

    fun load() {
        page = 1
        seenRevision = repository.sourceRevision
        viewModelScope.launch {
            // 先解析源（本地、极快，同时填好源缓存），再拉列表，省掉一次重复的模块查询
            loadSources()
            loadPage(1)
        }
    }

    /**
     * 页面回到前台时调用：只有在模块装卸/启用/切源后源版本变了才重新加载，
     * 否则保持现有数据，做到"切页即进"、不重复请求。
     */
    fun syncIfSourceChanged() {
        if (repository.sourceRevision != seenRevision) refresh()
    }

    /** 执行搜索：切换关键词并重新加载 */
    fun search(kw: String) {
        keyword = kw.trim()
        load()
    }

    /**
     * 手动刷新：回到默认列表并强制重新拉取。
     * 切换模块后生效源会变，但 ViewModel 实例被导航复用，
     * 需要显式重载；同时清掉源缓存与详情缓存，避免旧模块数据串台。
     */
    fun refresh() {
        keyword = ""
        repository.invalidateSourceCache()
        repository.clearDetailCache()
        load()
    }

    /**
     * 切换数据源：写入默认源并立即重拉列表。
     * 多个模块同时启用时，列表只展示当前选中的这一个源。
     */
    fun switchSource(id: String) {
        if (id.isBlank()) return
        keyword = ""
        repository.switchSource(id)
        page = 1
        seenRevision = repository.sourceRevision
        // 切源必须清空旧列表，否则会短暂显示上一个源的内容
        _list.value = UiState.Loading
        viewModelScope.launch {
            loadSources()
            loadPage(1)
        }
    }

    fun loadMore() {
        val current = _list.value as? UiState.Success ?: return
        if (!current.data.more || loadingMore) return
        if (current.data.list.size >= maxItems) {
            _list.value = UiState.Success(current.data.copy(more = false))
            return
        }
        loadingMore = true
        viewModelScope.launch {
            try {
                val next = page + 1
                val r = if (keyword.isEmpty()) repository.lists(next) else repository.search(keyword, next)
                when (r) {
                    is ApiResult.Ok -> {
                        val existing = current.data.list.map { it.id?.toString().orEmpty() }.toSet()
                        val fresh = r.value.list.filter { item ->
                            val id = item.id?.toString().orEmpty()
                            id.isNotBlank() && id !in existing
                        }
                        if (r.value.list.isEmpty() || fresh.isEmpty()) {
                            // 模块忽略 page 参数时，停止继续翻页，避免无限追加
                            _list.value = UiState.Success(current.data.copy(more = false))
                        } else {
                            page = next
                            val merged = (current.data.list + fresh).take(maxItems)
                            _list.value = UiState.Success(
                                current.data.copy(
                                    list = merged,
                                    page = r.value.page,
                                    more = r.value.more && merged.size < maxItems
                                )
                            )
                        }
                    }
                    is ApiResult.Err -> {
                        // 翻页失败不弹错误，但不能继续触发，否则会反复请求
                        _list.value = UiState.Success(current.data.copy(more = false))
                    }
                }
            } catch (t: Throwable) {
                _list.value = UiState.Success(current.data.copy(more = false))
            } finally {
                loadingMore = false
            }
        }
    }

    /** 数据源列表与当前源标识：只影响顶栏展示，失败也不打断列表 */
    private suspend fun loadSources() {
        val options = try {
            when (val r = repository.sourceOptions()) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
        _sources.value = options

        val id = try {
            repository.currentSourceId()
        } catch (t: Throwable) {
            null
        }
        _currentSourceId.value = id
        _currentSource.value = id?.let { cur -> options.firstOrNull { it.id == cur }?.displayName }
    }

    private suspend fun loadPage(p: Int) {
        // 已有内容时静默刷新：不切回 Loading，避免切页/刷新时白屏
        val hasContent = (_list.value as? UiState.Success)?.data?.list?.isNotEmpty() == true
        if (!hasContent) _list.value = UiState.Loading
        _list.value = try {
            val r = if (keyword.isEmpty()) repository.lists(p) else repository.search(keyword, p)
            when (r) {
                is ApiResult.Ok -> UiState.Success(r.value)
                is ApiResult.Err -> UiState.Error(r.message)
            }
        } catch (t: Throwable) {
            UiState.Error("短剧列表加载失败：${t.message ?: "未知错误"}")
        }
    }
}
