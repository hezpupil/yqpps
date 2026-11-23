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

    init { load() }

    fun load() {
        page = 1
        viewModelScope.launch {
            launch { loadPage(1) }
            if (keyword.isEmpty()) {
                launch {
                    _hot.value = UiState.Loading
                    _hot.value = when (val r = repository.hotWords()) {
                        is ApiResult.Ok -> UiState.Success(r.value)
                        is ApiResult.Err -> UiState.Error(r.message)
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
        if (!current.data.more) return
        viewModelScope.launch {
            val next = page + 1
            val r = if (keyword.isEmpty()) repository.lists(next) else repository.search(keyword, next)
            when (r) {
                is ApiResult.Ok -> {
                    page = next
                    _list.value = UiState.Success(
                        DramaPageResult(
                            list = current.data.list + r.value.list,
                            page = r.value.page,
                            more = r.value.more
                        )
                    )
                }
                is ApiResult.Err -> { /* 翻页失败静默 */ }
            }
        }
    }

    private suspend fun loadPage(p: Int) {
        _list.value = UiState.Loading
        val r = if (keyword.isEmpty()) repository.lists(p) else repository.search(keyword, p)
        _list.value = when (r) {
            is ApiResult.Ok -> UiState.Success(r.value)
            is ApiResult.Err -> UiState.Error(r.message)
        }
    }
}
