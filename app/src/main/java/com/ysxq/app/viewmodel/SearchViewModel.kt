package com.ysxq.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<VideoItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val suggestions: List<VideoItem> = emptyList(),
    val isLoadingSuggestions: Boolean = false
)

class SearchViewModel : ViewModel() {
    private val api = com.ysxq.app.data.NetworkModule.apiService

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionJob: Job? = null

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, suggestions = emptyList())
        if (query.isNotBlank()) {
            suggestionJob?.cancel()
            suggestionJob = viewModelScope.launch {
                delay(300)
                loadSuggestions(query.trim())
            }
        } else {
            _state.value = _state.value.copy(results = emptyList(), hasSearched = false, suggestions = emptyList())
        }
    }

    private suspend fun loadSuggestions(query: String) {
        try {
            _state.value = _state.value.copy(isLoadingSuggestions = true)
            val resp = api.getVideoList(ac = "detail", keyword = query, page = 1)
            _state.value = _state.value.copy(
                isLoadingSuggestions = false,
                suggestions = resp.list.take(10)
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoadingSuggestions = false, suggestions = emptyList())
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return

        searchJob?.cancel()
        suggestionJob?.cancel()
        _state.value = _state.value.copy(suggestions = emptyList())

        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                hasSearched = true,
                results = emptyList(),
                currentPage = 1,
                error = null
            )
            try {
                val resp = api.getVideoList(ac = "detail", keyword = query, page = 1)
                _state.value = _state.value.copy(
                    isLoading = false,
                    results = resp.list,
                    totalPages = resp.pagecount
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "搜索失败"
                )
            }
        }
    }

    fun searchMore() {
        val s = _state.value
        if (!s.isLoadingMore && s.currentPage < s.totalPages) {
            viewModelScope.launch {
                _state.value = _state.value.copy(isLoadingMore = true)
                try {
                    val currentState = _state.value
                    val resp = api.getVideoList(
                        ac = "detail",
                        keyword = currentState.query.trim(),
                        page = currentState.currentPage + 1
                    )
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        currentPage = currentState.currentPage + 1,
                        results = currentState.results + resp.list
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoadingMore = false, error = e.message ?: "加载更多失败")
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        _state.value = SearchState()
    }
}
