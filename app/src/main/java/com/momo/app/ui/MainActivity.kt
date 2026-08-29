package com.momo.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.rememberNavController
import com.momo.app.data.update.UpdateChecker
import com.momo.app.ui.nav.AppNavHost
import com.momo.app.ui.theme.YsxqAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YsxqAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController)
                    AutoUpdatePrompt()
                }
            }
        }
    }

    /**
     * Called when the user leaves the activity (presses Home, switches to another app).
     * If a video is actively playing, enter Picture-in-Picture mode so playback continues
     * in a small floating window.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipHelper.tryEnterPip(this)) {
            Log.d(TAG, "Entered PiP mode")
        }
    }

    /**
     * Handle PiP mode changes. When entering PiP, we keep the player running.
     * When exiting PiP, we restore the system UI (status bar / navigation bar)
     * if the player was in fullscreen mode.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipHelper.isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Entering PiP: hide system bars for a cleaner small window
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            // Exiting PiP: restore system bars (DetailScreen will re-hide them if fullscreen)
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        Log.d(TAG, "PiP mode changed: isInPip=$isInPictureInPictureMode")
    }

    override fun onDestroy() {
        super.onDestroy()
        PipHelper.reset()
    }
}

/**
 * 全局自动更新弹窗：App 启动后台检查发现新版本后提示，
 * 点「立即更新」应用内下载（带进度），完成后自动调起系统安装页。
 * forceUpdate=true 时不给「稍后」按钮，强制升级。
 */
@Composable
private fun AutoUpdatePrompt() {
    val context = LocalContext.current
    val info by UpdateChecker.pendingUpdate.collectAsState()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    val updateInfo = info ?: return
    AlertDialog(
        onDismissRequest = {
            if (!updateInfo.forceUpdate && !downloading) UpdateChecker.dismissUpdate()
        },
        title = { Text("发现新版本 v${updateInfo.versionName}") },
        text = {
            Column {
                if (updateInfo.updateLog.isNotBlank()) {
                    Text(updateInfo.updateLog)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("已下载 ${(progress * 100).toInt()}%", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    CoroutineScope(Dispatchers.IO).launch {
                        val apk = UpdateChecker.downloadApk(context, updateInfo.apkDownloadUrl) { p ->
                            progress = p
                        }
                        downloading = false
                        if (apk != null) {
                            UpdateChecker.dismissUpdate()
                            UpdateChecker.installApk(context, apk)
                        }
                    }
                }
            ) { Text(if (downloading) "下载中…" else "立即更新") }
        },
        dismissButton = if (!updateInfo.forceUpdate) {
            {
                TextButton(
                    enabled = !downloading,
                    onClick = { UpdateChecker.dismissUpdate() }
                ) { Text("稍后") }
            }
        } else null
    )
}
