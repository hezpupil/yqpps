package com.youqu.vod.ui.drama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.model.DramaDetailData
import com.youqu.vod.duanju.ModuleDramaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 短剧详情页：drama + eps（选集）。数据来自红果模块（Go 核心） */
class DramaDetailViewModel(
    private val repository: ModuleDramaRepository,
    val dramaId: String
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DramaDetailData>>(UiState.Loading)
    val state: StateFlow<UiState<DramaDetailData>> = _state.asStateFlow()

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
