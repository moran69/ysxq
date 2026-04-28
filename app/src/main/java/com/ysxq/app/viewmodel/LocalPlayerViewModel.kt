package com.ysxq.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.download.DownloadTask
import com.ysxq.app.data.download.DownloadStatus
import com.ysxq.app.data.download.downloadStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class LocalPlayerState(
    val videoId: Int = 0,
    val videoName: String = "",
    val videoPic: String = "",
    val episodes: List<DownloadTask> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val error: String? = null
) {
    val currentFile: File?
        get() = episodes.getOrNull(currentEpisodeIndex)?.let {
            val f = File(it.savePath)
            if (f.exists()) f else null
        }
}

class LocalPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val store = application.downloadStore()
    private val TAG = "LocalPlayerVM"

    private val _state = MutableStateFlow(LocalPlayerState())
    val state: StateFlow<LocalPlayerState> = _state

    fun loadVideo(videoId: Int, initialEpisodeIndex: Int = 0) {
        viewModelScope.launch {
            val tasks = store.tasks.map { list ->
                list.filter { it.videoId == videoId && it.status == DownloadStatus.COMPLETED.name }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

            tasks.collect { episodes ->
                if (episodes.isEmpty() && _state.value.episodes.isEmpty()) {
                    _state.value = LocalPlayerState(
                        videoId = videoId,
                        error = "没有找到已下载的视频"
                    )
                    return@collect
                }

                val sortedEpisodes = episodes.sortedBy { it.episodeName }
                val first = sortedEpisodes.firstOrNull() ?: return@collect

                val newIndex = if (_state.value.currentEpisodeIndex == 0 && initialEpisodeIndex > 0) {
                    initialEpisodeIndex.coerceAtMost(sortedEpisodes.size - 1)
                } else {
                    val current = _state.value.currentEpisodeIndex
                    if (current < sortedEpisodes.size) current else 0
                }

                _state.value = LocalPlayerState(
                    videoId = videoId,
                    videoName = first.videoName,
                    videoPic = first.videoPic,
                    episodes = sortedEpisodes,
                    currentEpisodeIndex = newIndex
                )
            }
        }
    }

    fun selectEpisode(index: Int) {
        _state.value = _state.value.copy(currentEpisodeIndex = index)
    }
}
