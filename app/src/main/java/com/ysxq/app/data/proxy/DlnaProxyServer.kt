package com.ysxq.app.data.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DlnaProxyServer(port: Int = 0) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DlnaProxyServer"
        private const val PIPE_BUFFER_SIZE = 64 * 1024
        private const val DLNA_CONTENT_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        private const val BASE_URL = "https://cj.lziapi.com/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Referer", BASE_URL)
                .addHeader("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        })
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/health" -> serveHealth()
            uri.startsWith("/stream/") -> serveStream(uri, session)
            else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun serveHealth(): Response {
        return newFixedLengthResponse(Status.OK, "text/plain", "OK")
    }

    private fun serveStream(uri: String, httpSession: IHTTPSession): Response {
        val sessionId = uri.removePrefix("/stream/")
        if (sessionId.isBlank()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Missing sessionId")
        }

        val sessionManager = DlnaProxyService.sessionManager
        if (sessionManager == null) {
            Log.w(TAG, "SessionManager not initialized")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "SessionManager not initialized")
        }

        var proxySession = sessionManager.getSession(sessionId)
        if (proxySession == null) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Session not found: $sessionId")
        }

        if (sessionManager.isSessionComplete(sessionId)) {
            Log.i(TAG, "Session $sessionId complete, recreating from segment 0")
            val newSession = runBlocking {
                sessionManager.createSession(proxySession.m3u8Url, 0)
            }
            proxySession = newSession
        }

        val timeSeekHeader = httpSession.headers["timeseekrange.dlna.org"]
        var seekedToMs: Long? = null
        if (timeSeekHeader != null) {
            parseTimeSeekRange(timeSeekHeader)?.let { timeMs ->
                Log.i(TAG, "Seeking session $sessionId to ${timeMs}ms")
                sessionManager.seekToTime(sessionId, timeMs)
                seekedToMs = timeMs
            }
        }
        if (timeSeekHeader == null) {
            httpSession.headers["range"]?.let { rangeValue ->
                parseRangeHeader(rangeValue)?.let { timeMs ->
                    Log.i(TAG, "Seeking session $sessionId via Range to ${timeMs}ms")
                    sessionManager.seekToTime(sessionId, timeMs)
                    seekedToMs = timeMs
                }
            }
        }

        val playlist = proxySession.playlist
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)

        val initSegmentUri = playlist.initSegmentUri
        if (initSegmentUri != null) {
            try {
                downloadSegment(initSegmentUri)?.let { bytes ->
                    pipedOut.write(bytes)
                    pipedOut.flush()
                    Log.d(TAG, "Written init segment: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download init segment: ${e.message}")
            }
        }

        val firstSegment = sessionManager.getNextSegment(sessionId)
        if (firstSegment != null) {
            try {
                downloadSegment(firstSegment.url)?.let { bytes ->
                    pipedOut.write(bytes)
                    pipedOut.flush()
                    Log.d(TAG, "Pre-buffered first segment: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-buffer first segment: ${e.message}")
            }
        } else {
            pipedOut.close()
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No segments available")
        }

        thread(name = "stream-writer-$sessionId", isDaemon = true) {
            writeSegments(sessionId, pipedOut)
        }

        val response = newChunkedResponse(Status.OK, "video/mp2t", pipedIn)
        response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
        response.addHeader("Cache-Control", "no-cache")
        // DLNA duration header: enables TV progress bar and seek support
        if (playlist.totalDuration > 0) {
            val totalStr = formatNpt(playlist.totalDuration)
            response.addHeader("Content-Duration.dlna.org", String.format("%.3f", playlist.totalDuration))
            // TimeSeekRange response: tells TV where playback starts and total duration
            val startStr = seekedToMs?.let { formatNpt(it / 1000.0) } ?: "0.000"
            response.addHeader("TimeSeekRange.dlna.org", "npt=$startStr-$totalStr")
        }
        return response
    }

    private fun writeSegments(sessionId: String, output: PipedOutputStream) {
        try {
            val sessionManager = DlnaProxyService.sessionManager ?: return

            while (true) {
                val session = sessionManager.getSession(sessionId)
                if (session == null || !session.isActive) {
                    Log.i(TAG, "Session $sessionId inactive, stopping writer")
                    break
                }

                val segment = sessionManager.getNextSegment(sessionId) ?: break

                try {
                    val bytes = downloadSegmentWithRetry(segment.url)
                    if (bytes != null) {
                        output.write(bytes)
                        output.flush()
                    } else {
                        Log.w(TAG, "Failed to download segment after retry, skipping: ${segment.url}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error writing segment: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Writer thread error for session $sessionId: ${e.message}")
        } finally {
            try {
                output.close()
            } catch (_: Exception) {
                // Pipe close may fail if client disconnected first
            }
            Log.d(TAG, "Writer thread finished for session $sessionId")
        }
    }

    private fun downloadSegmentWithRetry(url: String): ByteArray? {
        val firstResult = tryDownload(url)
        if (firstResult?.second != null) return firstResult.second

        val code = firstResult?.first ?: -1
        if (code >= 400 && code != 404 && code != 410) {
            Log.w(TAG, "Retrying segment download (status $code): $url")
            Thread.sleep(500)
            return tryDownload(url)?.second
        }

        return null
    }

    private fun downloadSegment(url: String): ByteArray? {
        return tryDownload(url)?.second
    }

    private fun tryDownload(url: String): Pair<Int, ByteArray?>? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val code = response.code
            if (!response.isSuccessful) {
                response.close()
                Pair(code, null)
            } else {
                val body = response.body
                val bytes = body?.bytes()
                response.close()
                Pair(code, bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download error for $url: ${e.message}")
            Pair(-1, null)
        }
    }

    fun getStreamUrl(sessionId: String): String {
        val ip = getLanIp()
        val port = listeningPort
        return "http://$ip:$port/stream/$sessionId"
    }

    fun getLanIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name
                if (name == "wlan0" || name == "eth0") {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is InetAddress && !addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2?.hasMoreElements() == true) {
                val ni = interfaces2.nextElement()
                if (ni.isUp && !ni.isLoopback) {
                    val addresses = ni.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is InetAddress && !addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get LAN IP: ${e.message}")
            "127.0.0.1"
        }
    }

    /**
     * Parse TimeSeekRange.dlna.org header.
     * Format: "npt=START-END" or "npt=START-"
     * START/END in seconds or MM:SS or HH:MM:SS format.
     */
    private fun parseTimeSeekRange(headerValue: String): Long? {
        return try {
            val npt = headerValue.substringAfter("npt=").substringBefore("-").trim()
            if (npt.isBlank()) return null
            parseNptTime(npt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse TimeSeekRange: $headerValue")
            null
        }
    }

    /**
     * Parse Range header for time-based seeking.
     * Format: "npt=START-END" (DLNA style) or bytes range (ignored).
     */
    private fun parseRangeHeader(headerValue: String): Long? {
        if (headerValue.contains("npt=")) {
            return parseTimeSeekRange(headerValue)
        }
        return null
    }

    /**
     * Parse NPT (Normal Play Time) to milliseconds.
     * Supports: "123.456" (seconds), "12:34.567" (MM:SS), "1:02:03.456" (HH:MM:SS)
     */
    private fun parseNptTime(npt: String): Long {
        val parts = npt.split(":")
        return when (parts.size) {
            1 -> (parts[0].toDoubleOrNull() ?: 0.0) * 1000.0
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: 0.0
                val seconds = parts[1].toDoubleOrNull() ?: 0.0
                (minutes * 60.0 + seconds) * 1000.0
            }
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: 0.0
                val minutes = parts[1].toDoubleOrNull() ?: 0.0
                val seconds = parts[2].toDoubleOrNull() ?: 0.0
                ((hours * 3600.0) + (minutes * 60.0) + seconds) * 1000.0
            }
            else -> 0.0
        }.toLong()
    }

    /** Format seconds to NPT time string (HH:MM:SS.mmm) */
    private fun formatNpt(seconds: Double): String {
        val h = (seconds / 3600.0).toInt()
        val m = ((seconds % 3600.0) / 60.0).toInt()
        val s = seconds % 60.0
        return if (h > 0) {
            String.format("%d:%02d:%06.3f", h, m, s)
        } else {
            String.format("%02d:%06.3f", m, s)
        }
    }
}
