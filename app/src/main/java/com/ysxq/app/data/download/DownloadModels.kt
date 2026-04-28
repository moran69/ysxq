package com.ysxq.app.data.download

import kotlinx.serialization.Serializable

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
data class DownloadTask(
    val id: String,
    val videoId: Int,
    val videoName: String,
    val videoPic: String,
    val episodeName: String,
    val episodeUrl: String,
    val savePath: String,
    val status: String = DownloadStatus.PENDING.name,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMsg: String? = null,
    val resolvedUrl: String? = null,
    val segmentCount: Int = 0
)

data class DownloadProgress(
    val taskId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speed: Long,
    val progress: Float
)
