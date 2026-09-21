package com.momo.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.Episode
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import com.momo.app.data.susou.SusouApi
import com.momo.app.data.susou.SusouVideoItem
import com.momo.app.data.susou.friendlySourceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SusouState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<SusouVideoItem> = emptyList(),
    val hotList: List<SusouVideoItem> = emptyList(),
    val error: String? = null,
    // 详情加载中指示（点击卡片后）
    val isDetailLoading: Boolean = false
)

class SusouViewModel : ViewModel() {

    private val _state = MutableStateFlow(SusouState())
    val state: StateFlow<SusouState> = _state.asStateFlow()

    init {
        loadHot()
    }

    /** 进入页面自动加载热门/周期表（AES 解密接口） */
    fun loadHot() {
        viewModelScope.launch {
            try {
                val list = SusouApi.weekday()
                _state.value = _state.value.copy(hotList = list)
            } catch (_: Exception) {
                // 周期表失败不影响使用，保留空态
            }
        }
    }

    fun updateQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun search() {
        val keyword = _state.value.query.trim()
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, hasSearched = true, error = null)
            try {
                val list = SusouApi.search(keyword)
                _state.value = _state.value.copy(isLoading = false, results = list)
                if (list.isEmpty()) {
                    _state.value = _state.value.copy(error = "没有找到相关影片")
                }
            } catch (e: Exception) {
                android.util.Log.e("SusouVM", "搜索失败", e)
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
     * 点击速搜结果 → 备用源反查详情（ep 列表 m3u8 直链）
     * 返回 null 表示无可播放源
     */
    suspend fun resolveDetail(item: SusouVideoItem): Pair<VideoItem, List<VideoSource>>? {
        return SusouApi.resolveDetail(item)
    }
}
