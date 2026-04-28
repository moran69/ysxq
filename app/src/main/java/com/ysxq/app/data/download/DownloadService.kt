package com.ysxq.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 2001

        private const val ACTION_START = "com.ysxq.app.DOWNLOAD_START"
        private const val ACTION_STOP = "com.ysxq.app.DOWNLOAD_STOP"

        fun start(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager

    private val _taskSnapshots = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val taskSnapshots: StateFlow<Map<String, DownloadProgress>> = _taskSnapshots.asStateFlow()

    private var collectJob: Job? = null
    private var autoStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?: return
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("下载服务", "正在监控下载任务"))
                startTracking()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectJob?.cancel()
        autoStopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        if (collectJob?.isActive == true) return

        collectJob = serviceScope.launch {
            DownloadManager.progressFlow.collect { progress ->
                val current = _taskSnapshots.value.toMutableMap()
                current[progress.taskId] = progress
                _taskSnapshots.value = current
                updateNotification(current)
            }
        }

        scheduleAutoStop()
    }

    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            while (isActive) {
                delay(3000)
                if (DownloadManager.activeTaskCount() == 0) {
                    delay(2000)
                    if (DownloadManager.activeTaskCount() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }
                }
            }
        }
    }

    private fun updateNotification(snapshots: Map<String, DownloadProgress>) {
        val activeCount = DownloadManager.activeTaskCount()
        if (activeCount == 0 && snapshots.values.none { it.progress < 1f }) {
            return
        }

        val activeProgresses = snapshots.values.filter { it.progress < 1f }
        val title: String
        val text: String
        val percent: Int

        if (activeProgresses.size == 1) {
            val p = activeProgresses.first()
            percent = (p.progress * 100).toInt()
            val speedText = if (p.speed > 0) " · ${formatSpeed(p.speed)}" else ""
            title = "正在下载"
            text = "$percent%$speedText"
        } else if (activeProgresses.size > 1) {
            val avgProgress = activeProgresses.map { it.progress }.average().toFloat()
            percent = (avgProgress * 100).toInt()
            val totalSpeed = activeProgresses.sumOf { it.speed }
            title = "正在下载 ${activeProgresses.size} 个任务"
            text = "$percent% · ${formatSpeed(totalSpeed)}"
        } else {
            title = "下载完成"
            text = "所有任务已完成"
            percent = 100
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(activeProgresses.isNotEmpty())
                .setProgress(100, percent, false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(activeProgresses.isNotEmpty())
                .setProgress(100, percent, false)
                .build()
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "视频下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "视频下载进度通知"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000 -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
