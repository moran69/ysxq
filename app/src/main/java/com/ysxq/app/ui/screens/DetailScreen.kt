package com.ysxq.app.ui.screens

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.yinnho.upnpcast.DLNACast
import com.ysxq.app.R
import com.ysxq.app.data.local.WatchHistoryEntry
import com.ysxq.app.data.local.favoritesStore
import com.ysxq.app.data.local.toFavoriteItem
import com.ysxq.app.data.local.watchHistoryStore
import com.ysxq.app.data.sync.FavoritesSyncRepository
import com.ysxq.app.data.sync.WatchHistorySyncRepository
import com.ysxq.app.data.proxy.DlnaProxyServer
import com.ysxq.app.data.proxy.DlnaProxyService
import com.ysxq.app.ui.components.*
import com.ysxq.app.ui.theme.*
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.viewmodel.DetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    videoId: Int,
    onBack: () -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isProgressSeeking by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showCastSheet by remember { mutableStateOf(false) }
    var showFullscreenEpisodes by remember { mutableStateOf(false) }
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
    var isLongPressSpeed by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(1f) }
    var showLoginDialog by remember { mutableStateOf(false) }

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

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf(
                "Referer" to "https://cj.lziapi.com/"
            ))
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // Large buffer to prevent audio underrun — especially important on emulator
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                100_000,   // minBufferMs: keep 100s buffered
                100_000,   // maxBufferMs: allow up to 100s
                5000,      // bufferForPlaybackMs: wait 5s before starting
                10_000     // bufferForPlaybackAfterRebufferMs: wait 10s after rebuffer
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
            )
            .setLoadControl(loadControl)
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
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            hasMediaLoaded = true
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
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playerError = error.message ?: "播放出错"
                    }
                })
            }
    }

    val currentUrl = viewModel.getCurrentEpisodeUrl()
    LaunchedEffect(currentUrl) {
        if (currentUrl != null) {
            playerError = null
            isBuffering = true
            hasMediaLoaded = false
            hasInitialHistorySaved = false
            pendingSeekPosition = null
            userRequestedPlay = false
            exoPlayer.setMediaItem(MediaItem.fromUri(currentUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
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

    DisposableEffect(Unit) {
        exoPlayer.volume = 1f
        onDispose {
            isCasting = false
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.release()
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
                    if (!isCasting) {
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
            isFullscreen = false
            isLocked = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
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

    DisposableEffect(Unit) {
        onDispose {
            if (isCasting) {
                stopCastingCallback?.invoke()
            }
        }
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        activity?.let { act ->
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (isFullscreen) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                controller.show(WindowInsetsCompat.Type.systemBars())
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
            onCastStateChanged = { casting ->
                isCasting = casting
                if (casting) {
                    // Pause local player to save resources while casting
                    exoPlayer.pause()
                } else {
                    // Resume local player at TV's last known position
                    if (lastCastPositionMs > 0 && exoPlayer.contentDuration > 0) {
                        exoPlayer.seekTo(lastCastPositionMs)
                    }
                    exoPlayer.play()
                    lastCastPositionMs = 0L
                }
            },
            onNextEpisode = { viewModel.selectEpisode(viewModel.state.value.currentEpisodeIndex + 1) },
            onPrevEpisode = { viewModel.selectEpisode(viewModel.state.value.currentEpisodeIndex - 1) },
            currentEpisodeIndex = state.currentEpisodeIndex,
            totalEpisodes = state.sources.getOrNull(state.currentSourceIndex)?.episodes?.size ?: 0,
            getEpisodeUrl = { viewModel.getCurrentEpisodeUrl() },
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

    if (isFullscreen) {
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
            onExitFullscreen = {
                isFullscreen = false
                isLocked = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity?.let { act ->
                    WindowCompat.getInsetsController(act.window, act.window.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            },
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
            onSeekCompleted = { saveWatchProgress() }
        )
    } else {
        LaunchedEffect(videoId) { viewModel.loadDetail(videoId) }

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

                            if (!isPlaying && !isBuffering && currentUrl == null) {
                                AsyncImage(model = video.pic, contentDescription = video.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                                    Text("请选择剧集开始播放", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                                }
                            }

                            // Casting overlay: show when casting, pause ExoPlayer to save resources
                            if (isCasting) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.75f))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { showCastSheet = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Filled.Tv,
                                            contentDescription = null,
                                            tint = SakuraPrimary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("正在投屏中", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("点击管理投屏", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                }
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
                                            togglePlayPause()
                                        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)) { Text("重试") }
                                    }
                                }
                            }

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

                            if (showControls || !isPlaying) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.clickable { showCastSheet = true },
                                            shape = RoundedCornerShape(14.dp),
                                            color = Color.Black.copy(alpha = 0.5f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Outlined.Cast, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("投屏", color = Color.White, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    if (currentUrl != null && hasMediaLoaded && !isBuffering) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            if (isPlaying) {
                                                IconButton(
                                                    onClick = { exoPlayer.pause() },
                                                    modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                                ) {
                                                    Icon(Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                                }
                                            } else {
                                                IconButton(
                                                    onClick = { togglePlayPause() },
                                                    modifier = Modifier.size(56.dp).background(SakuraPrimary.copy(alpha = 0.9f), CircleShape)
                                                ) {
                                                    Icon(Icons.Filled.PlayArrow, null, tint = DarkBackground, modifier = Modifier.size(36.dp))
                                                }
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomStart)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
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
                                        modifier = Modifier.clickable { showCastSheet = true },
                                        shape = RoundedCornerShape(14.dp),
                                        color = SakuraPrimary.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Outlined.Cast, null, tint = SakuraPrimary, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("投屏", color = SakuraPrimary, fontSize = 12.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(currentSource.episodes) { index, episode ->
                                        val selected = index == state.currentEpisodeIndex
                                        Surface(shape = RoundedCornerShape(8.dp), color = if (selected) SakuraPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant, border = if (selected) ButtonDefaults.outlinedButtonBorder else null, modifier = Modifier.clickable { viewModel.selectEpisode(index) }) {
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
    onSeekCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var gestureVolume by remember { mutableFloatStateOf(1f) }
    var gestureBrightness by remember { mutableFloatStateOf(0.5f) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(showVolumeIndicator) {
        if (showVolumeIndicator) { delay(800); showVolumeIndicator = false }
    }
    LaunchedEffect(showBrightnessIndicator) {
        if (showBrightnessIndicator) { delay(800); showBrightnessIndicator = false }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setOnTouchListener { _, _ -> false }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { _ ->
                            gestureVolume = exoPlayer.volume
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
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (dragAmount.y == 0f) return@detectDragGestures
                            val delta = -dragAmount.y / size.height
                            if (change.position.x > size.width / 2) {
                                gestureVolume = (gestureVolume + delta * 2f).coerceIn(0f, 1f)
                                exoPlayer.volume = gestureVolume
                                showVolumeIndicator = true
                            } else {
                                gestureBrightness = (gestureBrightness + delta * 2f).coerceIn(0f, 1f)
                                val layoutParams = activity?.window?.attributes
                                layoutParams?.screenBrightness = gestureBrightness
                                activity?.window?.attributes = layoutParams
                                showBrightnessIndicator = true
                            }
                        },
                        onDragEnd = { },
                        onDragCancel = { }
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val upBeforeTimeout = withTimeoutOrNull(500L) {
                            waitForUpOrCancellation()
                        }
                        if (upBeforeTimeout == null) {
                            onLongPressSpeedChanged(true)
                            waitForUpOrCancellation()
                            onLongPressSpeedChanged(false)
                        } else {
                            onToggleControls()
                        }
                    }
                }
        )

        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureVolume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(60.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureVolume)
                                .align(Alignment.BottomStart)
                                .background(SakuraPrimary, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureBrightness < 0.01f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh,
                        null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(60.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureBrightness)
                                .align(Alignment.BottomStart)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (isLongPressSpeed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("长按2倍加速中", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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

        if (showControls || !isPlaying) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onExitFullscreen, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出全屏", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(videoName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (episodeName.isNotBlank()) {
                                Text(episodeName, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!isLocked) {
                            Surface(
                                modifier = Modifier.clickable(onClick = onCastClick),
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Cast, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("投屏", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        if (isLocked) "解锁" else "锁定",
                        tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                }

                if (!isLocked && !isBuffering) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isPlaying) {
                            IconButton(
                                onClick = { exoPlayer.pause() },
                                modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        } else {
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier.size(56.dp).background(SakuraPrimary.copy(alpha = 0.9f), CircleShape)
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, tint = DarkBackground, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }

                if (isLocked && showControls) {
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

                if (!isLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
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
                            Spacer(modifier = Modifier.weight(1f))
                            Surface(
                                modifier = Modifier.clickable(onClick = onEpisodePickerClick),
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ListAlt, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("选集", color = Color.White, fontSize = 12.sp)
                                }
                            }
                            Surface(
                                modifier = Modifier.clickable(onClick = onSpeedClick),
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Black.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.Speed, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("倍速", color = Color.White, fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = onExitFullscreen, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.FullscreenExit, "退出全屏", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekableProgressControl(exoPlayer: ExoPlayer, compact: Boolean = false, mediaLoaded: Boolean = false, onSeekingChanged: ((Boolean) -> Unit)? = null, onSeekCompleted: (() -> Unit)? = null) {
    var tick by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableStateOf(0f) }
    var currentDuration by remember { mutableStateOf(0L) }

    LaunchedEffect(isSeeking) {
        onSeekingChanged?.invoke(isSeeking)
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking) {
                tick = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentDuration = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(1000)
        }
    }

    val isDurationValid = currentDuration > 0
    val duration = if (isDurationValid) currentDuration else 1L

    val progress = if (isSeeking) seekFraction
        else if (!isDurationValid) 0f
        else (tick.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    val showThumb = mediaLoaded && (isDurationValid || exoPlayer.currentMediaItem != null)
    val trackHeight = if (compact) 2.dp else 3.dp

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(if (isSeeking) (seekFraction * duration).toLong().coerceAtLeast(0L) else tick.coerceAtLeast(0L)),
            color = Color.White, fontSize = if (compact) 10.sp else 11.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            isSeeking = true
                            seekFraction = fraction
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            seekFraction = fraction
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
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(trackHeight / 2)),
                color = SakuraPrimary,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
            if (showThumb) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress.coerceAtLeast(0.01f)),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(SakuraPrimary, CircleShape)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(formatDuration(duration), color = Color.White.copy(alpha = 0.7f), fontSize = if (compact) 10.sp else 11.sp)
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
                            gestureVolume = exoPlayer.volume
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
                                gestureVolume = (gestureVolume + delta * 2f).coerceIn(0f, 1f)
                                exoPlayer.volume = gestureVolume
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
                        awaitFirstDown(requireUnconsumed = false)
                        val upBeforeTimeout = withTimeoutOrNull(500L) {
                            waitForUpOrCancellation()
                        }
                        if (upBeforeTimeout == null) {
                            onLongPressSpeedChanged(true)
                            waitForUpOrCancellation()
                            onLongPressSpeedChanged(false)
                        } else {
                            onToggleControls()
                        }
                    }
                }
        )

        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureVolume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(60.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureVolume)
                                .align(Alignment.BottomStart)
                                .background(SakuraPrimary, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (gestureBrightness < 0.01f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh,
                        null, tint = Color.White, modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(60.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(gestureBrightness)
                                .align(Alignment.BottomStart)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("选择播放速度", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                speeds.forEach { speed ->
                    val selected = abs(speed - currentSpeed) < 0.01f
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onSpeedSelected(speed) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) SakuraPrimary.copy(alpha = 0.2f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (speed == 1.0f) "正常" else "${speed}x",
                                color = if (selected) SakuraPrimary else TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(Icons.Filled.Check, null, tint = SakuraPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextTertiary) } }
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastDeviceSheet(
    videoUrl: String?,
    videoTitle: String,
    resumePositionMs: Long,
    onDismiss: () -> Unit,
    onCastStateChanged: (Boolean) -> Unit,
    onNextEpisode: () -> Unit,
    onPrevEpisode: () -> Unit,
    currentEpisodeIndex: Int,
    totalEpisodes: Int,
    getEpisodeUrl: () -> String?,
    onStopCastingRegistered: (() -> Unit) -> Unit,
    onSaveCastingProgress: (currentMs: Long, totalMs: Long) -> Unit
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
        onCastStateChanged(false)
        val sid = connectedSessionId
        val server = proxyServer
        connectedSessionId = null
        proxyServer = null
        scope.launch(Dispatchers.IO) {
            try { DLNACast.stop() } catch (_: Exception) { }
            sid?.let { DlnaProxyService.sessionManager?.destroySession(it) }
            server?.stop()
            DlnaProxyService.stop(context)
        }
    }

    SideEffect {
        onStopCastingRegistered(stopCasting)
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
            devices = DLNACast.search(timeout = 5000)
        } catch (_: Exception) {
            castError = "搜索设备失败，请确保手机与电视在同一WiFi网络"
        }
        isSearching = false
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isCasting) onDismiss() },
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
                    // Casting in progress UI
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            tint = SakuraPrimary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            castingDeviceName ?: "设备",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            castProgress,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (castPlaybackState.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "状态: $castPlaybackState",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                stopCasting()
                                scope.launch {
                                    isSearching = true
                                    castError = null
                                    try { devices = DLNACast.search(timeout = 5000) }
                                    catch (_: Exception) { castError = "搜索失败" }
                                    isSearching = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2D2D3D),
                                contentColor = Color(0xFFFF5252)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("断开连接", fontSize = 14.sp)
                        }

                        if (totalEpisodes > 1) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "第 ${currentEpisodeIndex + 1} / $totalEpisodes 集",
                                color = TextTertiary,
                                fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        pendingSwitchDirection = -1
                                    },
                                    enabled = currentEpisodeIndex > 0 && !isSwitchingEpisode,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)
                                ) {
                                    Icon(Icons.Filled.SkipPrevious, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("上一集", fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingSwitchDirection = 1
                                    },
                                    enabled = currentEpisodeIndex < totalEpisodes - 1 && !isSwitchingEpisode,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)
                                ) {
                                    Text("下一集", fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Filled.SkipNext, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
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

                                                // Create proxy session
                                                val proxySession = withContext(Dispatchers.IO) {
                                                    try {
                                                        sessionManager.createSession(videoUrl, resumePositionMs)
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
                                                val proxyUrl = server.getStreamUrl(proxySession.sessionId)

                                                // Cast proxy URL to device
                                                val success = DLNACast.castToDevice(device, proxyUrl, videoTitle)
                                                if (success) {
                                                    isCasting = true
                                                    connectedSessionId = proxySession.sessionId
                                                    isConnecting = false
                                                    onCastStateChanged(true)

                                                    // switchEpisode helper — stops current cast, switches episode, creates new session, re-casts
                                                    suspend fun switchEpisode(isNext: Boolean): Boolean {
                                                        isSwitchingEpisode = true
                                                        try {
                                                            DLNACast.stop()
                                                            connectedSessionId?.let { sid ->
                                                                DlnaProxyService.sessionManager?.destroySession(sid)
                                                            }
                                                            connectedSessionId = null

                                                            if (isNext) onNextEpisode() else onPrevEpisode()
                                                            delay(500)

                                                            val newUrl = getEpisodeUrl()
                                                            if (newUrl != null) {
                                                                val newSession = withContext(Dispatchers.IO) {
                                                                    DlnaProxyService.sessionManager?.createSession(newUrl, 0L)
                                                                }
                                                                if (newSession != null) {
                                                                    val activeServer = proxyServer
                                                                        ?: DlnaProxyServer().also {
                                                                            it.start()
                                                                            proxyServer = it
                                                                        }
                                                                    val newProxyUrl = activeServer.getStreamUrl(newSession.sessionId)
                                                                    connectedSessionId = newSession.sessionId
                                                                    val dev = currentCastDevice
                                                                    if (dev != null) {
                                                                        val ok = DLNACast.castToDevice(dev, newProxyUrl, videoTitle)
                                                                        if (ok) {
                                                                            isSwitchingEpisode = false
                                                                            return true
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } catch (_: Exception) { }
                                                        isSwitchingEpisode = false
                                                        return false
                                                    }

                                                    // Continuous progress polling with auto-next
                                                    var lastPlaybackState = ""
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

                                                            val prog = DLNACast.getProgress()
                                                            var shouldAutoNext = false
                                                            var sessionComplete = false

                                                            // Check proxy session completion (most reliable for M3U8 streams)
                                                            val sid = connectedSessionId
                                                            if (sid != null) {
                                                                sessionComplete = DlnaProxyService.sessionManager?.isSessionComplete(sid) ?: false
                                                            }

                                                            if (prog != null) {
                                                                val currentMs = prog.first
                                                                val totalMs = prog.second
                                                                castProgress = "${formatMs(currentMs)} / ${formatMs(totalMs)}"

                                                                // Auto-next when near end
                                                                if (totalMs > 0 && currentMs >= totalMs - 3000 && currentEpisodeIndex < totalEpisodes - 1) {
                                                                    shouldAutoNext = true
                                                                }

                                                                // Save casting progress to history every ~15s
                                                                if (pollCount % 5 == 0 && currentMs > 0) {
                                                                    onSaveCastingProgress(currentMs, totalMs)
                                                                }
                                                            }
                                                            // Fallback: session complete + TV near end
                                                            if (sessionComplete && currentEpisodeIndex < totalEpisodes - 1) {
                                                                shouldAutoNext = true
                                                            }
                                                            val st = DLNACast.getState()
                                                            castPlaybackState = st.playbackState.name

                                                            // Detect TV playback stopped (not user-initiated)
                                                            // Require 2 consecutive STOPPED states to avoid false triggers
                                                            if (st.playbackState.name == "STOPPED" && !userStopped) {
                                                                stoppedCount++
                                                                if (stoppedCount >= 2 && currentEpisodeIndex < totalEpisodes - 1) {
                                                                    shouldAutoNext = true
                                                                }
                                                            } else {
                                                                stoppedCount = 0
                                                            }
                                                            lastPlaybackState = st.playbackState.name

                                                            if (shouldAutoNext && !userStopped) {
                                                                val switched = switchEpisode(isNext = true)
                                                                if (!switched) break
                                                                stoppedCount = 0
                                                            }
                                                        } catch (_: Exception) { }
                                                    }
                                                } else {
                                                    // Cast failed, clean up
                                                    sessionManager.destroySession(proxySession.sessionId)
                                                    server.stop()
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
                            try { devices = DLNACast.search(timeout = 5000) }
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

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
