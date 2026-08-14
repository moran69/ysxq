package com.momo.app.data.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class DlnaProxyService : Service() {

    companion object {
        private const val CHANNEL_ID = "dlna_cast_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PORT = "proxy_port"

        // Static references for composable access
        @Volatile
        var proxyServer: DlnaProxyServer? = null
            internal set
        @Volatile
        var sessionManager: ProxySessionManager? = null
            private set

        fun start(context: Context, proxyPort: Int = 0) {
            val intent = Intent(context, DlnaProxyService::class.java).apply {
                putExtra(EXTRA_PORT, proxyPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DlnaProxyService::class.java))
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create and show foreground notification
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire WiFi lock to prevent disconnection during casting
        acquireWifiLock()

        // Initialize session manager
        if (sessionManager == null) {
            sessionManager = ProxySessionManager()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Stop proxy server
        proxyServer?.stop()
        proxyServer = null

        // Cleanup sessions
        sessionManager?.destroyAllSessions()
        sessionManager = null

        // Release WiFi lock
        releaseWifiLock()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "投屏代理服务运行中"
                setShowBadge(false)
            }
            val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("投屏中")
                .setContentText("正在向电视推送视频流")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("投屏中")
                .setContentText("正在向电视推送视频流")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    private fun acquireWifiLock() {
        try {
            val wifiManager = ContextCompat.getSystemService(this, WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL, "DlnaProxyWifiLock")?.apply {
                acquire()
            }
            multicastLock = wifiManager?.createMulticastLock("DlnaProxyMulticast")?.apply {
                acquire()
            }
        } catch (e: Exception) {
            // WiFi lock acquisition failed, continue without it
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {
                    // Already released
                }
            }
        }
        wifiLock = null
        multicastLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (_: Exception) { }
            }
        }
        multicastLock = null
    }
}
