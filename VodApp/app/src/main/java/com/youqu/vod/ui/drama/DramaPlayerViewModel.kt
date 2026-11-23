package com.youqu.vod.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.model.DramaEpisode
import com.youqu.vod.duanju.ModuleDramaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 短剧独立播放页：加载详情 + 当前集 + resolve 真实播放地址。
 * 切集只更新 currentIndex 并重新 resolve，不重建页面。
 */
class DramaPlayerViewModel(
    private val repository: ModuleDramaRepository,
    val dramaId: String,
    startIndex: Int = 0
) : ViewModel() {

    private val _detail = MutableStateFlow<UiState<DramaDetailData>>(UiState.Loading)
    val detail: StateFlow<UiState<DramaDetailData>> = _detail.asStateFlow()

    private val _currentIndex = MutableStateFlow(startIndex.coerceAtLeast(0))
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** 当前集解析出的播放 URL（核心本地代理地址） */
    private val _playUrl = MutableStateFlow<UiState<String>>(UiState.Loading)
    val playUrl: StateFlow<UiState<String>> = _playUrl.asStateFlow()

    private var episodes: List<DramaEpisode> = emptyList()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _detail.value = UiState.Loading
            when (val r = repository.detail(dramaId)) {
                is ApiResult.Ok -> {
                    episodes = r.value.eps.orEmpty()
                    _detail.value = UiState.Success(r.value)
                    if (episodes.isEmpty()) {
                        _playUrl.value = UiState.Error("该剧暂无可播放剧集")
                    } else {
                        resolveAt(_currentIndex.value.coerceIn(0, episodes.lastIndex))
                    }
                }
                is ApiResult.Err -> {
                    _detail.value = UiState.Error(r.message)
                    _playUrl.value = UiState.Error(r.message)
                }
            }
        }
    }

    /** 切到指定集并重新解析播放地址 */
    fun selectEpisode(index: Int) {
        if (episodes.isEmpty()) return
        val target = index.coerceIn(0, episodes.lastIndex)
        _currentIndex.value = target
        resolveAt(target)
    }

    /** 相对当前集翻页（上下滑切集） */
    fun step(delta: Int) = selectEpisode(_currentIndex.value + delta)

    val currentEpisode: DramaEpisode? get() = episodes.getOrNull(_currentIndex.value)
    val episodeCount: Int get() = episodes.size

    private fun resolveAt(index: Int) {
        val ep = episodes.getOrNull(index) ?: return
        viewModelScope.launch {
            _playUrl.value = UiState.Loading
            // episode.id 存的是 chapter 原始 JSON，回传给核心 resolve
            val result = repository.resolve(dramaId, ep.id?.toString().orEmpty(), index)
            _playUrl.value = when (result) {
                is ApiResult.Ok -> UiState.Success(result.value)
                is ApiResult.Err -> UiState.Error(result.message)
            }
        }
    }
}
