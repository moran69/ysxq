package com.momo.app.ui.screens

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import android.net.wifi.WifiManager
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.yinnho.upnpcast.DLNACast
import com.momo.app.R
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import com.momo.app.data.local.WatchHistoryEntry
import com.momo.app.data.local.favoritesStore
import com.momo.app.data.local.userPreferences
import com.momo.app.data.local.toFavoriteItem
import com.momo.app.data.local.watchHistoryStore
import com.momo.app.data.sync.FavoritesSyncRepository
import com.momo.app.data.sync.WatchHistorySyncRepository
import com.momo.app.data.proxy.DirectDlnaCaster
import com.momo.app.data.proxy.DlnaProxyServer
import com.momo.app.data.proxy.DlnaProxyService
import com.momo.app.ui.components.*
import com.momo.app.ui.danmaku.*
import com.momo.app.ui.theme.*
import com.momo.app.data.auth.AuthRepository
import com.momo.app.data.danmaku.DmkuApi

import com.momo.app.viewmodel.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

private suspend fun searchDlnaDevices(context: Context): List<DLNACast.Device> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val multicastLock = wifiManager.createMulticastLock("dlna-discovery")
    multicastLock.acquire()
    return try {
        DLNACast.init(context.applicationContext)
        DLNACast.search(timeout = 5000)
    } finally {
        multicastLock.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    videoId: Int,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel(),
    // 外部片源注入（速搜片源等）：提供时直接注入，不请求自家网络
    externalVideo: VideoItem? = null,
    externalSources: List<VideoSource>? = null
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isProgressSeeking by remember { mutableStateOf(false) }
    // 当前播放倍速（弹幕滚动速度同步用）
    var playbackSpeedFactor by remember { mutableFloatStateOf(1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    // 手动退出全屏后置位：抑制"横屏自动进全屏"，直到设备真正回到竖屏一次（避免退出后被拉回横屏反复横跳）
    var suppressAutoFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showCastSheet by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var selectedDownloadEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }


    var showFullscreenEpisodes by remember { mutableStateOf(false) }
    // 弹幕系统状态
    var danmakuList by remember { mutableStateOf<List<com.momo.app.ui.danmaku.DanmakuItem>>(listOf()) }
    var danmakuConfig by remember { mutableStateOf(com.momo.app.ui.danmaku.DanmakuConfig()) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var hasMediaLoaded by remember { mutableStateOf(false) }
    var hasInitialHistorySaved by remember { mutableStateOf(false) }
    var isCasting by remember { mutableStateOf(false) }
    var userRequestedPlay by remember { mutableStateOf(false) }
    var wasPlayingBeforeStop by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    var showCastExitDialog by remember { mutableStateOf(false) }
    var stopCastingCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var lastCastPositionMs by remember { mutableStateOf(0L) }
    var wasPlayingBeforeCast by remember { mutableStateOf(false) }
    var isLongPressSpeed by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(1f) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var deviceOrientation by remember { mutableIntStateOf(context.resources.configuration.orientation) }
    var castDeviceName by remember { mutableStateOf<String?>(null) }
    var castProgress by remember { mutableStateOf("00:00 / 00:00") }
    var castPlaybackState by remember { mutableStateOf("") }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var castDisconnectReason by remember { mutableStateOf("") }
    var showStopCastConfirm by remember { mutableStateOf(false) }
    var castCommandHandler by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var pendingCastEpisodeIndex by remember { mutableStateOf<Int?>(null) }
    // 投屏进度（毫秒）与拖动进度处理器（TV 端 REL_TIME seek）
    var castPositionMs by remember { mutableLongStateOf(0L) }
    var castDurationMs by remember { mutableLongStateOf(0L) }
    var castSeekHandler by remember { mutableStateOf<((Long) -> Unit)?>(null) }

    val favoritesStore = remember { context.favoritesStore() }
    val watchHistoryStore = remember { context.watchHistoryStore() }
    val historySyncRepo = remember { WatchHistorySyncRepository(watchHistoryStore) }
    val favoritesSyncRepo = remember { FavoritesSyncRepository(favoritesStore) }
    val isFavorite by remember(videoId) { favoritesStore.isFavoriteFlow(videoId) }.collectAsState(initial = false)
    val historyEntry by remember(videoId) { watchHistoryStore.getEntryFlow(videoId) }.collectAsState(initial = null)
    var showResumePrompt by remember { mutableStateOf(false) }
    LaunchedEffect(showResumePrompt) {
        if (showResumePrompt) {
            delay(5000)
            showResumePrompt = false
        }
    }
    var hasResumedForThisSession by remember { mutableStateOf(false) }
    var pendingSeekPosition by remember { mutableStateOf<Long?>(null) }

    val scope = rememberCoroutineScope()

    // 当前是否 NBY 加密线路(用于播放失败提示)
    var isNbyLine by remember { mutableStateOf(false) }
    // 最近一次 prepare 的原始地址（播放失败重试时重新解析并 prepare）
    var lastPreparedUrl by remember { mutableStateOf<String?>(null) }

    // 网络数据源工厂提升到 remember 外，LaunchedEffect 里给 NBY 解密 URL 显式构建 HlsMediaSource 用
    val httpDataSourceFactory = remember {
        val streamClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        OkHttpDataSource.Factory(streamClient)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Referer" to "http://161.118.252.183:8899/"
            ))
    }
    val dataSourceFactory = remember { DefaultDataSource.Factory(context, httpDataSourceFactory) }

    val exoPlayer = remember {
        // Smart buffer: fast start (1.5s) + stable playback (15s min, 50s max)
        // Rebuffer only needs 2s (was 3s), backBuffer 10s for instant seek-back
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,    // minBufferMs: 15s — enough for stable playback without over-buffering
                50_000,    // maxBufferMs: 50s — cap memory usage (was 100s)
                1_500,     // bufferForPlaybackMs: 1.5s — near-instant start (was 8s!)
                2_000      // bufferForPlaybackAfterRebufferMs: 2s — quick recovery after stall
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)  // buffer by duration, not bytes
            .setBackBuffer(10_000, false)  // keep 10s of played content for instant seek-back
            .build()

        // Renderers with extension decoder priority + fallback
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            )

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.EXACT)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
                setWakeMode(C.WAKE_MODE_LOCAL)
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus= */ true
                )
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                        com.momo.app.ui.PipHelper.updateState(
                            hasVideo = hasMediaLoaded,
                            playing = playing,
                            width = this@apply.videoSize.width,
                            height = this@apply.videoSize.height
                        )
                    }
                    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                        playbackSpeedFactor = playbackParameters.speed
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            hasMediaLoaded = true
                            com.momo.app.ui.PipHelper.updateState(hasVideo = true, playing = isPlaying, width = this@apply.videoSize.width, height = this@apply.videoSize.height)
                            // Trigger initial history save via flag
                            if (!hasInitialHistorySaved) {
                                hasInitialHistorySaved = true
                            }
                            val shouldAutoPlay = userRequestedPlay
                            pendingSeekPosition?.let { pos ->
                                pendingSeekPosition = null
                                if (pos > 1000) {
                                    this@apply.seekTo(pos)
                                }
                            }
                            if (shouldAutoPlay) {
                                this@apply.playWhenReady = true
                                userRequestedPlay = false
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            // Auto-play next episode
                            val s = viewModel.state.value
                            val currentEp = s.currentEpisodeIndex
                            val totalEps = s.sources.getOrNull(s.currentSourceIndex)?.episodes?.size ?: 0
                            if (currentEp >= 0 && currentEp + 1 < totalEps) {
                                viewModel.selectEpisode(currentEp + 1)
                                userRequestedPlay = true
                            }
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        // NBY 解密线路 ts 分片受源站 CDN 防盗链保护(302→占位图), 提示更明确
                        playerError = if (isNbyLine) "该线路源站受限，请切换其他线路" else (error.message ?: "播放出错")
                    }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        com.momo.app.ui.PipHelper.updateState(
                            hasVideo = true,
                            playing = isPlaying,
                            width = videoSize.width,
                            height = videoSize.height
                        )
                    }
                })
            }
    }

    // Restore last playback speed from preferences
    LaunchedEffect(Unit) {
        val prefs = context.userPreferences()
        prefs.playbackSpeed.collect { savedSpeed ->
            if (savedSpeed != 1.0f && savedSpeed > 0f) {
                exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(savedSpeed))
            }
        }
    }
    val currentUrl = viewModel.getCurrentEpisodeUrl()
    // 统一的播放准备入口（首次加载与播放失败重试共用）
    fun prepareAndPlay(originalUrl: String, playUrl: String) {
        if (playUrl.startsWith("NBY-")) {
            // 解密失败: 提示并放弃该线路
            playerError = "该线路解密失败，请尝试切换其他线路"
            isBuffering = false
        } else if (playUrl.startsWith("YJ-")) {
            // 看剧AI token 解析失败
            playerError = "播放线路解析失败，请尝试切换其他线路"
            isBuffering = false
        } else if (playUrl != originalUrl) {
            // NBY/看剧AI 解析返回的 URL 无 .m3u8 后缀, DefaultMediaSourceFactory
            // 按 MIME 推断成 Progressive 会失败, 显式构建 HlsMediaSource
            val mediaSource = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(playUrl))
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(playUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
        }
    }
    LaunchedEffect(currentUrl) {
        isNbyLine = currentUrl?.startsWith("NBY-") == true
        if (currentUrl != null) {
            playerError = null
            isBuffering = true
            hasMediaLoaded = false
            hasInitialHistorySaved = false
            pendingSeekPosition = null
            userRequestedPlay = false
            lastPreparedUrl = currentUrl
            // NBY 加密地址懒解密(直链原样返回)
            val playUrl = viewModel.resolvePlayUrl(currentUrl)
            android.util.Log.d("DetailPlay", "playUrl=${playUrl.take(70)} changed=${playUrl != currentUrl}")
            prepareAndPlay(currentUrl, playUrl)
        }
    }

    LaunchedEffect(historyEntry, state.video, hasResumedForThisSession) {
        val entry = historyEntry
        // Only process resume logic on initial load, not on subsequent history updates
        if (state.video == null || hasResumedForThisSession) return@LaunchedEffect
        hasResumedForThisSession = true
        if (entry == null) return@LaunchedEffect
        if (entry.episodeIndex >= 0 && state.sources.isNotEmpty()) {
            val sourceIdx = state.currentSourceIndex
            val episodes = state.sources.getOrNull(sourceIdx)?.episodes
            if (episodes != null && entry.episodeIndex < episodes.size) {
                viewModel.selectEpisode(entry.episodeIndex)
                // If resuming a different episode, set pending seek so player auto-seeks when ready
                if (entry.episodeIndex != state.currentEpisodeIndex && entry.positionMs > 1000) {
                    pendingSeekPosition = entry.positionMs
                }
            }
        }
        if (entry.positionMs > 10000) {
            showResumePrompt = true
        }
        hasResumedForThisSession = true
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (!hasMediaLoaded) {
                userRequestedPlay = true
            } else {
                exoPlayer.play()
            }
        }
    }

    /**
     * 手动退出全屏（返回键/退出按钮/投屏面板）。
     * 关键：保持 SENSOR 但置位 suppressAutoFullscreen——若此刻手机还横着，
     * 不会被自动全屏逻辑立刻拉回去；等真正竖屏一次后才恢复自动全屏。
     */
    fun exitFullscreen() {
        isFullscreen = false
        isLocked = false
        suppressAutoFullscreen = true
        activity?.let { act ->
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            WindowCompat.getInsetsController(act.window, act.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun saveWatchProgress() {
        val video = state.video ?: return
        if (!AuthRepository.isLoggedIn) return
        val episode = state.sources.getOrNull(state.currentSourceIndex)
            ?.episodes?.getOrNull(state.currentEpisodeIndex)
        val positionMs = if (exoPlayer.contentDuration > 0) exoPlayer.currentPosition else 0L
        val durationMs = if (exoPlayer.contentDuration > 0) exoPlayer.contentDuration else 0L
        scope.launch {
            val entry = WatchHistoryEntry(
                videoId = video.id,
                videoName = video.name,
                pic = video.pic,
                typeName = video.typeName,
                remarks = video.remarks,
                episodeIndex = state.currentEpisodeIndex,
                episodeName = episode?.name ?: "",
                positionMs = positionMs,
                durationMs = durationMs
            )
            watchHistoryStore.saveProgress(entry)
            historySyncRepo.upsertToCloud(entry)
        }
    }

    // Save history shortly after media becomes ready (delay allows pending seeks to complete)
    LaunchedEffect(hasInitialHistorySaved) {
        if (hasInitialHistorySaved && state.video != null && !isCasting) {
            delay(2000)
            saveWatchProgress()
        }
    }

    // Periodically save watch progress (every 15s) so history survives app kill
    LaunchedEffect(state.video?.id, state.currentEpisodeIndex, isCasting) {
        if (state.video == null || isCasting) return@LaunchedEffect
        while (true) {
            delay(15_000)
            if (exoPlayer.isPlaying || exoPlayer.contentDuration > 0) {
                saveWatchProgress()
            }
        }
    }

    // 弹幕位置同步（每 200ms 更新一次）
    LaunchedEffect(exoPlayer, isFullscreen) {
        while (true) {
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(200)
        }
    }

    // 视频加载时拉取真实弹幕（dmku.hls.one 公益弹幕库，按剧名+集数匹配爱奇艺源）
    LaunchedEffect(state.video?.id, state.currentEpisodeIndex) {
        if (state.video != null) {
            val name = state.video?.name ?: ""
            val episodeNo = state.currentEpisodeIndex + 1
            val entries = DmkuApi.resolveDanmaku(name, episodeNo)
            danmakuList = if (entries.isNotEmpty()) {
                entries.map { e ->
                    com.momo.app.ui.danmaku.DanmakuItem(
                        text = e.text,
                        time = e.timeMs,
                        color = e.color,
                        type = when (e.type) {
                            5 -> com.momo.app.ui.danmaku.DanmakuType.TOP
                            4 -> com.momo.app.ui.danmaku.DanmakuType.BOTTOM
                            else -> com.momo.app.ui.danmaku.DanmakuType.SCROLL
                        }
                    )
                }
            } else {
                emptyList()
            }
            // 预取下一集弹幕（resolveDanmaku 有缓存，切集时秒显）
            val totalEps = state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 0
            if (episodeNo + 1 <= totalEps) {
                try { DmkuApi.resolveDanmaku(name, episodeNo + 1) } catch (_: Exception) { }
            }
        }
    }

    DisposableEffect(context) {
        val callback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                deviceOrientation = newConfig.orientation
            }
            override fun onLowMemory() {}
        }
        context.registerComponentCallbacks(callback)
        onDispose { context.unregisterComponentCallbacks(callback) }
    }

    LaunchedEffect(hasMediaLoaded, isCasting, isFullscreen) {
        if (hasMediaLoaded || isCasting) {
            if (!isFullscreen) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }
    }

    LaunchedEffect(deviceOrientation) {
        if (!hasMediaLoaded && !isCasting) return@LaunchedEffect
        when (deviceOrientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // 手动退出全屏后（还没竖屏过）不再自动进全屏，避免退出被拉回、来回横跳
                if (!suppressAutoFullscreen && !isFullscreen) {
                    isFullscreen = true
                    isLocked = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    activity?.let { act ->
                        val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                // 真正回到竖屏一次后，恢复横屏自动全屏能力
                suppressAutoFullscreen = false
                if (isFullscreen) {
                    isFullscreen = false
                    isLocked = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    activity?.let { act ->
                        WindowCompat.getInsetsController(act.window, act.window.decorView)
                            .show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        exoPlayer.volume = 1f
        onDispose {
            isCasting = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
            com.momo.app.ui.PipHelper.reset()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.let { act ->
                act.window.attributes = act.window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(context, isCasting) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (!isCasting) exoPlayer.pause()
                    }
                    Intent.ACTION_USER_PRESENT -> { }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val lifecycleOwner = ProcessLifecycleOwner.get()
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!isCasting && !com.momo.app.ui.PipHelper.isInPipMode) {
                        wasPlayingBeforeStop = exoPlayer.isPlaying
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (!isCasting && wasPlayingBeforeStop) {
                        exoPlayer.play()
                        wasPlayingBeforeStop = false
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        if (isLocked) return@BackHandler
        if (isFullscreen) {
            exitFullscreen()
        } else if (isCasting) {
            showCastExitDialog = true
        } else {
            saveWatchProgress()
            playerViewRef?.player = null
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            onBack()
        }
    }

    if (showCastExitDialog) {
        AlertDialog(
            onDismissRequest = { showCastExitDialog = false },
            title = { Text("退出投屏", color = TextPrimary) },
            text = { Text("投屏正在进行中，确定要退出吗？", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showCastExitDialog = false
                    stopCastingCallback?.invoke()
                    saveWatchProgress()
                    playerViewRef?.player = null
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    onBack()
                }) {
                    Text("确定", color = SakuraPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCastExitDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            title = { Text("提示", color = TextPrimary) },
            text = { Text("请先登录后使用此功能", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showLoginDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                ) {
                    Text("好的", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoginDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = {
                showDisconnectDialog = false
            },
            title = { Text("投屏已断开", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("与设备的连接已断开，请检查网络后重试", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    showCastSheet = true
                }) {
                    Text("重新投屏", color = SakuraPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                }) {
                    Text("关闭", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isCasting) {
                stopCastingCallback?.invoke()
            }
        }
    }

    fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            isFullscreen = true
            suppressAutoFullscreen = false
            activity?.let { act ->
                val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) { delay(3000); showControls = false }
    }

    if (showSpeedDialog) {
        SpeedSelectionDialog(
            currentSpeed = exoPlayer.playbackParameters.speed,
            onSpeedSelected = { speed ->
                exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed))
                scope.launch {
                    val prefs = context.userPreferences()
                    prefs.savePlaybackSpeed(speed)
                }
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showFullscreenEpisodes) {
        val currentSource = state.sources.getOrNull(state.currentSourceIndex)
        if (currentSource != null) {
            Dialog(onDismissRequest = { showFullscreenEpisodes = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.9f)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("选集", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.heightIn(max = 400.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(currentSource.episodes) { index, episode ->
                                val selected = index == state.currentEpisodeIndex
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) SakuraPrimary else Color.White.copy(alpha = 0.15f),
                                    modifier = Modifier.clickable {
                                        if (isCasting) {
                                            pendingCastEpisodeIndex = index
                                        }
                                        viewModel.selectEpisode(index)
                                        showFullscreenEpisodes = false
                                    }
                                ) {
                                    Text(
                                        episode.name,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.8f),
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCastSheet) {
        val castUrl = viewModel.getCurrentEpisodeUrl()
        val videoName = state.video?.name ?: ""
        CastDeviceSheet(
            videoUrl = castUrl,
            videoTitle = videoName,
            resumePositionMs = exoPlayer.currentPosition,
            onDismiss = { showCastSheet = false },
            onCastStateChanged = { casting, deviceName, progress ->
                isCasting = casting
                castDeviceName = deviceName
                if (casting && progress.isNotEmpty()) castProgress = progress
                if (casting) {
                    if (isFullscreen) exitFullscreen()
                    wasPlayingBeforeCast = exoPlayer.isPlaying
                    if (exoPlayer.isPlaying) exoPlayer.pause()
                } else {
                    showCastSheet = false
                    castCommandHandler = null
                    castSeekHandler = null
                    if (lastCastPositionMs > 0 && exoPlayer.contentDuration > 0) {
                        exoPlayer.seekTo(lastCastPositionMs)
                    }
                    // 投屏前在播才恢复播放，避免投完屏回来突然自动播
                    if (wasPlayingBeforeCast) exoPlayer.play()
                    lastCastPositionMs = 0L
                }
            },
            onCastProgressUpdate = { currentMs, totalMs, playbackState ->
                castPositionMs = currentMs
                castDurationMs = totalMs
                castProgress = "${formatMs(currentMs)} / ${formatMs(totalMs)}"
                castPlaybackState = playbackState
            },
            onCastSeekRegistered = { handler -> castSeekHandler = handler },
            onCastDisconnected = {
                showDisconnectDialog = true
            },
            onCastCommandRegistered = { handler -> castCommandHandler = handler },
            pendingEpisodeIndex = pendingCastEpisodeIndex,
            onPendingEpisodeConsumed = { pendingCastEpisodeIndex = null },
            onNextEpisode = { viewModel.selectEpisode(viewModel.state.value.currentEpisodeIndex + 1) },
            onPrevEpisode = { viewModel.selectEpisode(viewModel.state.value.currentEpisodeIndex - 1) },
            currentEpisodeIndex = state.currentEpisodeIndex,
            totalEpisodes = state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 0,
            getEpisodeUrl = { viewModel.getCurrentEpisodeUrl() },
            resolveUrl = { viewModel.resolvePlayUrl(it) },
            onStopCastingRegistered = { callback -> stopCastingCallback = callback },
            onSaveCastingProgress = { currentMs, totalMs ->
                lastCastPositionMs = currentMs
                val video = state.video ?: return@CastDeviceSheet
                val episode = state.sources.getOrNull(state.currentSourceIndex)
                    ?.episodes?.getOrNull(state.currentEpisodeIndex)
                scope.launch {
                    val entry = WatchHistoryEntry(
                        videoId = video.id,
                        videoName = video.name,
                        pic = video.pic,
                        typeName = video.typeName,
                        remarks = video.remarks,
                        episodeIndex = state.currentEpisodeIndex,
                        episodeName = episode?.name ?: "",
                        positionMs = currentMs,
                        durationMs = totalMs
                    )
                    watchHistoryStore.saveProgress(entry)
                    historySyncRepo.upsertToCloud(entry)
                }
            }
        )
    }

    // 下载选集弹窗
    if (showDownloadDialog && state.video != null) {
        val video = state.video!!
        val currentEpisodes = state.sources.getOrNull(state.currentSourceIndex)?.episodes ?: emptyList()
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("选择下载集数", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 300.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(currentEpisodes) { index, episode ->
                            val isSelected = index in selectedDownloadEpisodes
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) SakuraPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                border = if (isSelected) ButtonDefaults.outlinedButtonBorder else null,
                                modifier = Modifier.clickable {
                                    selectedDownloadEpisodes = if (isSelected) {
                                        selectedDownloadEpisodes - index
                                    } else {
                                        selectedDownloadEpisodes + index
                                    }
                                }
                            ) {
                                Text(
                                    episode.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    color = if (isSelected) SakuraPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedDownloadEpisodes = if (selectedDownloadEpisodes.size == currentEpisodes.size) emptySet() else currentEpisodes.indices.toSet()
                        }) {
                            Text(
                                if (selectedDownloadEpisodes.size == currentEpisodes.size) "取消全选" else "全选",
                                color = SakuraPrimary
                            )
                        }
                        Text(
                            "已选 ${selectedDownloadEpisodes.size} 集",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val episodesToDownload = selectedDownloadEpisodes.toList()
                        val selectedCount = episodesToDownload.size
                        showDownloadDialog = false
                        selectedDownloadEpisodes = emptySet()
                        Toast.makeText(context, "已添加 $selectedCount 个下载任务", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            for (index in episodesToDownload) {
                                val ep = currentEpisodes.getOrNull(index) ?: continue
                                val taskId = "${video.id}_${ep.name}"
                                val saveDir = com.momo.app.data.download.DownloadManager.getDownloadDir()
                                val savePath = "${saveDir.absolutePath}/${video.id}_${ep.name}.mp4"
                                val task = com.momo.app.data.download.DownloadTask(
                                    id = taskId,
                                    videoId = video.id,
                                    videoName = video.name,
                                    videoPic = video.pic,
                                    episodeName = ep.name,
                                    episodeUrl = ep.url,
                                    savePath = savePath
                                )
                                com.momo.app.data.download.DownloadManager.startDownload(task)
                            }
                        }
                    },
                    enabled = selectedDownloadEpisodes.isNotEmpty()
                ) {
                    Text("确认下载", color = if (selectedDownloadEpisodes.isNotEmpty()) SakuraPrimary else TextTertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("取消", color = TextTertiary)
                }
            },
            containerColor = DarkSurface
        )
    }

    if (isFullscreen) {
        if (isCasting) {
            CastingControlPanel(
                deviceName = castDeviceName ?: "设备",
                positionMs = castPositionMs,
                durationMs = castDurationMs,
                playbackState = castPlaybackState,
                currentEpisodeIndex = state.currentEpisodeIndex,
                totalEpisodes = state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 0,
                isFullscreen = true,
                onToggleFullscreen = { toggleFullscreen() },
                onPrevEpisode = { castCommandHandler?.invoke(-1) },
                onStopCast = { castCommandHandler?.invoke(0) },
                onNextEpisode = { castCommandHandler?.invoke(1) },
                onTogglePlayPause = { castCommandHandler?.invoke(if (castPlaybackState == "PLAYING") 2 else 3) },
                onSeek = { targetMs -> castSeekHandler?.invoke(targetMs) }
            )
        } else {
        FullscreenPlayer(
            exoPlayer = exoPlayer,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            hasMediaLoaded = hasMediaLoaded,
            showControls = showControls,
            isLocked = isLocked,
            isLongPressSpeed = isLongPressSpeed,
            onLongPressSpeedChanged = { enabled ->
                if (enabled) {
                    speedBeforeLongPress = exoPlayer.playbackParameters.speed
                    exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(2f))
                    isLongPressSpeed = true
                } else {
                    exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speedBeforeLongPress))
                    isLongPressSpeed = false
                }
            },
            onToggleControls = { showControls = !showControls },
            onToggleLock = {
                isLocked = !isLocked
                if (!isLocked) {
                    showControls = true
                }
            },
            onTogglePlayPause = { togglePlayPause() },
            onExitFullscreen = { exitFullscreen() },
            onSpeedClick = { showSpeedDialog = true },
            onCastClick = { showCastSheet = true },
            onPrevEpisode = { viewModel.selectEpisode(state.currentEpisodeIndex - 1) },
            onNextEpisode = { viewModel.selectEpisode(state.currentEpisodeIndex + 1) },
            onEpisodePickerClick = { showFullscreenEpisodes = true },
            hasPrevEpisode = state.currentEpisodeIndex > 0,
            hasNextEpisode = state.currentEpisodeIndex < (state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 1) - 1,
            videoName = state.video?.name ?: "",
            episodeName = state.sources.getOrNull(state.currentSourceIndex)
                ?.episodes?.getOrNull(state.currentEpisodeIndex)?.name ?: "",
            onSeekCompleted = { saveWatchProgress() },
            currentPositionMs = currentPositionMs,
            playbackSpeed = playbackSpeedFactor,
            danmakuList = danmakuList,
            danmakuConfig = danmakuConfig,
            onDanmakuConfigChange = { danmakuConfig = it },
            onSendDanmaku = { text, color, type ->
                val posMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val newItem = com.momo.app.ui.danmaku.DanmakuItem(
                    text = text,
                    time = posMs,
                    color = color,
                    type = type
                )
                danmakuList = danmakuList + newItem
                // 同步发送到 dmku 公益弹幕库（真实弹幕服务）
                val name = state.video?.name ?: ""
                val episodeNo = state.currentEpisodeIndex + 1
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val hex = "#%06X".format(color.toInt() and 0xFFFFFF)
                    DmkuApi.sendDanmaku(name, episodeNo, posMs, text, hex)
                }
            }
        )
        } // end else (not casting)
    } else {
        LaunchedEffect(videoId) {
            // 已加载过则跳过：全屏退出/重组时避免重复 loadDetail 报错与重复注入重置进度
            if (state.video != null && state.sources.isNotEmpty()) return@LaunchedEffect
            if (externalVideo != null && externalSources != null) {
                viewModel.injectExternal(externalVideo, externalSources)
            } else {
                viewModel.loadDetail(videoId)
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            TopAppBar(
                title = { Text(state.video?.name ?: "影片详情", color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = {
                if (isCasting) showCastExitDialog = true
                else { saveWatchProgress(); onBack() }
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground.copy(alpha = 0.95f))
            )

            when {
                state.isLoading -> LoadingIndicator(modifier = Modifier.weight(1f))
                state.error != null -> Box(modifier = Modifier.weight(1f)) { ErrorState(state.error ?: "加载失败") { viewModel.loadDetail(videoId) } }
                state.video != null -> {
                    val video = state.video!!
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = false
                                        setOnTouchListener { _, _ -> false }
                                    }.also { playerViewRef = it }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // 离开竖屏组合时解绑 surface（进全屏/退出详情时），防止 surface 泄漏黑屏
                            DisposableEffect(Unit) {
                                onDispose { playerViewRef?.player = null }
                            }

                            if (!isPlaying && !isBuffering && currentUrl == null) {
                                AsyncImage(model = video.pic, contentDescription = video.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                                    Text("请选择剧集开始播放", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                                }
                            }

                            // Casting overlay: show when casting, pause ExoPlayer to save resources
                            if (isCasting) {
                                CastingControlPanel(
                                    deviceName = castDeviceName ?: "设备",
                                    positionMs = castPositionMs,
                                    durationMs = castDurationMs,
                                    playbackState = castPlaybackState,
                                    currentEpisodeIndex = state.currentEpisodeIndex,
                                    totalEpisodes = state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 0,
                                    isFullscreen = false,
                                    onToggleFullscreen = { toggleFullscreen() },
                                    onPrevEpisode = { castCommandHandler?.invoke(-1) },
                                    onStopCast = { castCommandHandler?.invoke(0) },
                                    onNextEpisode = { castCommandHandler?.invoke(1) },
                                    onTogglePlayPause = { castCommandHandler?.invoke(if (castPlaybackState == "PLAYING") 2 else 3) },
                                    onSeek = { targetMs -> castSeekHandler?.invoke(targetMs) }
                                )
                            }

                            if (currentUrl != null && isBuffering) {
                                if (!hasMediaLoaded) {
                                    Image(
                                        painter = painterResource(id = R.drawable.detail_background),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CuteCatLoader(modifier = Modifier.size(100.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(if (!hasMediaLoaded) "视频加载中..." else "缓冲中...", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                    }
                                }
                            }

                            if (playerError != null) {
                                Box(modifier = Modifier.fillMaxSize().background(DarkBackground.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("播放失败", color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(playerError!!, color = TextSecondary, fontSize = 12.sp, maxLines = 2)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedButton(onClick = {
                                            playerError = null
                                            hasMediaLoaded = false
                                            isBuffering = true
                                            userRequestedPlay = true
                                            val url = lastPreparedUrl
                                            if (url != null) {
                                                scope.launch { prepareAndPlay(url, viewModel.resolvePlayUrl(url)) }
                                            }
                                        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)) { Text("重试") }
                                    }
                                }
                            }

                            if (!isCasting) {
                            if (currentUrl != null) {
                                InlinePlayerGestureOverlay(
                                    exoPlayer = exoPlayer,
                                    showControls = showControls,
                                    isLongPressSpeed = isLongPressSpeed,
                                    onLongPressSpeedChanged = { enabled ->
                                        if (enabled) {
                                            speedBeforeLongPress = exoPlayer.playbackParameters.speed
                                            exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(2f))
                                            isLongPressSpeed = true
                                        } else {
                                            exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speedBeforeLongPress))
                                            isLongPressSpeed = false
                                        }
                                    },
                                    onToggleControls = { showControls = !showControls }
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { showControls = !showControls }
                                )
                            }

        if (showControls && isLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("屏幕已锁定", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isLocked && (showControls || !isPlaying),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 顶部信息条: 剧名 + 集数
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp)
                                    ) {
                                        Text(
                                            video.name,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val curEp = state.sources.getOrNull(state.currentSourceIndex)
                                            ?.episodes?.getOrNull(state.currentEpisodeIndex)
                                        if (curEp?.name?.isNotBlank() == true) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                curEp.name,
                                                color = Color.White.copy(alpha = 0.75f),
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PlayerChip(icon = Icons.Outlined.Cast, text = "投屏", onClick = { showCastSheet = true })
                                    }

                                    if (currentUrl != null && hasMediaLoaded && !isBuffering) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            if (isPlaying) {
                                                IconButton(
                                                    onClick = { exoPlayer.pause() },
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                                ) {
                                                    Icon(Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                                }
                                            } else {
                                                IconButton(
                                                    onClick = { togglePlayPause() },
                                                    modifier = Modifier
                                                        .size(52.dp)
                                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                                ) {
                                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                                }
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomStart)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        SeekableProgressControl(exoPlayer = exoPlayer, compact = true, mediaLoaded = hasMediaLoaded || currentUrl != null, onSeekingChanged = { isProgressSeeking = it }, onSeekCompleted = { userRequestedPlay = true; saveWatchProgress() })
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(onClick = { toggleFullscreen() }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.Fullscreen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }

                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(video.name, fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (!AuthRepository.isLoggedIn) {
                                        showLoginDialog = true
                                        return@TextButton
                                    }
                                    scope.launch {
                                        if (isFavorite) {
                                            favoritesStore.removeFavorite(videoId)
                                            favoritesSyncRepo.deleteFromCloud(videoId)
                                        } else {
                                            val favItem = video.toFavoriteItem()
                                            favoritesStore.addFavorite(favItem)
                                            favoritesSyncRepo.upsertToCloud(favItem)
                                        }
                                    }
                                }) {
                                    Icon(
                                        if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "收藏",
                                        tint = if (isFavorite) Color(0xFFFF6B6B) else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (isFavorite) "已收藏" else "收藏",
                                        color = if (isFavorite) Color(0xFFFF6B6B) else TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            if (showResumePrompt && historyEntry != null && historyEntry!!.positionMs > 10000) {
                                val entry = historyEntry!!
                                val totalSec = entry.positionMs / 1000
                                val h = totalSec / 3600
                                val m = (totalSec % 3600) / 60
                                val s = totalSec % 60
                                val timeText = buildString {
                                    if (h > 0) append("${h}小时")
                                    if (m > 0 || h > 0) append("${m}分钟")
                                    append("${s}秒")
                                }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = SakuraPrimary.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.History, null, tint = SakuraPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "上次看到 $timeText",
                                            color = SakuraPrimary, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = {
                                            showResumePrompt = false
                                            userRequestedPlay = true
                                            if (hasMediaLoaded) {
                                                exoPlayer.seekTo(entry.positionMs)
                                                exoPlayer.play()
                                                isBuffering = true
                                            } else if (exoPlayer.currentMediaItem != null) {
                                                exoPlayer.seekTo(entry.positionMs)
                                            } else {
                                                pendingSeekPosition = entry.positionMs
                                            }
                                        }) { Text("跳转", color = SakuraPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                        TextButton(onClick = { showResumePrompt = false }) { Text("忽略", color = TextTertiary, fontSize = 12.sp) }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (video.remarks.isNotBlank()) TagChip(video.remarks, SakuraPrimary)
                                if (video.typeName.isNotBlank()) TagChip(video.typeName, SkyBlue)
                                if (video.area.isNotBlank()) TagChip(video.area, Lavender)
                                if (video.year.isNotBlank()) TagChip(video.year, Color(0xFFFFB300))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (video.director.isNotBlank()) InfoRow("导演", video.director)
                            if (video.actor.isNotBlank()) InfoRow("演员", video.actor, 2)
                            if (video.content.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                var expanded by remember { mutableStateOf(false) }
                                Text("简介", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                val cleanContent = video.content.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
                                Text(cleanContent, color = TextSecondary, fontSize = 13.sp, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                                Text(if (expanded) "收起" else "展开全部", color = SakuraPrimary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded })
                            }
                        }

                        if (state.sources.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            SectionHeader(title = "播放源")
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(state.sources) { index, source ->
                                    val selected = index == state.currentSourceIndex
                                    Surface(shape = RoundedCornerShape(8.dp), color = if (selected) SakuraPrimary else DarkSurfaceVariant, modifier = Modifier.clickable { viewModel.selectSource(index) }) {
                                        Text(source.label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (selected) DarkBackground else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }

                            val currentSource = state.sources.getOrNull(state.currentSourceIndex)
                            if (currentSource != null && currentSource.episodes.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("选集 (${currentSource.episodes.size})", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Surface(
                                        modifier = Modifier.clickable {
                                            showDownloadDialog = true
                                            selectedDownloadEpisodes = setOf(state.currentEpisodeIndex)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = SakuraPrimary.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Outlined.PlayCircle, null, tint = SakuraPrimary, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("下载", color = SakuraPrimary, fontSize = 12.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(currentSource.episodes) { index, episode ->
                                        val selected = index == state.currentEpisodeIndex
                                        Surface(shape = RoundedCornerShape(8.dp), color = if (selected) SakuraPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant, border = if (selected) ButtonDefaults.outlinedButtonBorder else null, modifier = Modifier.clickable {
                                            if (isCasting) {
                                                pendingCastEpisodeIndex = index
                                            }
                                            viewModel.selectEpisode(index)
                                        }) {
                                            Text(episode.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp), color = if (selected) SakuraPrimary else TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenPlayer(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasMediaLoaded: Boolean,
    showControls: Boolean,
    isLocked: Boolean,
    isLongPressSpeed: Boolean,
    onLongPressSpeedChanged: (Boolean) -> Unit,
    onToggleControls: () -> Unit,
    onToggleLock: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onExitFullscreen: () -> Unit,
    onSpeedClick: () -> Unit,
    onCastClick: () -> Unit,
    onPrevEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onEpisodePickerClick: () -> Unit,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    videoName: String,
    episodeName: String,
    currentPositionMs: Long = 0L,
    playbackSpeed: Float = 1f,
    danmakuList: List<com.momo.app.ui.danmaku.DanmakuItem> = emptyList(),
    danmakuConfig: com.momo.app.ui.danmaku.DanmakuConfig = com.momo.app.ui.danmaku.DanmakuConfig(),
    onDanmakuConfigChange: (com.momo.app.ui.danmaku.DanmakuConfig) -> Unit = {},
    onSendDanmaku: (String, Long, com.momo.app.ui.danmaku.DanmakuType) -> Unit = { _, _, _ -> },
    onSeekCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var showSubtitleDialog by remember { mutableStateOf(false) }
    var subtitleTrackCount by remember { mutableStateOf(0) }

    // 弹幕 UI 状态
    var showDanmakuSettings by remember { mutableStateOf(false) }
    var showDanmakuInput by remember { mutableStateOf(false) }
    var localDanmakuConfig by remember { mutableStateOf(danmakuConfig) }

    // Detect available subtitle tracks
    LaunchedEffect(exoPlayer.currentMediaItem) {
        val textGroups = exoPlayer.currentTracks.groups
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        var count = 0
        textGroups.forEach { group ->
            count += group.length
        }
        subtitleTrackCount = count
    }

    if (showSubtitleDialog) {
        val textGroups = exoPlayer.currentTracks.groups
            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            containerColor = DarkSurface,
            title = { Text("选择字幕", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            val params = exoPlayer.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                .build()
                            exoPlayer.trackSelectionParameters = params
                            showSubtitleDialog = false
                        }
                    ) {
                        Text(
                            "关闭字幕",
                            color = SakuraPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                        )
                    }
                    var trackIdx = 0
                    textGroups.forEachIndexed { groupIdx, group ->
                        (0 until group.length).forEach { i ->
                            val format = group.getTrackFormat(i)
                            val label = format.label ?: format.language ?: "字幕 ${trackIdx + 1}"
                            val supported = group.isTrackSupported(i)
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = supported) {
                                    // 显式选中用户点击的轨道（否则只恢复字幕类型，播放器仍自动选默认轨）
                                    val params = exoPlayer.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    exoPlayer.trackSelectionParameters = params
                                    showSubtitleDialog = false
                                }
                            ) {
                                Text(
                                    label,
                                    color = if (supported) TextPrimary else TextTertiary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
                                )
                            }
                            trackIdx++
                        }
                    }
                    if (trackIdx == 0) {
                        Text("当前视频无可用字幕轨道", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) { Text("关闭", color = TextSecondary) }
            }
        )
    }

    var gestureVolume by remember { mutableFloatStateOf(1f) }
    var gestureBrightness by remember { mutableFloatStateOf(0.5f) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    // 全屏水平滑动拖进度
    var gestureSeekMs by remember { mutableLongStateOf(-1L) }
    var gestureSeekStartPos by remember { mutableFloatStateOf(0f) }
    var showSeekIndicator by remember { mutableStateOf(false) }
    // 轻点快进/快退提示（左右 1/4 区域单击）
    var tapSeekDelta by remember { mutableStateOf(0L) }
    var showTapSeekHint by remember { mutableStateOf(false) }

    LaunchedEffect(showVolumeIndicator) {
        if (showVolumeIndicator) { delay(800); showVolumeIndicator = false }
    }
    LaunchedEffect(showBrightnessIndicator) {
        if (showBrightnessIndicator) { delay(800); showBrightnessIndicator = false }
    }

    var fullscreenPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setOnTouchListener { _, _ -> false }
                }.also { fullscreenPlayerView = it }
            },
            modifier = Modifier.fillMaxSize()
        )
        // 退出全屏时及时解绑 surface，避免与竖屏 PlayerView 抢占导致黑屏
        DisposableEffect(Unit) {
            onDispose { fullscreenPlayerView?.player = null }
        }

        // B站风格弹幕渲染层
        DanmakuOverlay(
            danmakuList = danmakuList,
            currentPositionMs = currentPositionMs,
            isPlaying = isPlaying,
            config = localDanmakuConfig,
            playbackSpeed = playbackSpeed,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            gestureVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val winBrightness = activity?.window?.attributes?.screenBrightness
                            gestureBrightness = if (winBrightness != null && winBrightness >= 0f) {
                                winBrightness
                            } else {
                                val sysBrightness = Settings.System.getInt(
                                    context.contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS,
                                    125
                                )
                                (sysBrightness / 255f).coerceIn(0f, 1f)
                            }
                            // 水平滑动拖进度: 记录起始位置和基准时间
                            gestureSeekStartPos = offset.x
                            gestureSeekMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = kotlin.math.abs(dragAmount.x)
                            val dy = kotlin.math.abs(dragAmount.y)
                            val duration = exoPlayer.duration.coerceAtLeast(0L)
                            // 水平位移明显大于垂直 → 进度手势
                            if (dx > dy * 1.2f && duration > 0) {
                                // 每滑动 1/3 屏宽 = 进度条 100%
                                val fraction = (change.position.x - gestureSeekStartPos) / size.width / 3f
                                val target = (gestureSeekMs + (fraction * duration).toLong())
                                    .coerceIn(0L, duration)
                                gestureSeekMs = target
                                showSeekIndicator = true
                            } else if (dragAmount.y != 0f) {
                                // 垂直滑动: 音量/亮度 (右侧音量, 左侧亮度)
                                val delta = -dragAmount.y / size.height
                                if (change.position.x > size.width / 2) {
                                    // 右半屏上下滑: 调系统媒体音量（与音量键一致，跨会话记忆）
                                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    gestureVolume = (gestureVolume + delta * 2f).coerceIn(0f, 1f)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        (gestureVolume * maxVol).roundToInt().coerceIn(0, maxVol),
                                        0
                                    )
                                    showVolumeIndicator = true
                                } else {
                                    gestureBrightness = (gestureBrightness + delta * 2f).coerceIn(0f, 1f)
                                    val layoutParams = activity?.window?.attributes
                                    layoutParams?.screenBrightness = gestureBrightness
                                    activity?.window?.attributes = layoutParams
                                    showBrightnessIndicator = true
                                }
                            }
                        },
                        onDragEnd = {
                            // 结束水平拖拽 → 真正 seek
                            if (showSeekIndicator && gestureSeekMs >= 0) {
                                exoPlayer.seekTo(gestureSeekMs)
                                showSeekIndicator = false
                                onSeekCompleted()
                            }
                            gestureSeekMs = -1L
                        },
                        onDragCancel = {
                            gestureSeekMs = -1L
                            showSeekIndicator = false
                        }
                    )
                }
                .pointerInput(isLocked) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstUp = withTimeoutOrNull(300L) {
                            waitForUpOrCancellation()
                        }
                        if (firstUp == null) {
                            onLongPressSpeedChanged(true)
                            waitForUpOrCancellation()
                            onLongPressSpeedChanged(false)
                        } else {
                            if (isLocked) {
                                onToggleControls()
                            } else {
                                val secondDown = withTimeoutOrNull(300L) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                    currentEvent.changes.firstOrNull()?.let {
                                        if (it.pressed) it else null
                                    }
                                }
                                if (secondDown != null) {
                                    waitForUpOrCancellation()
                                    onTogglePlayPause()
                                } else {
                                    // 单击: 左侧 1/4 快退10秒, 右侧 1/4 快进10秒, 中间切控制栏
                                    val tapX = firstUp.position.x
                                    when {
                                        tapX < size.width / 4f -> {
                                            val target = (exoPlayer.currentPosition - 10_000L)
                                                .coerceAtLeast(0L)
                                            exoPlayer.seekTo(target)
                                            onSeekCompleted()
                                            tapSeekDelta = -10_000L
                                            showTapSeekHint = true
                                        }
                                        tapX > size.width * 3f / 4f -> {
                                            val max = exoPlayer.duration.coerceAtLeast(0L)
                                            val target = (exoPlayer.currentPosition + 10_000L)
                                                .coerceAtMost(if (max > 0) max else Long.MAX_VALUE)
                                            exoPlayer.seekTo(target)
                                            onSeekCompleted()
                                            tapSeekDelta = 10_000L
                                            showTapSeekHint = true
                                        }
                                        else -> onToggleControls()
                                    }
                                }
                            }
                        }
                    }
                }
        )

        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureVolume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        null, tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(72.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureVolume)
                                .align(Alignment.BottomStart)
                                .background(SakuraPrimary, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureBrightness < 0.01f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh,
                        null, tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(72.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureBrightness)
                                .align(Alignment.BottomStart)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showSeekIndicator && gestureSeekMs >= 0) {
            val totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            val target = gestureSeekMs.coerceIn(0L, if (totalDuration > 0) totalDuration else gestureSeekMs)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (target < exoPlayer.currentPosition) Icons.Filled.SkipPrevious else Icons.Filled.SkipNext,
                        null,
                        tint = SakuraPrimary,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            "${formatDuration(target)} / ${formatDuration(totalDuration)}",
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        val deltaSec = (target - exoPlayer.currentPosition) / 1000
                        if (deltaSec != 0L) {
                            Text(
                                (if (deltaSec > 0) "+" else "-") + formatDuration(abs(deltaSec) * 1000),
                                color = SakuraPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        if (showTapSeekHint && tapSeekDelta != 0L) {
            LaunchedEffect(tapSeekDelta) {
                delay(700)
                showTapSeekHint = false
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tapSeekDelta > 0) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious,
                        null,
                        tint = SakuraPrimary,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (tapSeekDelta > 0) "快进 10 秒" else "快退 10 秒",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isLongPressSpeed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Speed, null, tint = SakuraPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("2倍速播放中", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (isBuffering) {
            if (!hasMediaLoaded) {
                Image(
                    painter = painterResource(id = R.drawable.detail_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CuteCatLoader(modifier = Modifier.size(100.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (!hasMediaLoaded) "视频加载中..." else "缓冲中...", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }

        AnimatedVisibility(
            visible = showControls || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    // B站风格弹幕发送输入栏
                    DanmakuInputBar(
                        visible = showDanmakuInput,
                        onSend = onSendDanmaku,
                        onDismiss = { showDanmakuInput = false }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onExitFullscreen, modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出全屏", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(videoName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (episodeName.isNotBlank()) {
                                Text(episodeName, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!isLocked) {
                            PlayerChip(icon = Icons.Outlined.Cast, text = "投屏", onClick = onCastClick)
                            // B站风格弹幕发送按钮
                            PlayerChip(
                                icon = Icons.Filled.Edit,
                                text = "发弹幕",
                                active = showDanmakuInput,
                                onClick = { showDanmakuInput = !showDanmakuInput }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                ) {
                    Icon(
                        if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        if (isLocked) "解锁" else "锁定",
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                }

                if (!isLocked && !isBuffering) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            // 快退 10 秒
                            IconButton(
                                onClick = {
                                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    onSeekCompleted()
                                    tapSeekDelta = -10_000L
                                    showTapSeekHint = true
                                },
                                modifier = Modifier
                                    .size(50.dp)
                                    .shadow(4.dp, CircleShape)
                                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(Icons.Filled.Replay10, "快退10秒", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            // 播放/暂停 主按钮
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .size(68.dp)
                                    .shadow(6.dp, CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    null, tint = Color.White, modifier = Modifier.size(40.dp)
                                )
                            }
                            // 快进 10 秒
                            IconButton(
                                onClick = {
                                    val max = exoPlayer.duration.coerceAtLeast(0L)
                                    val target = (exoPlayer.currentPosition + 10_000L)
                                        .coerceAtMost(if (max > 0) max else Long.MAX_VALUE)
                                    exoPlayer.seekTo(target)
                                    onSeekCompleted()
                                    tapSeekDelta = 10_000L
                                    showTapSeekHint = true
                                },
                                modifier = Modifier
                                    .size(50.dp)
                                    .shadow(4.dp, CircleShape)
                                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(Icons.Filled.Forward10, "快进10秒", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }

                if (!isLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SeekableProgressControl(
                                exoPlayer = exoPlayer,
                                compact = false,
                                mediaLoaded = true,
                                onSeekingChanged = { },
                                onSeekCompleted = onSeekCompleted
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasPrevEpisode) {
                                IconButton(onClick = onPrevEpisode, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.SkipPrevious, "上一集", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            if (hasNextEpisode) {
                                IconButton(onClick = onNextEpisode, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Filled.SkipNext, "下一集", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            // B站风格弹幕开关按钮
                            PlayerChip(
                                icon = Icons.Filled.Subtitles,
                                text = "弹幕",
                                active = localDanmakuConfig.enabled,
                                onClick = {
                                    localDanmakuConfig = localDanmakuConfig.copy(enabled = !localDanmakuConfig.enabled)
                                    onDanmakuConfigChange(localDanmakuConfig)
                                }
                            )
                            // B站风格弹幕设置按钮
                            IconButton(
                                onClick = { showDanmakuSettings = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Tune,
                                    "弹幕设置",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            PlayerChip(
                                icon = Icons.AutoMirrored.Filled.ListAlt,
                                text = "选集",
                                onClick = onEpisodePickerClick
                            )
                            PlayerChip(
                                icon = Icons.Filled.Speed,
                                text = "倍速",
                                onClick = onSpeedClick
                            )
                            // Subtitle button
                            PlayerChip(
                                icon = Icons.Filled.Subtitles,
                                text = "字幕",
                                active = subtitleTrackCount > 0,
                                onClick = { showSubtitleDialog = true }
                            )
                            IconButton(onClick = onExitFullscreen, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.FullscreenExit, "退出全屏", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }

        // B站风格弹幕设置面板
        if (showDanmakuSettings) {
            DanmakuSettingsPanel(
                config = localDanmakuConfig,
                onConfigChange = { newConfig ->
                    localDanmakuConfig = newConfig
                    onDanmakuConfigChange(newConfig)
                },
                onDismiss = { showDanmakuSettings = false }
            )
        }
    }
}

@Composable
private fun SeekableProgressControl(exoPlayer: ExoPlayer, compact: Boolean = false, mediaLoaded: Boolean = false, onSeekingChanged: ((Boolean) -> Unit)? = null, onSeekCompleted: (() -> Unit)? = null) {
    var tick by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableStateOf(0f) }
    var currentDuration by remember { mutableStateOf(0L) }
    var bufferedFraction by remember { mutableStateOf(0f) }
    // 拖拽起始状态：记录按下时的播放进度和触摸坐标，用 delta 方式控制灵敏度
    var dragStartFraction by remember { mutableStateOf(0f) }
    var dragStartX by remember { mutableStateOf(0f) }
    val seekSensitivity = 0.5f  // 灵敏度：0.5 = 需要拖两倍距离才移动相同进度

    LaunchedEffect(isSeeking) {
        onSeekingChanged?.invoke(isSeeking)
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking) {
                tick = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentDuration = exoPlayer.duration.coerceAtLeast(0L)
                bufferedFraction = (exoPlayer.bufferedPercentage / 100f).coerceIn(0f, 1f)
            }
            delay(500)
        }
    }

    val isDurationValid = currentDuration > 0
    val duration = if (isDurationValid) currentDuration else 1L

    val progress = if (isSeeking) seekFraction
        else if (!isDurationValid) 0f
        else (tick.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    val showThumb = mediaLoaded && (isDurationValid || exoPlayer.currentMediaItem != null)
    val trackHeight = if (compact) 3.dp else 4.dp
    val thumbSize by animateDpAsState(
        targetValue = if (isSeeking) (if (compact) 14.dp else 16.dp) else (if (compact) 10.dp else 12.dp),
        label = "seekThumb"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 拖动时的时间预览气泡
        if (isSeeking && isDurationValid) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    formatDuration((seekFraction * duration).toLong()),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(if (isSeeking) (seekFraction * duration).toLong().coerceAtLeast(0L) else tick.coerceAtLeast(0L)),
                color = Color.White, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)  // 增大触摸区域，视觉条高度不变
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isSeeking = true
                                // 不跳到触摸位置，而是记录当前播放进度作为起点
                                val liveDuration = exoPlayer.duration.coerceAtLeast(0L)
                                dragStartFraction = if (liveDuration > 0) {
                                    (exoPlayer.currentPosition.toFloat() / liveDuration.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                seekFraction = dragStartFraction
                                dragStartX = offset.x
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                // delta 方式：进度变化 = 拖拽位移 × 灵敏度，不直接映射到绝对位置
                                val deltaFraction = (change.position.x - dragStartX) / size.width * seekSensitivity
                                seekFraction = (dragStartFraction + deltaFraction).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                isSeeking = false
                                val liveDuration = exoPlayer.duration.coerceAtLeast(0L)
                                if (liveDuration > 0) {
                                    exoPlayer.seekTo((seekFraction * liveDuration).toLong())
                                    onSeekCompleted?.invoke()
                                }
                            },
                            onDragCancel = { isSeeking = false }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            val liveDuration = exoPlayer.duration.coerceAtLeast(0L)
                            if (liveDuration > 0) {
                                exoPlayer.seekTo((fraction * liveDuration).toLong())
                                onSeekCompleted?.invoke()
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                // Buffered progress (light gray behind play progress)
                if (bufferedFraction > progress) {
                    LinearProgressIndicator(
                        progress = { bufferedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackHeight)
                            .clip(RoundedCornerShape(trackHeight / 2)),
                        color = Color.White.copy(alpha = 0.22f),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
                // 主题粉进度条（呼应弹幕主题色）
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight)
                        .clip(RoundedCornerShape(trackHeight / 2)),
                    color = Color(0xFFFB7299),
                    trackColor = Color.White.copy(alpha = 0.22f)
                )
                if (showThumb) {
                    Box(
                        modifier = Modifier.fillMaxWidth(progress.coerceAtLeast(0.01f)),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .size(thumbSize)
                                .shadow(3.dp, CircleShape)
                                .background(Color.White, CircleShape)
                                .border(2.dp, Color(0xFFFB7299), CircleShape)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(formatDuration(duration), color = Color.White.copy(alpha = 0.7f), fontSize = if (compact) 11.sp else 12.sp)
        }
    }
}

@Composable
private fun InlinePlayerGestureOverlay(
    exoPlayer: ExoPlayer,
    showControls: Boolean,
    isLongPressSpeed: Boolean,
    onLongPressSpeedChanged: (Boolean) -> Unit,
    onToggleControls: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var gestureVolume by remember { mutableFloatStateOf(1f) }
    var gestureBrightness by remember { mutableFloatStateOf(0.5f) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var isGesturing by remember { mutableStateOf(false) }
    // 轻点快进/快退提示
    var tapSeekDelta by remember { mutableStateOf(0L) }
    var showTapSeekHint by remember { mutableStateOf(false) }

    LaunchedEffect(showVolumeIndicator) {
        if (showVolumeIndicator) { delay(800); showVolumeIndicator = false }
    }
    LaunchedEffect(showBrightnessIndicator) {
        if (showBrightnessIndicator) { delay(800); showBrightnessIndicator = false }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            gestureVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val winBrightness = activity?.window?.attributes?.screenBrightness
                            gestureBrightness = if (winBrightness != null && winBrightness >= 0f) {
                                winBrightness
                            } else {
                                val sysBrightness = Settings.System.getInt(
                                    context.contentResolver,
                                    Settings.System.SCREEN_BRIGHTNESS,
                                    125
                                )
                                (sysBrightness / 255f).coerceIn(0f, 1f)
                            }
                            isGesturing = true
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.y == 0f) return@detectDragGestures
                            val delta = -dragAmount.y / size.height
                            if (change.position.x > size.width / 2) {
                                // 右半屏上下滑: 调系统媒体音量（与音量键一致，跨会话记忆）
                                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                gestureVolume = (gestureVolume + delta * 2f).coerceIn(0f, 1f)
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (gestureVolume * maxVol).roundToInt().coerceIn(0, maxVol),
                                    0
                                )
                                showVolumeIndicator = true
                            } else {
                                gestureBrightness = (gestureBrightness + delta * 2f).coerceIn(0f, 1f)
                                val layoutParams = activity?.window?.attributes
                                layoutParams?.screenBrightness = gestureBrightness
                                activity?.window?.attributes = layoutParams
                                showBrightnessIndicator = true
                            }
                        },
                        onDragEnd = {
                            isGesturing = false
                        },
                        onDragCancel = {
                            isGesturing = false
                        }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstUp = withTimeoutOrNull(300L) {
                            waitForUpOrCancellation()
                        }
                        if (firstUp == null) {
                            onLongPressSpeedChanged(true)
                            waitForUpOrCancellation()
                            onLongPressSpeedChanged(false)
                        } else {
                            val secondDown = withTimeoutOrNull(300L) {
                                awaitPointerEvent(PointerEventPass.Main)
                                currentEvent.changes.firstOrNull()?.let {
                                    if (it.pressed) it else null
                                }
                            }
                            if (secondDown != null) {
                                waitForUpOrCancellation()
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            } else {
                                // 单击: 左侧 1/4 快退10秒, 右侧 1/4 快进10秒, 中间切控制栏
                                val tapX = firstUp.position.x
                                when {
                                    tapX < size.width / 4f -> {
                                        val target = (exoPlayer.currentPosition - 10_000L)
                                            .coerceAtLeast(0L)
                                        exoPlayer.seekTo(target)
                                        tapSeekDelta = -10_000L
                                        showTapSeekHint = true
                                    }
                                    tapX > size.width * 3f / 4f -> {
                                        val max = exoPlayer.duration.coerceAtLeast(0L)
                                        val target = (exoPlayer.currentPosition + 10_000L)
                                            .coerceAtMost(if (max > 0) max else Long.MAX_VALUE)
                                        exoPlayer.seekTo(target)
                                        tapSeekDelta = 10_000L
                                        showTapSeekHint = true
                                    }
                                    else -> onToggleControls()
                                }
                            }
                        }
                    }
                }
        )

        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureVolume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        null, tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(72.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureVolume)
                                .align(Alignment.BottomStart)
                                .background(SakuraPrimary, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureBrightness < 0.01f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh,
                        null, tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(72.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureBrightness)
                                .align(Alignment.BottomStart)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showTapSeekHint && tapSeekDelta != 0L) {
            LaunchedEffect(tapSeekDelta) {
                delay(700)
                showTapSeekHint = false
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tapSeekDelta > 0) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious,
                        null,
                        tint = SakuraPrimary,
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (tapSeekDelta > 0) "快进 10 秒" else "快退 10 秒",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 30.dp)
        ) {
            Text("播放倍速", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                speeds.forEach { speed ->
                    val selected = abs(speed - currentSpeed) < 0.01f
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) SakuraPrimary else DarkSurfaceVariant,
                        modifier = Modifier.clickable { onSpeedSelected(speed) }
                    ) {
                        Text(
                            if (speed == 1.0f) "正常" else "${speed}x",
                            color = if (selected) Color.White else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

@Composable
private fun TagChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoRow(label: String, value: String, maxLines: Int = 1) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = TextTertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(value, color = TextSecondary, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CastingControlPanel(
    deviceName: String,
    positionMs: Long,
    durationMs: Long,
    playbackState: String,
    currentEpisodeIndex: Int,
    totalEpisodes: Int,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onPrevEpisode: () -> Unit,
    onStopCast: () -> Unit,
    onNextEpisode: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit = {}
) {
    // 两次 SOAP 轮询(3s)之间本地插值，让进度显示平滑推进
    var interpolatedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(positionMs, playbackState) {
        interpolatedMs = 0L
        if (playbackState == "PLAYING") {
            while (isActive) {
                delay(1000)
                interpolatedMs += 1000L
            }
        }
    }
    val displayMs = (positionMs + interpolatedMs).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)

    val stateText = when (playbackState) {
        "PLAYING" -> "播放中"
        "PAUSED_PLAYBACK", "PAUSED" -> "已暂停"
        "BUFFERING" -> "缓冲中"
        "STOPPED" -> "已停止"
        "TRANSITIONING" -> "切换中"
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onToggleFullscreen() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Tv,
                contentDescription = null,
                tint = SakuraPrimary,
                modifier = Modifier.size(if (isFullscreen) 48.dp else 32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("投屏中", color = SakuraPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                deviceName,
                color = Color.White,
                fontSize = if (isFullscreen) 15.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (stateText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(stateText, color = Color.White.copy(alpha = 0.6f), fontSize = if (isFullscreen) 13.sp else 11.sp)
            }
            Spacer(modifier = Modifier.height(if (isFullscreen) 10.dp else 6.dp))
            if (isFullscreen) {
                CastSeekBar(
                    positionMs = displayMs,
                    durationMs = durationMs,
                    compact = false,
                    onSeek = onSeek
                )
            } else {
                Text(
                    "${formatMs(displayMs)} / ${formatMs(durationMs)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(if (isFullscreen) 14.dp else 8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (isFullscreen) 24.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CastTextButton(
                    text = "上一集",
                    enabled = currentEpisodeIndex > 0,
                    onClick = onPrevEpisode,
                    fontSize = if (isFullscreen) 16.sp else 14.sp
                )
                // 播放/暂停遥控
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(if (isFullscreen) 60.dp else 46.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        if (playbackState == "PLAYING") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState == "PLAYING") "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(if (isFullscreen) 32.dp else 24.dp)
                    )
                }
                CastTextButton(
                    text = "下一集",
                    enabled = currentEpisodeIndex < totalEpisodes - 1,
                    onClick = onNextEpisode,
                    fontSize = if (isFullscreen) 16.sp else 14.sp
                )
            }
            Spacer(modifier = Modifier.height(if (isFullscreen) 12.dp else 6.dp))
            CastTextButton(
                text = "结束投屏",
                enabled = true,
                onClick = onStopCast,
                isStop = true,
                fontSize = if (isFullscreen) 16.sp else 14.sp
            )
            if (!isFullscreen) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("点击切换全屏", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp)
            }
        }
    }
}

/**
 * 投屏进度条：拖动结束通过 DLNA REL_TIME Seek 让 TV 跳转。
 */
@Composable
private fun CastSeekBar(positionMs: Long, durationMs: Long, compact: Boolean, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(if (compact) 0.9f else 0.92f)
    ) {
        Text(
            formatMs(positionMs),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = if (compact) 10.sp else 12.sp
        )
        Slider(
            value = if (isDragging) dragFraction else fraction,
            onValueChange = {
                isDragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                isDragging = false
                if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SakuraPrimary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
        Text(
            formatMs(durationMs),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = if (compact) 10.sp else 12.sp
        )
    }
}

/**
 * 播放器统一操作芯片：半透明胶囊底 + 图标 + 文字，active 态高亮主题色。
 */
@Composable
private fun PlayerChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (active) SakuraPrimary.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.45f),
        border = if (active) BorderStroke(1.dp, SakuraPrimary.copy(alpha = 0.6f)) else BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (active) SakuraPrimary else Color.White, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, color = if (active) SakuraPrimary else Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CastTextButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isStop: Boolean = false,
    fontSize: TextUnit = 14.sp
) {
    val bgColor = when {
        isStop -> Color(0xFFFF5252)
        enabled -> Color.White.copy(alpha = 0.15f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val textColor = when {
        isStop -> Color.White
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.3f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = if (isStop) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastDeviceSheet(
    videoUrl: String?,
    videoTitle: String,
    resumePositionMs: Long,
    onDismiss: () -> Unit,
    onCastStateChanged: (Boolean, String?, String) -> Unit,
    onCastProgressUpdate: (currentMs: Long, totalMs: Long, playbackState: String) -> Unit,
    onCastSeekRegistered: (((Long) -> Unit) -> Unit)? = null,
    onCastDisconnected: () -> Unit,
    onCastCommandRegistered: (((Int) -> Unit) -> Unit)?,
    onNextEpisode: () -> Unit,
    onPrevEpisode: () -> Unit,
    currentEpisodeIndex: Int,
    totalEpisodes: Int,
    getEpisodeUrl: () -> String?,
    // NBY 加密地址懒解密(直链原样返回), 由上层 ViewModel 实现
    resolveUrl: suspend (String) -> String = { it },
    onStopCastingRegistered: (() -> Unit) -> Unit,
    onSaveCastingProgress: (currentMs: Long, totalMs: Long) -> Unit,
    pendingEpisodeIndex: Int? = null,
    onPendingEpisodeConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<com.yinnho.upnpcast.DLNACast.Device>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var castingDevice by remember { mutableStateOf<String?>(null) }
    var castError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Proxy casting state
    var isCasting by remember { mutableStateOf(false) }
    var castProgress by remember { mutableStateOf("00:00 / 00:00") }
    var castPlaybackState by remember { mutableStateOf("") }
    var castingDeviceName by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectedSessionId by remember { mutableStateOf<String?>(null) }
    var proxyServer by remember { mutableStateOf<DlnaProxyServer?>(null) }
    var currentCastDevice by remember { mutableStateOf<com.yinnho.upnpcast.DLNACast.Device?>(null) }
    var isSwitchingEpisode by remember { mutableStateOf(false) }
    var userStopped by remember { mutableStateOf(false) }
    var pendingSwitchDirection by remember { mutableStateOf<Int?>(null) }

    var isStopInProgress by remember { mutableStateOf(false) }

    val stopCasting: () -> Unit = lambda@{
        if (isStopInProgress) return@lambda
        isStopInProgress = true
        userStopped = true
        isCasting = false
        castingDeviceName = null
        castingDevice = null
        onCastStateChanged(false, null, "")
        val sid = connectedSessionId
        val server = proxyServer
        val deviceToStop = currentCastDevice
        connectedSessionId = null
        proxyServer = null
        scope.launch(Dispatchers.IO) {
            // Get final TV position before stopping
            try {
                val pos = DirectDlnaCaster.getPositionInfo()
                if (pos != null && pos.first > 0) {
                    onSaveCastingProgress(pos.first, pos.second)
                }
            } catch (_: Exception) { }
            try { deviceToStop?.let { DirectDlnaCaster.stop(it) } } catch (_: Exception) { }
            try { DLNACast.stop() } catch (_: Exception) { }
            DirectDlnaCaster.clearCache()
            sid?.let { DlnaProxyService.sessionManager?.destroySession(it) }
            server?.clearSession(sid ?: "")
            server?.shutdown()
            DlnaProxyService.stop(context)
        }
    }

    SideEffect {
        onStopCastingRegistered(stopCasting)
    }

    SideEffect {
        onCastCommandRegistered?.invoke { action ->
            when (action) {
                -1 -> pendingSwitchDirection = -1
                0 -> stopCasting()
                1 -> pendingSwitchDirection = 1
                2 -> scope.launch(Dispatchers.IO) {
                    try { DirectDlnaCaster.pause() } catch (_: Exception) { }
                }
                3 -> scope.launch(Dispatchers.IO) {
                    try { DirectDlnaCaster.play() } catch (_: Exception) { }
                }
            }
        }
    }

    SideEffect {
        onCastSeekRegistered?.invoke { targetMs ->
            scope.launch(Dispatchers.IO) {
                try { DirectDlnaCaster.seek(targetMs) } catch (_: Exception) { }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            userStopped = true
        }
    }

    LaunchedEffect(Unit) {
        isSearching = true
        castError = null
        try {
            devices = searchDlnaDevices(context)
        } catch (_: Exception) {
            castError = "搜索设备失败，请确保手机与电视在同一WiFi网络"
        }
        isSearching = false
    }

    if (!isCasting) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("投屏到设备", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (videoUrl != null) "将「$videoTitle」投屏到同一WiFi下的电视或盒子"
                else "请先选择一集视频再投屏",
                color = TextSecondary, fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(16.dp))

            when {
                isCasting -> {
                    // Casting is managed by CastingControlPanel in the main screen.
                    // CastDeviceSheet stays mounted for polling/effects but hides the bottom sheet.
                }
                videoUrl == null -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("请先选择剧集", color = TextTertiary, fontSize = 14.sp)
                    }
                }
                isConnecting -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            color = SakuraPrimary,
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "正在连接到 ${castingDeviceName ?: "设备"}...",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                isSearching -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = SakuraPrimary, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("正在搜索附近的设备...", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
                castError != null -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.WifiOff, null, tint = TextTertiary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(castError!!, color = TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                devices.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CastConnected, null, tint = TextTertiary, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("未发现可用设备", color = TextTertiary, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("请确保手机与电视在同一WiFi下", color = TextTertiary, fontSize = 12.sp)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(devices.size) { index ->
                            val device = devices[index]
                            val isThisCasting = castingDevice == device.id
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isThisCasting && castingDevice == null) {
                                        castingDevice = device.id
                                        castingDeviceName = device.name
                                        currentCastDevice = device
                                        isConnecting = true
                                        castError = null
                                        userStopped = false
                                        scope.launch {
                                            try {
                                                // Start proxy service
                                                DlnaProxyService.start(context)
                                                delay(100)

                                                // Get session manager
                                                val sessionManager = DlnaProxyService.sessionManager
                                                    ?: throw IllegalStateException("代理服务未就绪")

                                                // Create proxy session (NBY 加密地址先懒解密)
                                                val realPlayUrl = if (videoUrl?.startsWith("NBY-") == true) {
                                                    resolveUrl(videoUrl)
                                                } else videoUrl
                                                if (realPlayUrl.isNullOrBlank()) {
                                                    throw IllegalStateException("视频地址无效，请切换线路")
                                                }
                                                val proxySession = withContext(Dispatchers.IO) {
                                                    try {
                                                        sessionManager.createSession(realPlayUrl, resumePositionMs)
                                                    } catch (e: Exception) {
                                                        throw IllegalStateException("视频解析失败: ${e.message}", e)
                                                    }
                                                }

                                                // Create and start proxy server
                                                val server = try {
                                                    DlnaProxyServer().also { it.start() }
                                                } catch (e: Exception) {
                                                    sessionManager.destroySession(proxySession.sessionId)
                                                    throw IllegalStateException("代理服务启动失败: ${e.message}", e)
                                                }
                                                proxyServer = server

                                                // Get proxy URL
                                                val proxyUrl = server.getPlaylistUrl(proxySession.sessionId)

                                                // Cast proxy URL to device via DirectDlnaCaster (proper DLNA.ORG_PN)
                                                val success = DirectDlnaCaster.cast(device, proxyUrl, videoTitle)
                                                if (success) {
                                                    isCasting = true
                                                    connectedSessionId = proxySession.sessionId
                                                    isConnecting = false
                                                    onCastStateChanged(true, castingDeviceName, castProgress)

                                                    // switchEpisode helper — stops current cast, switches episode, creates new session, re-casts
                                                    suspend fun switchEpisode(isNext: Boolean): Boolean {
                                                        isSwitchingEpisode = true
                                                        try {
                                                            val dev = currentCastDevice
                                                            if (dev != null) {
                                                                try { DirectDlnaCaster.stop(dev) } catch (_: Exception) { }
                                                            }
                                                            connectedSessionId?.let { sid ->
                                                                DlnaProxyService.sessionManager?.destroySession(sid)
                                                                proxyServer?.clearSession(sid)
                                                            }
                                                            connectedSessionId = null

                                                            if (isNext) onNextEpisode() else onPrevEpisode()
                                                            delay(500)

                                                            val newUrl = getEpisodeUrl()
                                                            if (newUrl != null) {
                                                                // NBY 加密地址懒解密
                                                                val realNewUrl = if (newUrl.startsWith("NBY-")) resolveUrl(newUrl) else newUrl
                                                                val newSession = withContext(Dispatchers.IO) {
                                                                    DlnaProxyService.sessionManager?.createSession(realNewUrl, 0L)
                                                                }
                                                                if (newSession != null) {
                                                                    val activeServer = proxyServer
                                                                        ?: DlnaProxyServer().also {
                                                                            it.start()
                                                                            proxyServer = it
                                                                        }
                                                                    val newProxyUrl = activeServer.getPlaylistUrl(newSession.sessionId)
                                                                    if (dev != null) {
                                                                        val ok = DirectDlnaCaster.cast(dev, newProxyUrl, videoTitle)
                                                                        if (ok) {
                                                                            connectedSessionId = newSession.sessionId
                                                                            isSwitchingEpisode = false
                                                                            return true
                                                                        }
                                                                    }
                                                                    // Cast failed or dev is null — clean up new session
                                                                    DlnaProxyService.sessionManager?.destroySession(newSession.sessionId)
                                                                    activeServer.clearSession(newSession.sessionId)
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                        isSwitchingEpisode = false
                                                        return false
                                                    }

                                                    suspend fun switchEpisodeToIndex(targetIndex: Int): Boolean {
                                                        isSwitchingEpisode = true
                                                        try {
                                                            val dev = currentCastDevice
                                                            if (dev != null) {
                                                                try { DirectDlnaCaster.stop(dev) } catch (_: Exception) { }
                                                            }
                                                            connectedSessionId?.let { sid ->
                                                                DlnaProxyService.sessionManager?.destroySession(sid)
                                                                proxyServer?.clearSession(sid)
                                                            }
                                                            connectedSessionId = null

                                                            delay(300)

                                                            val newUrl = getEpisodeUrl()
                                                            if (newUrl != null) {
                                                                // NBY 加密地址懒解密
                                                                val realNewUrl = if (newUrl.startsWith("NBY-")) resolveUrl(newUrl) else newUrl
                                                                val newSession = withContext(Dispatchers.IO) {
                                                                    DlnaProxyService.sessionManager?.createSession(realNewUrl, 0L)
                                                                }
                                                                if (newSession != null) {
                                                                    val activeServer = proxyServer
                                                                        ?: DlnaProxyServer().also {
                                                                            it.start()
                                                                            proxyServer = it
                                                                        }
                                                                    val newProxyUrl = activeServer.getPlaylistUrl(newSession.sessionId)
                                                                    if (dev != null) {
                                                                        val ok = DirectDlnaCaster.cast(dev, newProxyUrl, videoTitle)
                                                                        if (ok) {
                                                                            connectedSessionId = newSession.sessionId
                                                                            isSwitchingEpisode = false
                                                                            return true
                                                                        }
                                                                    }
                                                                    DlnaProxyService.sessionManager?.destroySession(newSession.sessionId)
                                                                    activeServer.clearSession(newSession.sessionId)
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                        isSwitchingEpisode = false
                                                        return false
                                                    }

                                                    // Continuous progress polling with auto-next
                                                    var stoppedCount = 0
                                                    var pollCount = 0
                                                    while (isActive) {
                                                        delay(3000)
                                                        pollCount++
                                                        try {
                                                            // Handle manual episode switch request from buttons
                                                            val pending = pendingSwitchDirection
                                                            if (pending != null) {
                                                                pendingSwitchDirection = null
                                                                val switched = switchEpisode(isNext = pending > 0)
                                                                if (!switched) break
                                                                stoppedCount = 0
                                                                pollCount = 0
                                                                continue
                                                            }

                                                            // Handle episode selection from grid while casting
                                                            val pendingEp = pendingEpisodeIndex
                                                            if (pendingEp != null) {
                                                                onPendingEpisodeConsumed()
                                                                val switched = switchEpisodeToIndex(pendingEp)
                                                                if (!switched) break
                                                                stoppedCount = 0
                                                                pollCount = 0
                                                                continue
                                                            }

                                                            var shouldAutoNext = false
                                                            var sessionComplete = false
                                                            var currentMs = 0L
                                                            var totalMs = 0L

                                                            // Check proxy session completion (most reliable for M3U8 streams)
                                                            val sid = connectedSessionId
                                                            if (sid != null) {
                                                                sessionComplete = DlnaProxyService.sessionManager?.isSessionComplete(sid) ?: false
                                                            }

                                                            // Query real TV transport state via DirectDlnaCaster SOAP
                                                            val transportState = DirectDlnaCaster.getTransportState() ?: "UNKNOWN"
                                                            castPlaybackState = transportState

                                                            // Query real TV progress via DirectDlnaCaster SOAP
                                                            val prog = DirectDlnaCaster.getPositionInfo()
                                                            if (prog != null && (prog.first > 0 || prog.second > 0)) {
                                                                currentMs = prog.first
                                                                totalMs = prog.second
                                                            }

                                                            // Fallback: estimated progress from proxy session
                                                            if (currentMs == 0L && totalMs == 0L && sid != null) {
                                                                val estimated = DlnaProxyService.sessionManager?.getEstimatedProgress(sid)
                                                                if (estimated != null && estimated.second > 0) {
                                                                    currentMs = estimated.first
                                                                    totalMs = estimated.second
                                                                }
                                                            }

                                                            if (totalMs > 0) {
                                                                castProgress = "${formatMs(currentMs)} / ${formatMs(totalMs)}"
                                                                onCastProgressUpdate(currentMs, totalMs, transportState)

                                                                // Auto-next when near end（仅播放中触发，暂停停在片尾不切集）
                                                                if (currentMs >= totalMs - 3000 && transportState == "PLAYING" && currentEpisodeIndex < totalEpisodes - 1) {
                                                                    shouldAutoNext = true
                                                                }

                                                                // Save casting progress to history every ~15s
                                                                if (pollCount % 5 == 0 && currentMs > 0) {
                                                                    onSaveCastingProgress(currentMs, totalMs)
                                                                }
                                                            } else {
                                                                onCastProgressUpdate(currentMs, totalMs, transportState)
                                                            }
                                                            // Fallback: session complete + TV near end
                                                            if (sessionComplete && currentEpisodeIndex < totalEpisodes - 1) {
                                                                shouldAutoNext = true
                                                            }

                                                            // Detect TV playback stopped (not user-initiated)
                                                            // Require 2 consecutive STOPPED states to avoid false triggers
                                                            if (transportState == "STOPPED" && !userStopped && !isSwitchingEpisode) {
                                                                stoppedCount++
                                                                if (stoppedCount >= 2 && currentEpisodeIndex < totalEpisodes - 1) {
                                                                    shouldAutoNext = true
                                                                } else if (stoppedCount >= 3) {
                                                                    onCastDisconnected()
                                                                }
                                                            } else {
                                                                stoppedCount = 0
                                                            }

                                                            if (shouldAutoNext && !userStopped) {
                                                                val switched = switchEpisode(isNext = true)
                                                                if (!switched) break
                                                                stoppedCount = 0
                                                            }
                                                        } catch (_: Exception) {
                                                            if (!userStopped && isCasting) {
                                                                onCastDisconnected()
                                                                break
                                                            }
                                                        }
                                                    }
                                                    // while loop exited — cleanup if not user-initiated stop
                                                    if (!userStopped) {
                                                        stopCasting()
                                                    }
                                                } else {
                                                    // Cast failed, clean up
                                                    sessionManager.destroySession(proxySession.sessionId)
                                                    server.shutdown()
                                                    proxyServer = null
                                                    castError = "投屏失败，请重试"
                                                    isConnecting = false
                                                    castingDevice = null
                                                    castingDeviceName = null
                                                }
                                            } catch (e: Exception) {
                                                castError = e.message ?: "投屏失败"
                                                isConnecting = false
                                                castingDevice = null
                                                castingDeviceName = null
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isThisCasting) SakuraPrimary.copy(alpha = 0.15f) else DarkSurfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (device.isTV) Icons.Filled.Tv else Icons.Filled.Devices,
                                        null,
                                        tint = if (isThisCasting) SakuraPrimary else TextSecondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            device.name,
                                            color = if (isThisCasting) SakuraPrimary else TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            device.address,
                                            color = TextTertiary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    if (isThisCasting) {
                                        CircularProgressIndicator(
                                            color = SakuraPrimary,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Cast,
                                            null,
                                            tint = SakuraPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (castError != null && devices.isNotEmpty() && !isCasting) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(castError!!, color = Color(0xFFFF5252), fontSize = 12.sp)
            }

            if (!isSearching && devices.isNotEmpty() && !isCasting) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isSearching = true
                            castError = null
                            try { devices = searchDlnaDevices(context) }
                            catch (_: Exception) { castError = "搜索失败" }
                            isSearching = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)
                ) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重新搜索")
                }
            }
        }
    }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
