package com.ysxq.app.data.download

import android.content.Context
import android.os.Environment
import com.ysxq.app.data.proxy.M3u8Segment
import com.ysxq.app.data.proxy.parseM3u8
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

object DownloadManager {

    private const val MAX_CONCURRENT = 16
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val SPEED_SAMPLES = 6
    private const val BUFFER_SIZE = 131072 // 128KB
    private const val MAX_RETRIES = 3

    private lateinit var appContext: Context
    private lateinit var store: DownloadStore
    private var crashRecovered = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val cancelledFlags = ConcurrentHashMap<String, Boolean>()
    private val progressSnapshots = ConcurrentHashMap<String, DownloadProgress>()

    private val _progressFlow = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow.asSharedFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(MAX_CONCURRENT, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Referer", "https://cj.lziapi.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    fun init(context: Context, downloadStore: DownloadStore) {
        appContext = context.applicationContext
        store = downloadStore
        // Only run crash recovery once per process lifetime
        if (crashRecovered) return
        crashRecovered = true
        scope.launch {
            store.tasks.first()
                .filter { it.status == DownloadStatus.DOWNLOADING.name }
                .forEach { task ->
                    store.updateTask(task.id) {
                        it.copy(status = DownloadStatus.PAUSED.name, speed = 0)
                    }
                }
        }
    }

    fun getDownloadDir(): File =
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ysxq")

    fun getTaskProgress(taskId: String): DownloadProgress? = progressSnapshots[taskId]

    fun isDownloading(taskId: String): Boolean = activeDownloads.containsKey(taskId)

    fun activeTaskCount(): Int = activeDownloads.size

    suspend fun startDownload(task: DownloadTask) {
        if (activeDownloads.containsKey(task.id)) return

        cancelledFlags[task.id] = false

        val existing = store.tasks.first().find { it.id == task.id }
        if (existing == null) {
            store.addTask(task)
        }
        store.updateTask(task.id) { it.copy(status = DownloadStatus.DOWNLOADING.name) }

        DownloadService.start(appContext, task.id)

        val job = scope.launch {
            try {
                executeDownload(existing ?: task)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (!cancelledFlags.getOrDefault(task.id, false)) {
                        val snapshot = progressSnapshots[task.id]
                        store.updateTask(task.id) {
                            it.copy(
                                status = DownloadStatus.PAUSED.name,
                                speed = 0,
                                progress = snapshot?.progress ?: it.progress,
                                downloadedBytes = snapshot?.bytesDownloaded ?: it.downloadedBytes
                            )
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    store.updateTask(task.id) {
                        it.copy(
                            status = DownloadStatus.FAILED.name,
                            errorMsg = e.message ?: "Unknown error",
                            speed = 0
                        )
                    }
                }
            } finally {
                activeDownloads.remove(task.id)
                progressSnapshots.remove(task.id)
                checkStopService()
            }
        }
        activeDownloads[task.id] = job
    }

    fun pauseDownload(taskId: String) {
        cancelledFlags.remove(taskId)
        activeDownloads[taskId]?.cancel()
    }

    fun resumeDownload(taskId: String) {
        scope.launch {
            val tasks = store.tasks.first()
            val task = tasks.find { it.id == taskId } ?: return@launch
            if (task.status != DownloadStatus.PAUSED.name &&
                task.status != DownloadStatus.FAILED.name
            ) return@launch
            startDownload(task)
        }
    }

    fun cancelDownload(taskId: String) {
        cancelledFlags[taskId] = true
        activeDownloads[taskId]?.cancel()
        activeDownloads.remove(taskId)
        scope.launch {
            val tasks = store.tasks.first()
            val task = tasks.find { it.id == taskId } ?: return@launch
            getTempDir(taskId, task.savePath).deleteRecursively()
            store.deleteTask(taskId)
            checkStopService()
        }
    }

    private fun checkStopService() {
        if (activeDownloads.isEmpty()) {
            DownloadService.stop(appContext)
        }
    }

    private suspend fun executeDownload(task: DownloadTask) {
        val tempDir = getTempDir(task.id, task.savePath)

        // Clean up .tmp partial files from interrupted downloads
        if (tempDir.exists()) {
            tempDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        }

        val hasExistingSegments = tempDir.exists() &&
            tempDir.listFiles()?.any { it.name.endsWith(".ts") } == true

        val finalContent: String
        val finalBaseUrl: String
        val segments: List<M3u8Segment>
        var masterBandwidth = 0L

        if (hasExistingSegments && task.resolvedUrl != null) {
            finalBaseUrl = extractBaseUrl(task.resolvedUrl)
            finalContent = fetchM3u8Content(task.resolvedUrl)
                ?: throw Exception("无法获取播放列表")
            val playlist = parseM3u8(finalContent, finalBaseUrl)
                ?: throw Exception("无法解析播放列表")
            segments = playlist.segments
        } else {
            val m3u8Content = fetchM3u8Content(task.episodeUrl)
                ?: throw Exception("无法获取 M3U8 播放列表")

            val baseUrl = extractBaseUrl(task.episodeUrl)
            val mediaPlaylistUrl = resolveMediaPlaylist(m3u8Content, baseUrl)

            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                val variants = M3u8QualityParser.parseMasterPlaylist(m3u8Content, baseUrl)
                masterBandwidth = M3u8QualityParser.selectBestQuality(variants).bandwidth
            }

            if (mediaPlaylistUrl != null) {
                finalBaseUrl = extractBaseUrl(mediaPlaylistUrl)
                finalContent = fetchM3u8Content(mediaPlaylistUrl)
                    ?: throw Exception("无法获取媒体播放列表")
            } else {
                finalContent = m3u8Content
                finalBaseUrl = baseUrl
            }

            val playlist = parseM3u8(finalContent, finalBaseUrl)
                ?: throw Exception("无法解析播放列表")

            segments = playlist.segments
            store.updateTask(task.id) {
                it.copy(
                    resolvedUrl = mediaPlaylistUrl ?: task.episodeUrl,
                    segmentCount = segments.size
                )
            }
        }

        if (segments.isEmpty()) throw Exception("播放列表中没有分片")

        tempDir.mkdirs()

        val totalSegments = segments.size
        val downloadedBytes = AtomicLong(0)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val segmentSizes = ConcurrentHashMap<Int, Long>()

        // Count existing segments for resume
        for (i in segments.indices) {
            val f = File(tempDir, segName(i))
            if (f.exists()) {
                val sz = f.length()
                downloadedBytes.addAndGet(sz)
                completedCount.incrementAndGet()
                segmentSizes[i] = sz
            }
        }

        // Get exact total size by HEAD-requesting ALL segments in parallel
        val totalBytesExact = probeAllSegmentSizes(segments, segmentSizes, totalSegments)
        val totalEstimate = AtomicLong(totalBytesExact)
        store.updateTask(task.id) { it.copy(totalBytes = totalBytesExact) }

        val progressJob = launchTracker(
            task.id, downloadedBytes, totalEstimate,
            completedCount, totalSegments
        )

        try {
            downloadAllSegments(segments, tempDir, downloadedBytes, completedCount, segmentSizes)

            progressJob.cancel()
            currentCoroutineContext().ensureActive()

            // Validate segment count matches expectation
            val actualCount = tempDir.listFiles()?.count { it.name.endsWith(".ts") } ?: 0
            if (actualCount != totalSegments) {
                throw Exception("分片数量不匹配：期望 $totalSegments，实际下载 $actualCount")
            }

            mergeSegments(tempDir, task.savePath)
            tempDir.deleteRecursively()

            val finalSize = File(task.savePath).length()
            val total = totalEstimate.get()
            progressSnapshots[task.id] = DownloadProgress(
                taskId = task.id, bytesDownloaded = total,
                totalBytes = total, speed = 0, progress = 1f
            )
            withContext(NonCancellable) {
                store.updateTask(task.id) {
                    it.copy(
                        status = DownloadStatus.COMPLETED.name,
                        progress = 1f, downloadedBytes = total,
                        totalBytes = total, speed = 0
                    )
                }
            }
        } catch (e: CancellationException) {
            progressJob.cancel()
            throw e
        } catch (e: Exception) {
            progressJob.cancel()
            throw e
        }
    }

    /**
     * Get exact total file size by sending parallel HEAD requests to all segments.
     * Returns sum of Content-Length headers. Falls back to average if some fail.
     */
    private suspend fun probeAllSegmentSizes(
        segments: List<M3u8Segment>,
        existingSizes: ConcurrentHashMap<Int, Long>,
        totalSegments: Int
    ): Long {
        // If all segments already downloaded, sum is exact
        if (existingSizes.size == totalSegments) {
            return existingSizes.values.sum()
        }

        val semaphore = Semaphore(MAX_CONCURRENT)
        val headSizes = ConcurrentHashMap<Int, Long>()

        coroutineScope {
            segments.mapIndexedNotNull { index, segment ->
                // Skip segments we already have on disk
                if (existingSizes.containsKey(index)) return@mapIndexedNotNull null
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val req = Request.Builder().url(segment.url).head().build()
                        downloadClient.newCall(req).execute().use { resp ->
                            val cl = resp.header("Content-Length")?.toLongOrNull()
                            if (cl != null && cl > 0) headSizes[index] = cl
                        }
                    } catch (_: Exception) {}
                    finally { semaphore.release() }
                }
            }.forEach { it?.await() }
        }

        // Merge existing sizes + HEAD sizes
        val allKnown = existingSizes.values.sum() + headSizes.values.sum()
        val allKnownCount = existingSizes.size + headSizes.size

        if (allKnownCount == totalSegments && allKnownCount > 0) {
            // Every segment has a known size — exact total
            return allKnown
        }

        // Some segments didn't return Content-Length — extrapolate
        if (allKnownCount > 0) {
            return allKnown * totalSegments / allKnownCount
        }

        // Total fallback
        val dur = segments.sumOf { it.duration }
        return if (dur > 0) (dur * 33_333L).toLong() else totalSegments * 200_000L
    }

    private fun resolveMediaPlaylist(content: String, baseUrl: String): String? {
        if (!content.contains("#EXT-X-STREAM-INF")) return null
        val variants = M3u8QualityParser.parseMasterPlaylist(content, baseUrl)
        return M3u8QualityParser.selectBestQuality(variants).url
    }

    private suspend fun downloadAllSegments(
        segments: List<M3u8Segment>,
        tempDir: File,
        downloadedBytes: AtomicLong,
        completedCount: java.util.concurrent.atomic.AtomicInteger,
        segmentSizes: ConcurrentHashMap<Int, Long>
    ) {
        val semaphore = Semaphore(MAX_CONCURRENT)
        coroutineScope {
            segments.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val segFile = File(tempDir, segName(index))
                    if (segFile.exists()) return@async

                    semaphore.acquire()
                    try {
                        currentCoroutineContext().ensureActive()
                        val bytes = downloadWithRetry(segment, segFile)
                        downloadedBytes.addAndGet(bytes)
                        completedCount.incrementAndGet()
                        segmentSizes[index] = bytes
                    } finally {
                        semaphore.release()
                    }
                }
            }.forEach { it.await() }
        }
    }

    private suspend fun downloadWithRetry(segment: M3u8Segment, outputFile: File): Long {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return withContext(Dispatchers.IO) { downloadOnce(segment, outputFile) }
            } catch (e: Exception) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) delay(200L * (attempt + 1))
            }
        }
        throw lastError ?: Exception("Download failed for ${segment.url}")
    }

    private fun downloadOnce(segment: M3u8Segment, outputFile: File): Long {
        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        try {
            val request = Request.Builder().url(segment.url).build()
            downloadClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input ->
                    tmpFile.outputStream().buffered(BUFFER_SIZE).use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                        }
                        output.flush()
                    }
                } ?: throw Exception("Empty response body")
            }
            // Atomic rename: segment only visible when fully written
            if (!tmpFile.renameTo(outputFile)) {
                tmpFile.copyTo(outputFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
        return outputFile.length()
    }

    private fun mergeSegments(tempDir: File, outputPath: String) {
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()

        val files = tempDir.listFiles()
            ?.filter { it.name.endsWith(".ts") }
            ?.sortedBy { it.name }
            ?: throw Exception("No segment files found")

        outFile.outputStream().buffered(BUFFER_SIZE).use { out ->
            val buf = ByteArray(BUFFER_SIZE)
            for (f in files) {
                f.inputStream().buffered(BUFFER_SIZE).use { inn ->
                    var n: Int
                    while (inn.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }
            out.flush()
        }
    }

    private fun launchTracker(
        taskId: String,
        downloadedBytes: AtomicLong,
        totalBytes: AtomicLong,
        completedCount: java.util.concurrent.atomic.AtomicInteger,
        totalSegments: Int
    ): Job {
        return scope.launch {
            var counter = 0
            val samples = ArrayDeque<Long>(SPEED_SAMPLES)
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)

                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1)
                val curBytes = downloadedBytes.get()
                val delta = (curBytes - lastBytes).coerceAtLeast(0)
                val instant = delta * 1000L / elapsed

                samples.addLast(instant)
                if (samples.size > SPEED_SAMPLES) samples.removeFirst()
                val speed = if (samples.isNotEmpty()) samples.sum() / samples.size else 0L

                lastBytes = curBytes
                lastTime = now

                val est = totalBytes.get()
                val progress = if (est > 0) (curBytes.toFloat() / est).coerceIn(0f, 0.99f) else 0f

                val snap = DownloadProgress(
                    taskId = taskId,
                    bytesDownloaded = curBytes,
                    totalBytes = est,
                    speed = speed,
                    progress = progress
                )
                progressSnapshots[taskId] = snap
                _progressFlow.tryEmit(snap)

                counter++
                if (counter >= 4) {
                    counter = 0
                    store.updateTask(taskId) {
                        it.copy(progress = progress, downloadedBytes = curBytes, totalBytes = est, speed = speed)
                    }
                }
            }
        }
    }

    private fun fetchM3u8Content(url: String): String? {
        return try {
            downloadClient.newCall(Request.Builder().url(url).build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        } catch (_: Exception) { null }
    }

    private fun getTempDir(taskId: String, savePath: String): File {
        return File(File(savePath).parentFile ?: getDownloadDir(), ".tmp/$taskId")
    }

    private fun segName(index: Int): String = String.format("%06d.ts", index)

    private fun extractBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.path ?: ""
            val slash = path.lastIndexOf('/')
            if (slash > 0) {
                URI(uri.scheme, uri.authority, path.substring(0, slash + 1), uri.query, uri.fragment).toString()
            } else url
        } catch (_: Exception) { url }
    }
}
