package com.youqu.vod.ui.hot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.HotWord
import com.youqu.vod.data.repo.VodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HotViewModel(private val repository: VodRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<HotWord>>>(UiState.Loading)
    val state: StateFlow<UiState<List<HotWord>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = when (val result = repository.hot()) {
                is ApiResult.Ok -> UiState.Success(result.value)
                is ApiResult.Err -> UiState.Error(result.message)
            }
        }
    }
}
