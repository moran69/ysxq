package com.ysxq.app.data.proxy

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
    val resumePositionMs: Long
)

class ProxySessionManager {
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
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    suspend fun createSession(m3u8Url: String, resumePositionMs: Long): ProxySession {
        val playlist = withContext(Dispatchers.IO) {
            val firstResponse = fetchPlaylistContent(m3u8Url)
            val firstBaseUrl = firstResponse.second

            if (isMasterPlaylist(firstResponse.first)) {
                val mediaUrl = getMediaPlaylistUrl(firstResponse.first, firstBaseUrl)
                    ?: throw IllegalStateException("Master playlist has no media playlist URL")
                val secondResponse = fetchPlaylistContent(mediaUrl)
                parseM3u8(secondResponse.first, secondResponse.second)
            } else {
                parseM3u8(firstResponse.first, firstBaseUrl)
            }
        } ?: throw IllegalStateException("Failed to parse m3u8 playlist from $m3u8Url")

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
            val baseUrl = response.request.url.toString().substringBeforeLast("/")
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
    }

    internal fun addTestSession(session: ProxySession) {
        synchronized(sessions) {
            sessions[session.sessionId] = session
        }
    }
}
