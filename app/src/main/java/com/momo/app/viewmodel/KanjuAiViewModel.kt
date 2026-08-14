package com.momo.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import com.momo.app.data.kanjuai.KanjuAiApi
import com.momo.app.data.kanjuai.KanjuAiSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KanjuAiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<KanjuAiSuggestion> = emptyList(),
    val error: String? = null,
    val isDetailLoading: Boolean = false
)

class KanjuAiViewModel : ViewModel() {

    private val _state = MutableStateFlow(KanjuAiState())
    val state: StateFlow<KanjuAiState> = _state.asStateFlow()

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, hasSearched = true, error = null)
            try {
                val list = KanjuAiApi.search(keyword)
                _state.value = _state.value.copy(isLoading = false, results = list)
                if (list.isEmpty()) {
                    _state.value = _state.value.copy(error = "没有找到相关影片")
                }
            } catch (e: Exception) {
                android.util.Log.e("KanjuAiVM", "搜索失败", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "搜索失败"
                )
            }
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(query = "", hasSearched = false, results = emptyList(), error = null)
    }

    /**
     * 点击搜索结果 → 获取详情+剧集列表
     * 返回 null 表示无可播放源
     */
    suspend fun resolveDetail(item: KanjuAiSuggestion): Pair<VideoItem, List<VideoSource>>? {
        android.util.Log.d("KanjuAiVM", "resolveDetail: ${item.label} variantId=${item.target?.variantId}")
        val variantId = item.target?.variantId ?: return null
        return KanjuAiApi.fetchVideoDetailByVariantId(variantId, item.label)
    }
}
