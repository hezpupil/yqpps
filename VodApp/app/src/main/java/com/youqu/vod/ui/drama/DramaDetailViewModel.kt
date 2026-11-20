package com.youqu.vod.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.data.repo.DramaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 短剧详情：drama + author + eps（选集） */
class DramaDetailViewModel(
    private val repository: DramaRepository,
    val dramaId: String
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DramaDetailData>>(UiState.Loading)
    val state: StateFlow<UiState<DramaDetailData>> = _state.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = when (val r = repository.detail(dramaId)) {
                is ApiResult.Ok -> UiState.Success(r.value)
                is ApiResult.Err -> UiState.Error(r.message)
            }
        }
    }
}
