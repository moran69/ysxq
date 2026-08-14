package com.momo.app.ui

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity

/**
 * Bridge between Compose (DetailScreen) and Activity (MainActivity) for PiP lifecycle.
 *
 * DetailScreen updates [hasActiveVideo] / [isPlaying] / [videoAspectRatio];
 * MainActivity reads these in onUserLeaveHint() to decide whether to enter PiP.
 */
object PipHelper {

    @Volatile
    var hasActiveVideo: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Video aspect ratio as (width, height) integers for precise Rational construction. */
    @Volatile
    private var videoWidth: Int = 16
    @Volatile
    private var videoHeight: Int = 9

    @Volatile
    var isInPipMode: Boolean = false
        internal set

    fun updateState(hasVideo: Boolean, playing: Boolean, width: Int, height: Int) {
        hasActiveVideo = hasVideo
        isPlaying = playing
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    fun reset() {
        hasActiveVideo = false
        isPlaying = false
        videoWidth = 16
        videoHeight = 9
    }

    /** Called from MainActivity.onUserLeaveHint(). Returns true if PiP was entered. */
    fun tryEnterPip(activity: ComponentActivity): Boolean {
        if (!hasActiveVideo || !isPlaying) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isInPipMode) return false

        return try {
            val params = PictureInPictureParams.Builder().apply {
                // Build Rational from integer width/height; clamp to Android's range
                val w = videoWidth.coerceAtLeast(1)
                val h = videoHeight.coerceAtLeast(1)
                val ratio = w.toFloat() / h.toFloat()
                val clamped = ratio.coerceIn(0.42f, 2.39f)
                // Re-scale to integers within a reasonable range for Rational
                val num = Math.round(clamped * 1000)
                setAspectRatio(Rational(num, 1000))
            }.build()
            activity.enterPictureInPictureMode(params)
            true
        } catch (e: Exception) {
            false
        }
    }
}
