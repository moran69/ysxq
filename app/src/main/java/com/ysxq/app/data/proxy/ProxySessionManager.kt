package com.ysxq.app.data.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ProxySession(
    val sessionId: String,
    val m3u8Url: String,
    val playlist: M3u8Playlist,
    val startSegmentIndex: Int,
    var currentSegmentIndex: Int,
    var isActive: Boolean = true,
    val resumePositionMs: Long,
    @Volatile var streamEpoch: Int = 0,
    @Volatile var lastServedSegmentIndex: Int = -1,
    @Volatile var lastServedSegmentTimeMs: Long = 0L,
    @Volatile var lastServedAtMillis: Long = 0L,
    @Volatile var tvPlaybackConfirmed: Boolean = false,
    @Volatile var playbackStartAtMs: Long = 0L
)

class ProxySessionManager {
    private val TAG = "ProxySessionManager"
    private val sessions = mutableMapOf<String, ProxySession>()
    private val client: OkHttpClient
    private val BASE_URL = "https://cj.lziapi.com/"

    init {
        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Referer", BASE_URL)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    suspend fun createSession(m3u8Url: String, resumePositionMs: Long): ProxySession {
        val playlist = withContext(Dispatchers.IO) {
            Log.i(TAG, "Creating session for URL: $m3u8Url")
            val firstResponse = fetchPlaylistContent(m3u8Url)
            val firstBaseUrl = firstResponse.second
            Log.i(TAG, "Fetched playlist content: ${firstResponse.first.length} chars, baseUrl=$firstBaseUrl")
            Log.i(TAG, "First 200 chars: ${firstResponse.first.take(200)}")

            if (isMasterPlaylist(firstResponse.first)) {
                val mediaUrl = getMediaPlaylistUrl(firstResponse.first, firstBaseUrl)
                    ?: throw IllegalStateException("Master playlist has no media playlist URL")
                Log.i(TAG, "Master playlist detected, media URL: $mediaUrl")
                val secondResponse = fetchPlaylistContent(mediaUrl)
                parseM3u8(secondResponse.first, secondResponse.second)
            } else {
                parseM3u8(firstResponse.first, firstBaseUrl)
            }
        } ?: throw IllegalStateException("Failed to parse m3u8 playlist from $m3u8Url")

        Log.i(TAG, "Parsed playlist: ${playlist.segments.size} segments, duration=${playlist.totalDuration}s, initSegment=${playlist.initSegmentUri ?: "none"}")
        if (playlist.segments.isNotEmpty()) {
            Log.i(TAG, "First segment URL: ${playlist.segments.first().url}")
        }

        CastDiagnostics.log("Playlist parsed: ${playlist.segments.size} segments, duration=${playlist.totalDuration}s")
        CastDiagnostics.log("Init segment: ${playlist.initSegmentUri ?: "none"}")
        if (playlist.segments.isNotEmpty()) {
            CastDiagnostics.log("First 3 segment URLs:")
            playlist.segments.take(3).forEachIndexed { i, s ->
                CastDiagnostics.log("  [$i] dur=${s.duration}s url=${s.url}")
            }
        }

        val startSegmentIndex = timeToSegmentIndex(playlist, resumePositionMs)
        val sessionId = UUID.randomUUID().toString()
        val session = ProxySession(
            sessionId = sessionId,
            m3u8Url = m3u8Url,
            playlist = playlist,
            startSegmentIndex = startSegmentIndex,
            currentSegmentIndex = startSegmentIndex,
            resumePositionMs = resumePositionMs
        )
        synchronized(sessions) {
            sessions[sessionId] = session
        }
        return session
    }

    private fun fetchPlaylistContent(url: String): Pair<String, String> {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch playlist: ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty response body for $url")
            // Ensure baseUrl ends with "/" so java.net.URI.resolve() treats the last
            // path segment as a directory, not a filename. Without the trailing "/",
            // URI("http://a/b/c").resolve("d.m3u8") yields "http://a/b/d.m3u8"
            // instead of the correct "http://a/b/c/d.m3u8".
            val baseUrl = response.request.url.toString().substringBeforeLast("/") + "/"
            return Pair(body, baseUrl)
        } finally {
            response.close()
        }
    }

    fun getSession(sessionId: String): ProxySession? {
        return synchronized(sessions) { sessions[sessionId] }
    }

    fun getNextSegment(sessionId: String): M3u8Segment? {
        return synchronized(sessions) {
            val session = sessions[sessionId] ?: return null
            if (session.currentSegmentIndex >= session.playlist.segments.size) {
                return null
            }
            val segment = session.playlist.segments[session.currentSegmentIndex]
            session.currentSegmentIndex++
            segment
        }
    }

    fun seekToTime(sessionId: String, timeMs: Long): Boolean {
        return synchronized(sessions) {
            val session = sessions[sessionId] ?: return false
            val newIndex = timeToSegmentIndex(session.playlist, timeMs)
            session.currentSegmentIndex = newIndex
            true
        }
    }

    /**
     * Reset segment index to startSegmentIndex — used when TV reconnects
     * to avoid skipping segments after previous failed attempts.
     */
    fun resetToStart(sessionId: String) {
        synchronized(sessions) {
            val session = sessions[sessionId] ?: return
            session.currentSegmentIndex = session.startSegmentIndex
        }
    }

    fun bumpStreamEpoch(sessionId: String): Int {
        return synchronized(sessions) {
            val session = sessions[sessionId] ?: return -1
            session.streamEpoch++
            session.streamEpoch
        }
    }

    fun getEstimatedProgress(sessionId: String): Pair<Long, Long>? {
        val session = synchronized(sessions) { sessions[sessionId] } ?: return null
        val segments = session.playlist.segments
        if (segments.isEmpty()) return null
        val totalMs = (session.playlist.totalDuration * 1000).toLong()

        if (session.playbackStartAtMs > 0) {
            val elapsed = System.currentTimeMillis() - session.playbackStartAtMs
            return Pair(elapsed.coerceAtMost(totalMs), totalMs)
        }

        return Pair(0L, totalMs)
    }

    fun isSessionComplete(sessionId: String): Boolean {
        return synchronized(sessions) {
            val session = sessions[sessionId] ?: return true
            session.currentSegmentIndex >= session.playlist.segments.size
        }
    }

    fun destroySession(sessionId: String) {
        synchronized(sessions) {
            sessions[sessionId]?.isActive = false
            sessions.remove(sessionId)
        }
    }

    fun destroyAllSessions() {
        synchronized(sessions) {
            sessions.values.forEach { it.isActive = false }
            sessions.clear()
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    internal fun addTestSession(session: ProxySession) {
        synchronized(sessions) {
            sessions[session.sessionId] = session
        }
    }
}
