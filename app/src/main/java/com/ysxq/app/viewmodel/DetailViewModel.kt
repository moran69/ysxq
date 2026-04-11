package com.ysxq.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailState(
    val isLoading: Boolean = false,
    val video: VideoItem? = null,
    val sources: List<VideoSource> = emptyList(),
    val currentSourceIndex: Int = 0,
    val currentEpisodeIndex: Int = 0,
    val error: String? = null
)

class DetailViewModel : ViewModel() {
    private val api = com.ysxq.app.data.NetworkModule.apiService
    private val cache = AppCache

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private var lastLoadedId: Int = -1

    fun loadDetail(videoId: Int) {
        if (lastLoadedId == videoId && _state.value.video != null) return
        lastLoadedId = videoId

        // 先查全局缓存
        val cached = cache.getVideoDetail(videoId)
        if (cached != null) {
            applyVideo(cached)
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val resp = api.getVideoDetail(ac = "detail", id = videoId)
                val video = resp.list.firstOrNull()
                if (video != null) cache.saveVideoDetail(videoId, video)
                if (video != null) applyVideo(video) else {
                    _state.value = _state.value.copy(isLoading = false, error = "未找到影片信息")
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "加载失败")
            }
        }
    }

    private fun applyVideo(video: VideoItem) {
        val sources = video.parsePlaySources().filter { it.label != "liangzi" }
        val preferredIndex = sources.indexOfFirst { it.label.contains("m3u8", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        _state.value = _state.value.copy(
            isLoading = false,
            video = video,
            sources = sources,
            currentSourceIndex = preferredIndex,
            currentEpisodeIndex = if (sources.getOrNull(preferredIndex)?.episodes?.isNotEmpty() == true) 0 else -1,
            error = null
        )
    }

    fun selectSource(index: Int) {
        _state.value = _state.value.copy(currentSourceIndex = index, currentEpisodeIndex = 0)
    }

    fun selectEpisode(index: Int) {
        _state.value = _state.value.copy(currentEpisodeIndex = index)
    }

    fun getCurrentEpisodeUrl(): String? {
        val s = _state.value
        return s.sources.getOrNull(s.currentSourceIndex)?.episodes?.getOrNull(s.currentEpisodeIndex)?.url
    }
}
