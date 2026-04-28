package com.ysxq.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.*
import com.ysxq.app.data.local.searchHistoryStore
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
    val isLoadingSuggestions: Boolean = false,
    val searchHistory: List<String> = emptyList()
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val api = com.ysxq.app.data.NetworkModule.apiService
    private val cache = AppCache
    private val historyStore = application.searchHistoryStore()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            historyStore.recentSearches.collect { history ->
                _state.value = _state.value.copy(searchHistory = history)
            }
        }
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, hasSearched = false)

        if (query.isNotBlank()) {
            val localResults = cache.searchLocal(query.trim(), 30)
            _state.value = _state.value.copy(suggestions = sortResults(localResults))

            // 索引未构建完成时，补 API 结果
            if (cache.isIndexBuilding) {
                suggestionJob?.cancel()
                suggestionJob = viewModelScope.launch {
                    delay(400)
                    // 检查 query 是否在 delay 期间被清空
                    if (_state.value.query.isBlank()) return@launch
                    loadSuggestions(query.trim())
                }
            }
        } else {
            searchJob?.cancel()
            suggestionJob?.cancel()
            _state.value = _state.value.copy(results = emptyList(), hasSearched = false, suggestions = emptyList())
        }
    }

    private suspend fun loadSuggestions(query: String) {
        try {
            _state.value = _state.value.copy(isLoadingSuggestions = true)
            val resp = api.getVideoList(ac = "detail", keyword = query, page = 1)
            cache.addToSearchIndex(resp.list)
            // 只保留名称确实包含关键词的 API 结果
            val q = query.lowercase()
            val filtered = resp.list.filter { it.name.lowercase().contains(q) }
            val currentIds = _state.value.suggestions.map { it.id }.toSet()
            val newFromApi = filtered.filter { it.id !in currentIds }
            _state.value = _state.value.copy(
                isLoadingSuggestions = false,
                suggestions = sortResults(_state.value.suggestions + newFromApi)
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoadingSuggestions = false)
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return

        searchJob?.cancel()
        suggestionJob?.cancel()
        _state.value = _state.value.copy(suggestions = emptyList())

        viewModelScope.launch { historyStore.addSearch(query) }

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
                cache.addToSearchIndex(resp.list)
                _state.value = _state.value.copy(
                    isLoading = false,
                    results = sortResults(resp.list),
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
                        results = sortResults(currentState.results + resp.list)
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
        _state.value = SearchState(searchHistory = _state.value.searchHistory)
    }

    private fun sortResults(videos: List<VideoItem>): List<VideoItem> {
        return videos.sortedBy { video ->
            when {
                video.name.contains("电影解说") || video.name.contains("[解说]") -> 1
                else -> 0
            }
        }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { historyStore.removeSearch(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyStore.clearAll() }
    }
}
