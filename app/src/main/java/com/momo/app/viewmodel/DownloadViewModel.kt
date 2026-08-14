package com.momo.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.download.DownloadManager
import com.momo.app.data.download.downloadStore
import com.momo.app.data.download.DownloadTask
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DownloadUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val groupedByVideo: Map<Int, List<DownloadTask>> = emptyMap()
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val store = application.downloadStore()

    init {
        DownloadManager.init(application, store)
    }

    val state: StateFlow<DownloadUiState> = store.tasks
        .map { tasks ->
            DownloadUiState(
                tasks = tasks,
                groupedByVideo = tasks.groupBy { it.videoId }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DownloadUiState()
        )

    fun pauseDownload(taskId: String) {
        DownloadManager.pauseDownload(taskId)
    }

    fun resumeDownload(taskId: String) {
        DownloadManager.resumeDownload(taskId)
    }

    fun deleteDownload(taskId: String) {
        DownloadManager.cancelDownload(taskId)
    }

    fun deleteVideoDownloads(videoId: Int) {
        val tasks = state.value.groupedByVideo[videoId] ?: return
        tasks.forEach { task ->
            DownloadManager.cancelDownload(task.id)
        }
    }
}
