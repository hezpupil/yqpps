package com.youqu.vod.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.repo.SearchResult
import com.youqu.vod.data.repo.VodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: VodRepository,
    private val keyword: String,
    private val page: Int,
    private val order: Int
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SearchResult>>(UiState.Loading)
    val state: StateFlow<UiState<SearchResult>> = _state.asStateFlow()

    val baseUrl: String get() = repository.baseUrl

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = when (val result = repository.search(keyword, page, order)) {
                is ApiResult.Ok -> UiState.Success(result.value)
                is ApiResult.Err -> UiState.Error(result.message)
            }
        }
    }
}
