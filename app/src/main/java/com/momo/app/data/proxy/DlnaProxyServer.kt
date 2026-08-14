package com.momo.app.data.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DlnaProxyServer(port: Int = 0) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "DlnaProxyServer"
        private const val DLNA_CONTENT_FEATURES =
            "DLNA.ORG_OP=11;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        private const val DLNA_PLAYLIST_CONTENT_FEATURES =
            "DLNA.ORG_OP=11;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=81700000000000000000000000000000"
        private const val BASE_URL = "http://161.118.252.183:8899/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val PREFETCH_AHEAD = 50
        private const val CONCURRENT_DOWNLOADS = 3
        private const val SEEK_CONCURRENT_DOWNLOADS = 8
        private const val INITIAL_WAIT_MS = 3000L
        private const val INITIAL_MIN_SEGMENTS = 5
        private const val MAX_CACHED_SEGMENTS = 25
        private const val MAX_RAW_BUFFER = 20
        private const val EVICT_BEHIND = 3
        private const val SEEK_INITIAL_SEGMENTS = 5
        private const val SPIN_WAIT_INTERVAL_MS = 20L
        private const val SPIN_WAIT_TOTAL_MS = 500L
    }

    @Volatile var requestCount = 0; private set
    @Volatile var lastRequestUrl = ""; private set
    @Volatile var lastRequestFrom = ""; private set
    @Volatile var lastResponseStatus = ""; private set
    @Volatile var segmentFormat = ""; private set

    private val ptsFixer = PtsFixer()
    private val segmentCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val rawBuffer = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    @Volatile private var activeSessionId: String? = null
    private var sessionScope: CoroutineScope? = null
    private var eagerJob: Job? = null
    private var aheadJob: Job? = null
    @Volatile private var lastServedBeforeCurrent = -1
    private val cacheLock = Any()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 15 })
        .connectionPool(okhttp3.ConnectionPool(15, 5, TimeUnit.MINUTES))
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
        val clientIp = session.remoteIpAddress
        requestCount++
        lastRequestUrl = "${session.method} $uri"
        lastRequestFrom = clientIp
        Log.d(TAG, ">>> REQUEST #$requestCount from $clientIp: ${session.method} $uri")

        val response = when {
            uri == "/health" -> serveHealth()
            uri.startsWith("/playlist/") -> servePlaylist(uri)
            uri.startsWith("/initsegment/") -> serveInitSegment(uri)
            uri.startsWith("/segment/") -> serveSegment(uri)
            uri.startsWith("/localfile/") -> serveLocalFile(uri, session)
            else -> newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found")
        }

        lastResponseStatus = "${response.status} ${response.mimeType}"
        Log.d(TAG, "<<< RESPONSE to $clientIp: ${response.status} (${response.mimeType})")
        return response
    }

    fun startEagerPrefetch(sessionId: String, fromIndex: Int = 0) {
        activeSessionId = sessionId
        eagerJob?.cancel()
        aheadJob?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope?.cancel()
        sessionScope = scope
        eagerJob = scope.launch {
            val sessionManager = DlnaProxyService.sessionManager ?: return@launch
            val session = sessionManager.getSession(sessionId) ?: return@launch
            val segments = session.playlist.segments
            val isSeek = fromIndex > 0
            val concurrency = if (isSeek) SEEK_CONCURRENT_DOWNLOADS else CONCURRENT_DOWNLOADS
            val maxIndex = if (isSeek) {
                minOf(fromIndex + MAX_CACHED_SEGMENTS, segments.size)
            } else {
                minOf(fromIndex + MAX_CACHED_SEGMENTS + PREFETCH_AHEAD, segments.size)
            }
            Log.i(TAG, "Eager prefetch from $fromIndex to $maxIndex (seek=$isSeek, concurrency=$concurrency)")

            val downloadCursor = AtomicInteger(fromIndex)

            val fixerJob = launch(Dispatchers.IO) {
                var fixIndex = fromIndex
                while (fixIndex < maxIndex && isActive && activeSessionId == sessionId && session.isActive) {
                    val key = "$sessionId/$fixIndex"
                    if (segmentCache.containsKey(key)) {
                        fixIndex++
                        continue
                    }
                    val raw = rawBuffer.remove(key)
                    if (raw != null) {
                        if (segments[fixIndex].isAfterDiscontinuity) {
                            ptsFixer.reset()
                            Log.i(TAG, "PTS discontinuity marker — reset PtsFixer at segment $fixIndex")
                        }
                        val fixed = ptsFixer.fixSegment(raw)
                        segmentCache.putIfAbsent(key, fixed)
                        Log.d(TAG, "Fixed & cached segment $fixIndex/${segments.size}")
                        fixIndex++
                    } else {
                        delay(50)
                    }
                }
                Log.i(TAG, "Ordered fixer complete at index $fixIndex/${segments.size}")
            }

            val downloadJobs = (0 until concurrency).map {
                launch(Dispatchers.IO) {
                    while (isActive && activeSessionId == sessionId && session.isActive) {
                        val i = downloadCursor.getAndIncrement()
                        if (i >= maxIndex) break
                        val key = "$sessionId/$i"
                        if (rawBuffer.containsKey(key) || segmentCache.containsKey(key)) continue
                        val bytes = downloadSegmentWithRetry(segments[i].url)
                        if (bytes != null) {
                            rawBuffer[key] = bytes
                        }
                    }
                }
            }

            downloadJobs.forEach { it.join() }
            fixerJob.join()
            Log.i(TAG, "Eager prefetch complete: cached ${segmentCache.keys.count { it.startsWith("$sessionId/") }} segments")
        }
    }

    /**
     * Download-only ahead prefetch — puts raw bytes into rawBuffer for the
     * ordered fixer (inside startEagerPrefetch) to process in sequence.
     *
     * On seek (big jump): clears distant cache, restarts eager prefetch from seek position.
     */
    private fun triggerAheadPrefetch(sessionId: String, currentIndex: Int, session: ProxySession) {
        val prevIndex = lastServedBeforeCurrent
        lastServedBeforeCurrent = currentIndex
        val bigJump = prevIndex >= 0 && kotlin.math.abs(currentIndex - prevIndex) > 3

        if (bigJump) {
            Log.i(TAG, "Seek detected: $prevIndex → $currentIndex, restarting eager prefetch")
            synchronized(cacheLock) {
                segmentCache.keys.retainAll { key ->
                    if (!key.startsWith("$sessionId/")) return@retainAll true
                    val idx = key.substringAfterLast("/").toIntOrNull() ?: return@retainAll false
                    kotlin.math.abs(idx - currentIndex) <= EVICT_BEHIND + SEEK_INITIAL_SEGMENTS
                }
                rawBuffer.keys.retainAll { key ->
                    if (!key.startsWith("$sessionId/")) return@retainAll true
                    val idx = key.substringAfterLast("/").toIntOrNull() ?: return@retainAll false
                    kotlin.math.abs(idx - currentIndex) <= EVICT_BEHIND + SEEK_INITIAL_SEGMENTS
                }
            }
            ptsFixer.reset()
            // Start prefetch from currentIndex+1 to avoid competing with TV's CDN fallback for the same segment
            startEagerPrefetch(sessionId, currentIndex + 1)
            return
        }

        evictOldSegments(sessionId, currentIndex)

        val scope = sessionScope ?: return
        aheadJob = scope.launch(Dispatchers.IO) {
            val segments = session.playlist.segments
            val end = minOf(currentIndex + PREFETCH_AHEAD, segments.size - 1)
            val startIdx = currentIndex + 1

            val dlJobs = (0 until CONCURRENT_DOWNLOADS).map { worker ->
                launch(Dispatchers.IO) {
                    var i = startIdx + worker
                    while (i <= end && isActive && activeSessionId == sessionId) {
                        if (rawBuffer.size >= MAX_RAW_BUFFER) break
                        val key = "$sessionId/$i"
                        if (!segmentCache.containsKey(key) && !rawBuffer.containsKey(key)) {
                            val bytes = downloadSegmentWithRetry(segments[i].url)
                            if (bytes != null) rawBuffer.putIfAbsent(key, bytes)
                        }
                        i += CONCURRENT_DOWNLOADS
                    }
                }
            }
            dlJobs.forEach { it.join() }
        }
    }

    private fun evictOldSegments(sessionId: String, currentIndex: Int) {
        synchronized(cacheLock) {
            val evictBefore = currentIndex - EVICT_BEHIND
            if (evictBefore <= 0) return
            segmentCache.keys.retainAll { key ->
                if (!key.startsWith("$sessionId/")) return@retainAll true
                val idx = key.substringAfterLast("/").toIntOrNull() ?: return@retainAll false
                idx >= evictBefore
            }
            rawBuffer.keys.retainAll { key ->
                if (!key.startsWith("$sessionId/")) return@retainAll true
                val idx = key.substringAfterLast("/").toIntOrNull() ?: return@retainAll false
                idx >= evictBefore
            }
        }
    }

    /**
     * Wait up to [timeoutMs] for at least [minCount] segments to be cached.
     * Returns the actual number cached when done (may be > minCount).
     */
    suspend fun prefetchAndAwait(sessionId: String, minCount: Int = INITIAL_MIN_SEGMENTS, timeoutMs: Long = INITIAL_WAIT_MS): Int {
        return withTimeoutOrNull(timeoutMs) {
            while (isActive) {
                val cached = segmentCache.keys.count { it.startsWith("$sessionId/") }
                if (cached >= minCount) return@withTimeoutOrNull cached
                delay(100)
            }
            segmentCache.keys.count { it.startsWith("$sessionId/") }
        } ?: segmentCache.keys.count { it.startsWith("$sessionId/") }
    }

    fun clearSession(sessionId: String) {
        activeSessionId = null
        lastServedBeforeCurrent = -1
        sessionScope?.cancel()
        sessionScope = null
        eagerJob = null
        aheadJob = null
        synchronized(cacheLock) {
            segmentCache.keys.retainAll { !it.startsWith("$sessionId/") }
            rawBuffer.keys.retainAll { !it.startsWith("$sessionId/") }
        }
    }

    fun shutdown() {
        clearSession(activeSessionId ?: "")
        stop()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun serveHealth(): Response {
        return newFixedLengthResponse(Status.OK, "text/plain", "OK")
    }

    private fun servePlaylist(uri: String): Response {
        val sessionId = uri.removePrefix("/playlist/").substringBefore("?").substringBefore(".")
        if (sessionId.isBlank()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Missing sessionId")
        }

        val sessionManager = DlnaProxyService.sessionManager
        if (sessionManager == null) {
            Log.w(TAG, "SessionManager not initialized for playlist request")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "SessionManager not initialized")
        }

        val session = sessionManager.getSession(sessionId)
        if (session == null) {
            Log.w(TAG, "Session not found for playlist: $sessionId")
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Session not found: $sessionId")
        }

        val playlist = session.playlist
        val ip = getLanIp()
        val port = listeningPort

        // Build rewritten M3U8 with proxy URLs
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        val targetDuration = playlist.segments.maxOfOrNull { kotlin.math.ceil(it.duration).toInt() } ?: 10
        sb.appendLine("#EXT-X-TARGETDURATION:$targetDuration")

        if (playlist.initSegmentUri != null) {
            sb.appendLine("#EXT-X-MAP:URI=\"http://$ip:$port/initsegment/$sessionId\"")
        }

        for ((index, segment) in playlist.segments.withIndex()) {
            if (segment.isAfterDiscontinuity) {
                sb.appendLine("#EXT-X-DISCONTINUITY")
            }
            sb.appendLine("#EXTINF:${segment.duration},")
            sb.appendLine("http://$ip:$port/segment/$sessionId/$index")
        }

        sb.appendLine("#EXT-X-ENDLIST")

        val content = sb.toString()
        Log.d(TAG, "Serving rewritten playlist for session $sessionId: ${playlist.segments.size} segments, targetDuration=${targetDuration}s")

        val response = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        response.addHeader("Content-Features.dlna.org", DLNA_PLAYLIST_CONTENT_FEATURES)
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /**
     * Serve the init segment (for fMP4/HLS streams) via proxy.
     */
    private fun serveInitSegment(uri: String): Response {
        val sessionId = uri.removePrefix("/initsegment/").substringBefore("?")
        val sessionManager = DlnaProxyService.sessionManager
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No session manager")

        val session = sessionManager.getSession(sessionId)
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Session not found")

        val initUrl = session.playlist.initSegmentUri
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No init segment")

        val bytes = downloadSegment(initUrl)
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "application/octet-stream", "Failed to download init segment")

        // fMP4 init segments contain ftyp+moov boxes — use video/mp4, not video/mp2t
        val contentType = "video/mp4"
        Log.d(TAG, "Served init segment: ${bytes.size} bytes")
        val response = newFixedLengthResponse(Status.OK, contentType, bytes.inputStream(), bytes.size.toLong())
        response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun serveSegment(uri: String): Response {
        val pathPart = uri.removePrefix("/segment/").substringBefore("?")
        val parts = pathPart.split("/")
        if (parts.size != 2) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Invalid segment URL")
        }

        val sessionId = parts[0]
        val segmentIndex = parts[1].toIntOrNull()
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Invalid segment index")

        val sessionManager = DlnaProxyService.sessionManager
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No session manager")

        val session = sessionManager.getSession(sessionId)
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Session not found: $sessionId")

        if (segmentIndex < 0 || segmentIndex >= session.playlist.segments.size) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Segment index $segmentIndex out of range (0..${session.playlist.segments.size - 1})")
        }

        val segment = session.playlist.segments[segmentIndex]
        session.lastServedSegmentIndex = segmentIndex
        session.lastServedAtMillis = System.currentTimeMillis()
        if (session.playbackStartAtMs == 0L) {
            session.playbackStartAtMs = System.currentTimeMillis()
        }
        var accMs = 0L
        for (i in 0 until segmentIndex) {
            accMs += (session.playlist.segments[i].duration * 1000).toLong()
        }
        session.lastServedSegmentTimeMs = accMs
        Log.d(TAG, "Proxying segment $segmentIndex for session $sessionId")

        triggerAheadPrefetch(sessionId, segmentIndex, session)

        val cacheKey = "$sessionId/$segmentIndex"

        segmentCache[cacheKey]?.let {
            Log.d(TAG, "Cache HIT for segment $segmentIndex")
            return segmentResponse(it)
        }

        val rawExists: Boolean
        synchronized(cacheLock) { rawExists = rawBuffer.containsKey(cacheKey) }
        if (rawExists) {
            Log.d(TAG, "Raw buffer hit for segment $segmentIndex, waiting for fixer")
            var waited = 0L
            while (waited < SPIN_WAIT_TOTAL_MS) {
                segmentCache[cacheKey]?.let { return segmentResponse(it) }
                Thread.sleep(SPIN_WAIT_INTERVAL_MS)
                waited += SPIN_WAIT_INTERVAL_MS
            }
            Log.w(TAG, "Fixer timeout for segment $segmentIndex")
        }

        Log.d(TAG, "Streaming segment $segmentIndex from CDN (cache miss)")
        return streamCdnResponse(segment.url)
            ?: segmentResponse(createEmptyTs()).also { Log.w(TAG, "CDN stream failed for segment $segmentIndex") }
    }

    private fun segmentResponse(bytes: ByteArray): Response {
        val response = newFixedLengthResponse(Status.OK, "video/mpeg", bytes.inputStream(), bytes.size.toLong())
        response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("TimeSeekRange.dlna.org", "npt=0-")
        response.addHeader("X-AvailableSeekRange", "1 npt=0-")
        return response
    }

    private fun createEmptyTs(): ByteArray {
        val nullPacket = ByteArray(188).also { it[0] = 0x47 }
        return ByteArray(188 * 10).also { buf -> repeat(10) { i -> System.arraycopy(nullPacket, 0, buf, i * 188, 188) } }
    }

    private fun streamCdnResponse(url: String): Response? {
        return try {
            val request = Request.Builder().url(url).build()
            val cdnResponse = client.newCall(request).execute()
            if (!cdnResponse.isSuccessful) {
                cdnResponse.close()
                Log.w(TAG, "CDN returned ${cdnResponse.code}")
                return null
            }
            val body = cdnResponse.body ?: return null
            val contentLength = body.contentLength()
            val stream = body.byteStream()
            Log.d(TAG, "CDN streaming: contentLength=$contentLength")
            val response = if (contentLength > 0) {
                newFixedLengthResponse(Status.OK, "video/mpeg", stream, contentLength)
            } else {
                newChunkedResponse(Status.OK, "video/mpeg", stream)
            }
            response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
            response.addHeader("transferMode.dlna.org", "Streaming")
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("TimeSeekRange.dlna.org", "npt=0-")
            response.addHeader("X-AvailableSeekRange", "1 npt=0-")
            response
        } catch (e: Exception) {
            Log.w(TAG, "CDN stream error: ${e.message}")
            null
        }
    }

    private fun downloadSegmentWithRetry(url: String): ByteArray? {
        val firstResult = tryDownload(url)
        if (firstResult?.second != null) return firstResult.second

        val code = firstResult?.first ?: -1
        // Retry on server errors (4xx except 404/410) AND network failures (code -1)
        if (code == -1 || (code >= 400 && code != 404 && code != 410)) {
            Log.w(TAG, "Retrying segment download (status $code): $url")
            Thread.sleep(500)
            val retryResult = tryDownload(url)
            if (retryResult?.second != null) return retryResult.second
            // Second retry for network timeouts
            if (retryResult?.first == -1 || retryResult?.first == null) {
                Log.w(TAG, "Second retry for network error: $url")
                Thread.sleep(1000)
                return tryDownload(url)?.second
            }
            return retryResult?.second
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

    fun getPlaylistUrl(sessionId: String): String {
        val ip = getLanIp()
        val port = listeningPort
        return "http://$ip:$port/playlist/$sessionId.m3u8"
    }

    private val localFileMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getLocalFileUrl(filePath: String): String {
        val id = java.util.UUID.nameUUIDFromBytes(filePath.toByteArray()).toString()
        localFileMap[id] = filePath
        val ip = getLanIp()
        val port = listeningPort
        return "http://$ip:$port/localfile/$id"
    }

    private fun serveLocalFile(uri: String, session: IHTTPSession): Response {
        val id = uri.removePrefix("/localfile/").substringBefore("?")
        val filePath = localFileMap[id]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found")
        val file = java.io.File(filePath)
        if (!file.exists()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File does not exist")
        }

        val fileSize = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
            val dashIdx = rangeSpec.indexOf('-')

            val rangeStart: Long
            val rangeEnd: Long

            if (dashIdx == 0) {
                // Suffix range: bytes=-500 means last 500 bytes
                val suffixLen = rangeSpec.substring(1).toLongOrNull() ?: 0L
                rangeStart = (fileSize - suffixLen).coerceAtLeast(0L)
                rangeEnd = fileSize - 1
            } else {
                rangeStart = rangeSpec.substring(0, dashIdx).toLongOrNull() ?: 0L
                val endStr = rangeSpec.substring(dashIdx + 1)
                rangeEnd = if (endStr.isNotEmpty()) {
                    endStr.toLongOrNull()?.coerceAtMost(fileSize - 1) ?: (fileSize - 1)
                } else {
                    fileSize - 1
                }
            }

            val actualEnd = rangeEnd.coerceAtMost(fileSize - 1)
            val contentLength = actualEnd - rangeStart + 1

            if (rangeStart >= fileSize || contentLength <= 0) {
                val r = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range Not Satisfiable")
                r.addHeader("Content-Range", "bytes */$fileSize")
                return r
            }

            Log.d(TAG, "Serving local file range: $rangeStart-$actualEnd/$fileSize")
            try {
                val fis = java.io.FileInputStream(file)
                fis.channel.position(rangeStart)
                val response = newFixedLengthResponse(Status.PARTIAL_CONTENT, "video/mp4", fis, contentLength)
                response.addHeader("Content-Range", "bytes $rangeStart-$actualEnd/$fileSize")
                response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
                response.addHeader("transferMode.dlna.org", "Streaming")
                response.addHeader("Cache-Control", "no-cache")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("X-AvailableSeekRange", "1 npt=0-")
                return response
            } catch (e: Exception) {
                Log.w(TAG, "Failed to serve local file range: ${e.message}")
                return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Internal Server Error")
            }
        }

        Log.d(TAG, "Serving local file: ${file.name} ($fileSize bytes)")
        val response = newFixedLengthResponse(Status.OK, "video/mp4", file.inputStream(), fileSize)
        response.addHeader("Content-Features.dlna.org", DLNA_CONTENT_FEATURES)
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("X-AvailableSeekRange", "1 npt=0-")
        return response
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

}
