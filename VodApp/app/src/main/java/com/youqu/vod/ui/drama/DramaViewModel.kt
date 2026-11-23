package com.youqu.vod.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaItem
import com.youqu.vod.data.repo.DramaPageResult
import com.youqu.vod.duanju.ModuleDramaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧页：列表 + 热搜 + 搜索。
 * searchKeyword 非空时展示搜索结果，否则展示首页列表。两者共用同一套 DramaScreen。
 */
class DramaViewModel(
    private val repository: ModuleDramaRepository,
    private val initialKeyword: String = ""
) : ViewModel() {

    private val _list = MutableStateFlow<UiState<DramaPageResult>>(UiState.Loading)
    val list: StateFlow<UiState<DramaPageResult>> = _list.asStateFlow()

    private val _hot = MutableStateFlow<UiState<List<String>>>(UiState.Loading)
    val hot: StateFlow<UiState<List<String>>> = _hot.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    /** 当前生效的搜索词（空表示列表模式） */
    private var keyword: String = initialKeyword.trim()

    private var page = 1
    private var loadingMore = false
    /** 第三方模块可能忽略 page 参数，限制总量避免无限追加把页面拖崩 */
    private val maxItems = 300

    init { load() }

    fun load() {
        page = 1
        viewModelScope.launch {
            launch { loadPage(1) }
            if (keyword.isEmpty()) {
                launch {
                    _hot.value = UiState.Loading
                    _hot.value = try {
                        when (val r = repository.hotWords()) {
                            is ApiResult.Ok -> UiState.Success(r.value)
                            is ApiResult.Err -> UiState.Error(r.message)
                        }
                    } catch (t: Throwable) {
                        UiState.Error("热搜加载失败：${t.message ?: "未知错误"}")
                    }
                }
            }
        }
    }

    /** 执行搜索：切换关键词并重新加载 */
    fun search(kw: String) {
        keyword = kw.trim()
        load()
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
                        val existing = current.data.list.map { it.id }.toSet()
                        val fresh = r.value.list.filter { it.id.isNotBlank() && it.id !in existing }
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

    private suspend fun loadPage(p: Int) {
        _list.value = UiState.Loading
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
