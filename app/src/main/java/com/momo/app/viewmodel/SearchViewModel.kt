package com.momo.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.*
import com.momo.app.data.local.searchHistoryStore
import com.momo.app.data.susou.SusouApi
import com.momo.app.data.susou.SusouVideoItem
import com.momo.app.data.kanjuai.KanjuAiApi
import com.momo.app.data.kanjuai.KanjuAiSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SearchSourceFilter(val displayName: String) {
    ALL("全部"),
    MAC_CMS("主源"),
    SUSOU("速搜"),
    KANJU_AI("看剧AI")
}

sealed class SearchResultItem {
    abstract val id: String
    abstract val title: String
    abstract val pic: String
    abstract val remarks: String
    abstract val sourceFilter: SearchSourceFilter
    abstract val sourceBadgeName: String

    data class MacCms(val video: VideoItem) : SearchResultItem() {
        override val id: String = "mac_${video.id}"
        override val title: String = video.name
        override val pic: String = video.pic
        override val remarks: String = video.remarks.ifBlank { video.typeName }
        override val sourceFilter: SearchSourceFilter = SearchSourceFilter.MAC_CMS
        override val sourceBadgeName: String = "主源"
    }

    data class Susou(val item: SusouVideoItem) : SearchResultItem() {
        override val id: String = "susou_${item.vodId}"
        override val title: String = item.vodName
        override val pic: String = item.vodPic
        override val remarks: String = item.vodRemarks.ifBlank { item.vodYear.ifBlank { "速搜" } }
        override val sourceFilter: SearchSourceFilter = SearchSourceFilter.SUSOU
        override val sourceBadgeName: String = "速搜"
    }

    data class KanjuAi(val item: KanjuAiSuggestion) : SearchResultItem() {
        override val id: String = "kanju_${item.id}"
        override val title: String = item.label
        override val pic: String = ""
        override val remarks: String = item.subtitle.ifBlank { "看剧AI" }
        override val sourceFilter: SearchSourceFilter = SearchSourceFilter.KANJU_AI
        override val sourceBadgeName: String = "看剧AI"
    }
}

data class SearchState(
    val query: String = "",
    val isLoading: Boolean = false,
    val selectedFilter: SearchSourceFilter = SearchSourceFilter.ALL,
    val macResults: List<SearchResultItem.MacCms> = emptyList(),
    val susouResults: List<SearchResultItem.Susou> = emptyList(),
    val kanjuResults: List<SearchResultItem.KanjuAi> = emptyList(),
    val allResults: List<SearchResultItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val suggestions: List<VideoItem> = emptyList(),
    val isLoadingSuggestions: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    val resolvingId: String? = null
) {
    val displayResults: List<SearchResultItem>
        get() = when (selectedFilter) {
            SearchSourceFilter.ALL -> allResults
            SearchSourceFilter.MAC_CMS -> macResults
            SearchSourceFilter.SUSOU -> susouResults
            SearchSourceFilter.KANJU_AI -> kanjuResults
        }

    val totalCount: Int
        get() = macResults.size + susouResults.size + kanjuResults.size
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val api = com.momo.app.data.NetworkModule.apiService
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

    fun selectFilter(filter: SearchSourceFilter) {
        _state.value = _state.value.copy(selectedFilter = filter)
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query, hasSearched = false)

        if (query.isNotBlank()) {
            val localResults = cache.searchLocal(query.trim(), 30)
            _state.value = _state.value.copy(suggestions = sortMacResults(localResults))

            // 索引未构建完成时，补 API 结果
            if (cache.isIndexBuilding) {
                suggestionJob?.cancel()
                suggestionJob = viewModelScope.launch {
                    delay(400)
                    if (_state.value.query.isBlank()) return@launch
                    loadSuggestions(query.trim())
                }
            }
        } else {
            searchJob?.cancel()
            suggestionJob?.cancel()
            _state.value = _state.value.copy(
                macResults = emptyList(),
                susouResults = emptyList(),
                kanjuResults = emptyList(),
                allResults = emptyList(),
                hasSearched = false,
                suggestions = emptyList()
            )
        }
    }

    private suspend fun loadSuggestions(query: String) {
        try {
            _state.value = _state.value.copy(isLoadingSuggestions = true)
            val resp = api.getVideoList(ac = "detail", keyword = query, page = 1)
            cache.addToSearchIndex(resp.list)
            val q = query.lowercase()
            val filtered = resp.list.filter { it.name.lowercase().contains(q) }
            val currentIds = _state.value.suggestions.map { it.id }.toSet()
            val newFromApi = filtered.filter { it.id !in currentIds }
            _state.value = _state.value.copy(
                isLoadingSuggestions = false,
                suggestions = sortMacResults(_state.value.suggestions + newFromApi)
            )
        } catch (_: Exception) {
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
                macResults = emptyList(),
                susouResults = emptyList(),
                kanjuResults = emptyList(),
                allResults = emptyList(),
                currentPage = 1,
                error = null
            )

            // 并行请求三大片源（自建主源、速搜、看剧AI）
            val macDeferred = async(Dispatchers.IO) {
                try {
                    val resp = api.getVideoList(ac = "detail", keyword = query, page = 1)
                    cache.addToSearchIndex(resp.list)
                    val sorted = sortMacResults(resp.list).map { SearchResultItem.MacCms(it) }
                    sorted to resp.pagecount
                } catch (_: Exception) {
                    emptyList<SearchResultItem.MacCms>() to 1
                }
            }

            val susouDeferred = async(Dispatchers.IO) {
                try {
                    val list = SusouApi.search(query)
                    list.map { SearchResultItem.Susou(it) }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val kanjuDeferred = async(Dispatchers.IO) {
                try {
                    val list = KanjuAiApi.search(query)
                    list.map { SearchResultItem.KanjuAi(it) }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val (macList, pageCount) = macDeferred.await()
            val susouList = susouDeferred.await()
            val kanjuList = kanjuDeferred.await()

            val merged = mergeMultiSourceResults(query, macList, susouList, kanjuList)

            _state.value = _state.value.copy(
                isLoading = false,
                macResults = macList,
                susouResults = susouList,
                kanjuResults = kanjuList,
                allResults = merged,
                totalPages = pageCount,
                error = if (merged.isEmpty()) "全网未找到相关影片，换个关键词试试~" else null
            )
        }
    }

    private fun mergeMultiSourceResults(
        query: String,
        macList: List<SearchResultItem.MacCms>,
        susouList: List<SearchResultItem.Susou>,
        kanjuList: List<SearchResultItem.KanjuAi>
    ): List<SearchResultItem> {
        val q = query.lowercase()
        fun relevance(item: SearchResultItem): Int {
            val title = item.title.lowercase()
            return when {
                title == q -> 0
                title.startsWith(q) -> 1
                title.contains(q) -> 2
                title.contains("解说") -> 9
                else -> 5
            }
        }

        // 轮询交替插入不同源，呈现多元平衡的结果列表
        val interleaved = mutableListOf<SearchResultItem>()
        val maxLen = maxOf(macList.size, susouList.size, kanjuList.size)
        for (i in 0 until maxLen) {
            if (i < macList.size) interleaved.add(macList[i])
            if (i < susouList.size) interleaved.add(susouList[i])
            if (i < kanjuList.size) interleaved.add(kanjuList[i])
        }

        return interleaved.sortedBy { relevance(it) }
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
                    val newMac = sortMacResults(resp.list).map { SearchResultItem.MacCms(it) }
                    val updatedMac = currentState.macResults + newMac
                    val updatedAll = currentState.allResults + newMac
                    _state.value = _state.value.copy(
                        isLoadingMore = false,
                        currentPage = currentState.currentPage + 1,
                        macResults = updatedMac,
                        allResults = updatedAll
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(isLoadingMore = false)
                }
            }
        }
    }

    /** 点击速搜卡片，异步解析真实可播放详情 */
    suspend fun resolveSusou(item: SusouVideoItem): Pair<VideoItem, List<VideoSource>>? {
        _state.value = _state.value.copy(resolvingId = "susou_${item.vodId}")
        return try {
            SusouApi.resolveDetail(item)
        } catch (_: Exception) {
            null
        } finally {
            _state.value = _state.value.copy(resolvingId = null)
        }
    }

    /** 点击看剧AI卡片，异步解析真实可播放详情 */
    suspend fun resolveKanjuAi(item: KanjuAiSuggestion): Pair<VideoItem, List<VideoSource>>? {
        _state.value = _state.value.copy(resolvingId = "kanju_${item.id}")
        return try {
            val variantId = item.target?.variantId ?: return null
            KanjuAiApi.fetchVideoDetailByVariantId(variantId, item.label)
        } catch (_: Exception) {
            null
        } finally {
            _state.value = _state.value.copy(resolvingId = null)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        _state.value = SearchState(searchHistory = _state.value.searchHistory)
    }

    private fun sortMacResults(videos: List<VideoItem>): List<VideoItem> {
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
