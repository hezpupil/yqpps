package com.youqu.vod.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.HotWord
import com.youqu.vod.data.model.VideoItem
import com.youqu.vod.data.repo.DomainRepository
import com.youqu.vod.data.repo.VodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: VodRepository,
    private val domainRepository: DomainRepository
) : ViewModel() {

    private val _hot = MutableStateFlow<UiState<List<HotWord>>>(UiState.Loading)
    val hot: StateFlow<UiState<List<HotWord>>> = _hot.asStateFlow()

    private val _recent = MutableStateFlow<UiState<List<VideoItem>>>(UiState.Loading)
    val recent: StateFlow<UiState<List<VideoItem>>> = _recent.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            // 先用远程域名刷新网关地址（进程内只请求一次），再并发取两组数据
            domainRepository.ensure()

            launch {
                _hot.value = UiState.Loading
                _hot.value = when (val result = repository.index()) {
                    is ApiResult.Ok -> UiState.Success(result.value)
                    is ApiResult.Err -> UiState.Error(result.message)
                }
            }
            launch {
                _recent.value = UiState.Loading
                _recent.value = when (val result = repository.recent()) {
                    is ApiResult.Ok -> UiState.Success(result.value)
                    is ApiResult.Err -> UiState.Error(result.message)
                }
            }
        }
    }
}
