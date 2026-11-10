package com.youqu.vod.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.ShowData
import com.youqu.vod.data.repo.VodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val repository: VodRepository,
    val videoId: String
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ShowData>>(UiState.Loading)
    val state: StateFlow<UiState<ShowData>> = _state.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = when (val result = repository.show(videoId)) {
                is ApiResult.Ok -> UiState.Success(result.value)
                is ApiResult.Err -> UiState.Error(result.message)
            }
        }
    }

    /** 播放心跳：网关要求周期性上报已播放秒数 */
    fun reportPlaytime(playcode: String) {
        if (playcode.isBlank()) return
        viewModelScope.launch {
            repository.playtime(playcode)
        }
    }
}
