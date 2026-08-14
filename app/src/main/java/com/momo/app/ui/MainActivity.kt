package com.momo.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.rememberNavController
import com.momo.app.ui.nav.AppNavHost
import com.momo.app.ui.theme.YsxqAppTheme

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
