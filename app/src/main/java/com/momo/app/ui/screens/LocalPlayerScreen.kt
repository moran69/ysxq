package com.momo.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import com.yinnho.upnpcast.DLNACast
import com.momo.app.data.download.DownloadTask
import com.momo.app.data.proxy.DirectDlnaCaster
import com.momo.app.data.proxy.DlnaProxyServer
import com.momo.app.data.proxy.DlnaProxyService
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.LocalPlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private suspend fun searchDlnaDevices(context: Context): List<DLNACast.Device> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val multicastLock = wifiManager.createMulticastLock("local-cast-discovery")
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
fun LocalPlayerScreen(
    videoId: Int,
    initialEpisodeIndex: Int = 0,
    onBack: () -> Unit,
    viewModel: LocalPlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showCastSheet by remember { mutableStateOf(false) }
    var showFullscreenEpisodes by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var hasMediaLoaded by remember { mutableStateOf(false) }
    var isCasting by remember { mutableStateOf(false) }
    var wasPlayingBeforeStop by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var showCastExitDialog by remember { mutableStateOf(false) }
    var stopCastingCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var isLongPressSpeed by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(1f) }
    var castDeviceName by remember { mutableStateOf<String?>(null) }
    var castProgress by remember { mutableStateOf("00:00 / 00:00") }
    var castPlaybackState by remember { mutableStateOf("") }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var castCommandHandler by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var castDevice by remember { mutableStateOf<DLNACast.Device?>(null) }
    var castPositionMs by remember { mutableLongStateOf(0L) }
    var castDurationMs by remember { mutableLongStateOf(0L) }
    var castSeekHandler by remember { mutableStateOf<((Long) -> Unit)?>(null) }

    // 投屏停止与遥控命令注册（此前 handler 从未注册，面板按钮全部无效——已修复）
    val stopCasting: () -> Unit = {
        isCasting = false
        castDeviceName = null
        castPlaybackState = ""
        val dev = castDevice
        castDevice = null
        scope.launch(Dispatchers.IO) {
            try { dev?.let { DirectDlnaCaster.stop(it) } } catch (_: Exception) { }
            try { DLNACast.stop() } catch (_: Exception) { }
            DirectDlnaCaster.clearCache()
            try {
                DlnaProxyService.proxyServer?.shutdown()
                DlnaProxyService.proxyServer = null
            } catch (_: Exception) { }
        }
    }
    fun castCurrentEpisode(dev: DLNACast.Device) {
        scope.launch {
            try {
                delay(600) // 等 ViewModel 切集就绪
                val file = viewModel.state.value.currentFile ?: return@launch
                val server = DlnaProxyService.proxyServer ?: DlnaProxyServer().also {
                    it.start(); DlnaProxyService.proxyServer = it
                }
                val fileUrl = server.getLocalFileUrl(file.absolutePath)
                DirectDlnaCaster.cast(dev, fileUrl, viewModel.state.value.videoName)
            } catch (_: Exception) { }
        }
    }
    val handleCastCommand: (Int) -> Unit = { action ->
        when (action) {
            0 -> stopCasting()
            -1 -> if (state.currentEpisodeIndex > 0) {
                viewModel.selectEpisode(state.currentEpisodeIndex - 1)
                castDevice?.let { castCurrentEpisode(it) }
            }
            1 -> if (state.currentEpisodeIndex < state.episodes.size - 1) {
                viewModel.selectEpisode(state.currentEpisodeIndex + 1)
                castDevice?.let { castCurrentEpisode(it) }
            }
            2 -> scope.launch(Dispatchers.IO) { try { DirectDlnaCaster.pause() } catch (_: Exception) { } }
            3 -> scope.launch(Dispatchers.IO) { try { DirectDlnaCaster.play() } catch (_: Exception) { } }
        }
    }
    SideEffect {
        stopCastingCallback = stopCasting
        castCommandHandler = handleCastCommand
    }

    LaunchedEffect(videoId) {
        viewModel.loadVideo(videoId, initialEpisodeIndex)
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(100_000, 100_000, 5000, 10_000)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
            .apply {
                playWhenReady = false
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) hasMediaLoaded = true
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playerError = error.message ?: "播放出错"
                    }
                })
            }
    }

    LaunchedEffect(state.currentFile, state.currentEpisodeIndex) {
        val file = state.currentFile
        if (file != null && file.exists()) {
            playerError = null
            hasMediaLoaded = false
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
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
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!isCasting) { wasPlayingBeforeStop = exoPlayer.isPlaying; exoPlayer.pause() }
                }
                Lifecycle.Event.ON_START -> {
                    if (!isCasting && wasPlayingBeforeStop) { exoPlayer.play(); wasPlayingBeforeStop = false }
                }
                else -> {}
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            isCasting = false
            exoPlayer.stop(); exoPlayer.clearMediaItems(); exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.let { act ->
                act.window.attributes = act.window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) { delay(3000); showControls = false }
    }

    BackHandler {
        if (isLocked) return@BackHandler
        if (isFullscreen) {
            isFullscreen = false; isLocked = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        } else if (isCasting) {
            showCastExitDialog = true
        } else {
            playerViewRef?.player = null
            exoPlayer.stop(); exoPlayer.clearMediaItems()
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
                    playerViewRef?.player = null
                    exoPlayer.stop(); exoPlayer.clearMediaItems()
                    onBack()
                }) { Text("确定", color = SakuraPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showCastExitDialog = false }) { Text("取消", color = TextSecondary) }
            },
            containerColor = DarkSurface, shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("投屏已断开", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("与设备的连接已断开，请检查网络后重试", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { showDisconnectDialog = false; showCastSheet = true }) { Text("重新投屏", color = SakuraPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("关闭", color = TextSecondary) }
            },
            containerColor = DarkSurface, shape = RoundedCornerShape(16.dp)
        )
    }

    DisposableEffect(Unit) { onDispose { if (isCasting) stopCastingCallback?.invoke() } }

    if (isCasting) {
        LaunchedEffect(Unit) {
            while (isActive) {
                delay(3000)
                try {
                    val transport = DirectDlnaCaster.getTransportState() ?: "UNKNOWN"
                    castPlaybackState = transport
                    val prog = DirectDlnaCaster.getPositionInfo()
                    if (prog != null && (prog.first > 0 || prog.second > 0)) {
                        castPositionMs = prog.first
                        castDurationMs = prog.second
                        castProgress = "${formatLocalDuration(prog.first)} / ${formatLocalDuration(prog.second)}"
                    }
                    if (transport == "STOPPED") {
                        isCasting = false; showDisconnectDialog = true
                    }
                } catch (_: Exception) {}
            }
        }
    }

    if (showSpeedDialog) {
        LocalSpeedSelectionDialog(
            currentSpeed = exoPlayer.playbackParameters.speed,
            onSpeedSelected = { speed ->
                exoPlayer.setPlaybackParameters(androidx.media3.common.PlaybackParameters(speed))
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showFullscreenEpisodes) {
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
                        itemsIndexed(state.episodes) { index, task ->
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
                                    task.episodeName,
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

    if (showCastSheet) {
        LocalCastDeviceSheet(
            state = state,
            exoPlayer = exoPlayer,
            context = context,
            scope = scope,
            onDismiss = { showCastSheet = false },
            onCastStarted = { device ->
                castDevice = device
                isCasting = true; castDeviceName = device.name; showCastSheet = false
                if (isFullscreen) {
                    isFullscreen = false; isLocked = false
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    activity?.let { act ->
                        WindowCompat.getInsetsController(act.window, act.window.decorView)
                            .show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                if (exoPlayer.isPlaying) exoPlayer.pause()
            },
            onCastError = { /* handled inside sheet */ }
        )
    }

    if (isFullscreen) {
        LocalFullscreenPlayer(
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
                if (!isLocked) showControls = true
            },
            onTogglePlayPause = { togglePlayPause() },
            onExitFullscreen = {
                isFullscreen = false; isLocked = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
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
            hasNextEpisode = state.currentEpisodeIndex < state.episodes.size - 1,
            videoName = state.videoName,
            episodeName = state.episodes.getOrNull(state.currentEpisodeIndex)?.episodeName ?: ""
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            TopAppBar(
                title = { Text(state.videoName.ifEmpty { "本地播放" }, color = TextPrimary, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isCasting) showCastExitDialog = true
                        else { playerViewRef?.player = null; exoPlayer.stop(); exoPlayer.clearMediaItems(); onBack() }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground.copy(alpha = 0.95f))
            )

            if (state.error != null) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Error, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(state.error!!, color = TextTertiary, fontSize = 14.sp)
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    // Player area — matches DetailScreen layout
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                        if (!isCasting) {
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
                        }

                        // Casting overlay
                        if (isCasting) {
                            LocalCastingControlPanel(
                                videoTitle = state.videoName,
                                episodeName = state.episodes.getOrNull(state.currentEpisodeIndex)?.episodeName ?: "",
                                deviceName = castDeviceName ?: "设备",
                                positionMs = castPositionMs,
                                durationMs = castDurationMs,
                                playbackState = castPlaybackState,
                                currentEpisodeIndex = state.currentEpisodeIndex,
                                totalEpisodes = state.episodes.size,
                                onPrevEpisode = { castCommandHandler?.invoke(-1) },
                                onStopCast = { castCommandHandler?.invoke(0) },
                                onNextEpisode = { castCommandHandler?.invoke(1) },
                                onTogglePlayPause = { castCommandHandler?.invoke(if (castPlaybackState == "PLAYING") 2 else 3) },
                                onSeek = { targetMs -> castSeekHandler?.invoke(targetMs) }
                            )
                        }

                        if (!isCasting && isBuffering) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = SakuraPrimary, strokeWidth = 3.dp)
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
                                        playerError = null; hasMediaLoaded = false
                                        state.currentFile?.let { f ->
                                            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                                            exoPlayer.prepare(); exoPlayer.playWhenReady = true
                                        }
                                    }, colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary)) { Text("重试") }
                                }
                            }
                        }

                        if (!isCasting) {
                            // Gesture overlay — same as DetailScreen's InlinePlayerGestureOverlay
                            LocalInlineGestureOverlay(
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

                            // Locked indicator
                            if (showControls && isLocked) {
                                Box(
                                    modifier = Modifier.align(Alignment.Center)
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

                            if (!isLocked && (showControls || !isPlaying)) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // Cast button — top right
                                    Row(
                                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
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

                                    // Play/pause center button
                                    if (hasMediaLoaded && !isBuffering) {
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

                                    // Bottom progress bar + fullscreen
                                    Column(
                                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
                                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        LocalSeekableProgressControl(
                                            exoPlayer = exoPlayer,
                                            compact = true,
                                            mediaLoaded = hasMediaLoaded
                                        )
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

                    // Video info section — matches DetailScreen exactly
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(state.videoName, fontSize = 22.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LocalTagChip("本地下载", SakuraPrimary)
                            val task = state.episodes.getOrNull(state.currentEpisodeIndex)
                            if (task != null) {
                                val sizeMB = task.downloadedBytes / (1024.0 * 1024)
                                LocalTagChip("%.1f MB".format(sizeMB), SkyBlue)
                            }
                        }
                    }

                    // Episode grid — matches DetailScreen layout
                    if (state.episodes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("选集 (${state.episodes.size})", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(state.episodes) { index, task ->
                                val selected = index == state.currentEpisodeIndex
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) SakuraPrimary.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                    border = if (selected) ButtonDefaults.outlinedButtonBorder else null,
                                    modifier = Modifier.clickable { viewModel.selectEpisode(index) }
                                ) {
                                    Text(
                                        task.episodeName,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                        color = if (selected) SakuraPrimary else TextSecondary,
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
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

// ---- Private components matching DetailScreen's design ----

@Composable
private fun LocalTagChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LocalSeekableProgressControl(
    exoPlayer: ExoPlayer,
    compact: Boolean = false,
    mediaLoaded: Boolean = false,
    onSeekCompleted: (() -> Unit)? = null
) {
    var tick by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableStateOf(0f) }
    var currentDuration by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking) {
                tick = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentDuration = exoPlayer.duration.coerceAtLeast(0L)
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
        label = "localSeekThumb"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        // 拖动时的时间预览气泡
        if (isSeeking && isDurationValid) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    formatLocalDuration((seekFraction * duration).toLong()),
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
            Text(formatLocalDuration(if (isSeeking) (seekFraction * duration).toLong().coerceAtLeast(0L) else tick.coerceAtLeast(0L)),
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
                                isSeeking = true; seekFraction = fraction
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                seekFraction = (change.position.x / size.width).coerceIn(0f, 1f)
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
                    modifier = Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(trackHeight / 2)),
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
                                .size(thumbSize)
                                .shadow(3.dp, CircleShape)
                                .background(Color.White, CircleShape)
                                .border(2.dp, SakuraPrimary, CircleShape)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(formatLocalDuration(duration), color = Color.White.copy(alpha = 0.7f), fontSize = if (compact) 10.sp else 11.sp)
        }
    }
}

@Composable
private fun LocalInlineGestureOverlay(
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
                                val sysBrightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 125)
                                (sysBrightness / 255f).coerceIn(0f, 1f)
                            }
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
                        onDragEnd = { },
                        onDragCancel = { }
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
                                currentEvent.changes.firstOrNull()?.let { if (it.pressed) it else null }
                            }
                            if (secondDown != null) {
                                waitForUpOrCancellation()
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            } else {
                                onToggleControls()
                            }
                        }
                    }
                }
        )

        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
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
                    Box(modifier = Modifier.width(3.dp).height(60.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(gestureVolume).align(Alignment.BottomStart).background(SakuraPrimary, RoundedCornerShape(2.dp)))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
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
                    Box(modifier = Modifier.width(3.dp).height(60.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(gestureBrightness).align(Alignment.BottomStart).background(Color.White, RoundedCornerShape(2.dp)))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun LocalFullscreenPlayer(
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
    episodeName: String
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var gestureVolume by remember { mutableFloatStateOf(1f) }
    var gestureBrightness by remember { mutableFloatStateOf(0.5f) }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var showBrightnessIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(showVolumeIndicator) { if (showVolumeIndicator) { delay(800); showVolumeIndicator = false } }
    LaunchedEffect(showBrightnessIndicator) { if (showBrightnessIndicator) { delay(800); showBrightnessIndicator = false } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = false; setOnTouchListener { _, _ -> false } } },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture layer
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { _ ->
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            gestureVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val winBrightness = activity?.window?.attributes?.screenBrightness
                            gestureBrightness = if (winBrightness != null && winBrightness >= 0f) winBrightness
                            else (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 125) / 255f).coerceIn(0f, 1f)
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
                                activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = gestureBrightness }
                                showBrightnessIndicator = true
                            }
                        },
                        onDragEnd = { }, onDragCancel = { }
                    )
                }
                .pointerInput(isLocked) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val firstUp = withTimeoutOrNull(300L) { waitForUpOrCancellation() }
                        if (firstUp == null) {
                            onLongPressSpeedChanged(true); waitForUpOrCancellation(); onLongPressSpeedChanged(false)
                        } else {
                            if (isLocked) { onToggleControls() } else {
                                val secondDown = withTimeoutOrNull(300L) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                    currentEvent.changes.firstOrNull()?.let { if (it.pressed) it else null }
                                }
                                if (secondDown != null) { waitForUpOrCancellation(); onTogglePlayPause() }
                                else { onToggleControls() }
                            }
                        }
                    }
                }
        )

        // Volume indicator
        if (showVolumeIndicator && gestureVolume >= 0f) {
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (gestureVolume < 0.01f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.width(3.dp).height(60.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(gestureVolume).align(Alignment.BottomStart).background(SakuraPrimary, RoundedCornerShape(2.dp)))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Brightness indicator
        if (showBrightnessIndicator && gestureBrightness >= 0f) {
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (gestureBrightness < 0.01f) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.width(3.dp).height(60.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(gestureBrightness).align(Alignment.BottomStart).background(Color.White, RoundedCornerShape(2.dp)))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(gestureBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Long press speed indicator
        if (isLongPressSpeed) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("长按2倍加速中", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Buffering indicator
        if (isBuffering) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = SakuraPrimary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (!hasMediaLoaded) "视频加载中..." else "缓冲中...", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
            }
        }

        // Controls overlay
        if (showControls || !isPlaying) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                            Surface(modifier = Modifier.clickable(onClick = onCastClick), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.5f)) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Cast, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("投屏", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Lock button
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
                        .size(40.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen, if (isLocked) "解锁" else "锁定", tint = Color.White, modifier = Modifier.size(22.dp))
                }

                // Center play/pause
                if (!isLocked && !isBuffering) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isPlaying) {
                            IconButton(onClick = { exoPlayer.pause() }, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                                Icon(Icons.Filled.Pause, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        } else {
                            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(56.dp).background(SakuraPrimary.copy(alpha = 0.9f), CircleShape)) {
                                Icon(Icons.Filled.PlayArrow, null, tint = DarkBackground, modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }

                // Bottom bar
                if (!isLocked) {
                    Column(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            LocalSeekableProgressControl(exoPlayer = exoPlayer, compact = false, mediaLoaded = true)
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            Surface(modifier = Modifier.clickable(onClick = onEpisodePickerClick), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.5f)) {
                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.ListAlt, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("选集", color = Color.White, fontSize = 12.sp)
                                }
                            }
                            Surface(modifier = Modifier.clickable(onClick = onSpeedClick), shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.5f)) {
                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
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
@OptIn(ExperimentalMaterial3Api::class)
private fun LocalSpeedSelectionDialog(
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

@Composable
private fun LocalCastingControlPanel(
    videoTitle: String,
    episodeName: String,
    deviceName: String,
    positionMs: Long,
    durationMs: Long,
    playbackState: String,
    currentEpisodeIndex: Int,
    totalEpisodes: Int,
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Tv, null, tint = SakuraPrimary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text("投屏中", color = SakuraPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(deviceName, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(videoTitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (episodeName.isNotBlank()) {
                Text(episodeName, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LocalCastSeekBar(positionMs = displayMs, durationMs = durationMs, onSeek = onSeek)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (currentEpisodeIndex > 0) {
                    IconButton(onClick = onPrevEpisode, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) {
                        Icon(Icons.Filled.SkipPrevious, "上一集", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                ) {
                    Icon(
                        if (playbackState == "PLAYING") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState == "PLAYING") "暂停" else "播放",
                        tint = Color.White, modifier = Modifier.size(26.dp)
                    )
                }
                if (currentEpisodeIndex < totalEpisodes - 1) {
                    IconButton(onClick = onNextEpisode, modifier = Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)) {
                        Icon(Icons.Filled.SkipNext, "下一集", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = onStopCast, modifier = Modifier.size(40.dp).background(Color(0xFFFF5252).copy(alpha = 0.2f), CircleShape)) {
                Icon(Icons.Filled.Close, "停止投屏", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 本地播放投屏进度条：拖动结束通过 DLNA REL_TIME Seek 让 TV 跳转。
 */
@Composable
private fun LocalCastSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(formatLocalDuration(positionMs), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        Slider(
            value = if (isDragging) dragFraction else fraction,
            onValueChange = { isDragging = true; dragFraction = it },
            onValueChangeFinished = {
                isDragging = false
                if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
            },
            enabled = durationMs > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = SakuraPrimary,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            )
        )
        Text(formatLocalDuration(durationMs), color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
    }
}

@Composable
private fun LocalCastDeviceSheet(
    state: com.momo.app.viewmodel.LocalPlayerState,
    exoPlayer: ExoPlayer,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onCastStarted: (DLNACast.Device) -> Unit,
    onCastError: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投屏到设备", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            var devices by remember { mutableStateOf<List<DLNACast.Device>>(emptyList()) }
            var isSearching by remember { mutableStateOf(true) }
            var castError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                isSearching = true
                devices = withContext(Dispatchers.IO) { searchDlnaDevices(context) }
                isSearching = false
            }

            Column {
                if (isSearching) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SakuraPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("搜索设备中...", color = TextSecondary, fontSize = 14.sp)
                    }
                } else if (devices.isEmpty()) {
                    Text("未找到设备，请确保手机和电视在同一WiFi下", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    devices.forEach { device ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                scope.launch {
                                    try {
                                        val file = state.currentFile ?: return@launch
                                        val server = DlnaProxyService.proxyServer ?: DlnaProxyServer().also {
                                            it.start(); DlnaProxyService.proxyServer = it
                                        }
                                        val fileUrl = server.getLocalFileUrl(file.absolutePath)
                                        // DirectDlnaCaster: 正确 DIDL + 缓存 control URL（支持后续暂停/进度遥控）
                                        val ok = DirectDlnaCaster.cast(device, fileUrl, state.videoName)
                                        if (ok) {
                                            onCastStarted(device)
                                            if (exoPlayer.isPlaying) exoPlayer.pause()
                                        } else {
                                            castError = "投屏失败"
                                        }
                                    } catch (e: Exception) { castError = e.message ?: "投屏失败" }
                                }
                            },
                            color = DarkSurfaceVariant, shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Cast, null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(device.name, color = TextPrimary, fontSize = 14.sp)
                            }
                        }
                    }
                }
                if (castError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(castError!!, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = TextTertiary) } },
        containerColor = DarkSurface
    )
}

private fun formatLocalDuration(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}
