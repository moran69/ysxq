package com.momo.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.momo.app.data.auth.AuthState
import com.momo.app.data.storage.CloudBaseStorageHelper
import com.momo.app.data.update.UpdateChecker
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    onLoginClick: () -> Unit,
    onFavoritesClick: () -> Unit = {},
    onWatchHistoryClick: () -> Unit = {},
    onProfileEditClick: () -> Unit = {},
    onFeedback: () -> Unit = {},
    onAbout: () -> Unit = {},
    viewModel: ProfileViewModel = viewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val isGuest by viewModel.isGuest.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    val photoUrl = (authState as? AuthState.Authenticated)?.user?.photoUrl
    val persistedResolvedUrl by viewModel.resolvedAvatarUrl.collectAsState()
    var resolvedPhotoUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(photoUrl) {
        if (photoUrl != null && !photoUrl.startsWith("cloud://")) {
            resolvedPhotoUrl = photoUrl
            viewModel.updatePersistedAvatarUrl(photoUrl)
        } else {
            val resolved = CloudBaseStorageHelper.resolveAvatarUrl(photoUrl)
            if (resolved != null) {
                resolvedPhotoUrl = resolved
                viewModel.updatePersistedAvatarUrl(resolved)
            }
        }
    }
    val displayAvatarUrl = resolvedPhotoUrl ?: persistedResolvedUrl

    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.updateAvatar(it) }
    }

    var showLoginDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isCacheCleared) {
        if (uiState.isCacheCleared) {
            snackbarHostState.showSnackbar("缓存已清除")
            viewModel.dismissCacheCleared()
        }
    }

    LaunchedEffect(uiState.sessionExpiredMessage) {
        val msg = uiState.sessionExpiredMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissSessionExpired()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.calculateCacheSize()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            when (authState) {
                is AuthState.Authenticated ->                 AuthenticatedContent(
                    viewModel = viewModel,
                    displayAvatarUrl = displayAvatarUrl,
                    onAvatarClick = onProfileEditClick,
                    onLogoutClick = { viewModel.showLogoutDialog() },
                    onFavoritesClick = onFavoritesClick,
                    onWatchHistoryClick = onWatchHistoryClick,
                    onProfileEditClick = onProfileEditClick,
                    onFeedback = onFeedback,
                    onAbout = onAbout
                )
                is AuthState.Unauthenticated -> GuestContent(
                    isGuest = isGuest,
                    onLoginClick = onLoginClick,
                    onRequireLogin = { showLoginDialog = true },
                    onFeedback = onFeedback,
                    onAbout = onAbout,
                    viewModel = viewModel
                )
                AuthState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SakuraPrimary)
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            title = { Text("提示", color = TextPrimary) },
            text = { Text("请先登录后使用此功能", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showLoginDialog = false; onLoginClick() },
                    colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                ) {
                    Text("去登录", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoginDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    if (uiState.showLogoutDialog) {
        LogoutDialog(
            onConfirm = { viewModel.signOut() },
            onDismiss = { viewModel.dismissLogoutDialog() }
        )
    }

    if (uiState.showEditProfileDialog) {
        EditProfileDialog(
            nickname = uiState.editNickname,
            isUpdating = uiState.isUpdating,
            error = uiState.updateError,
            localAvatarUri = uiState.localAvatarUri,
            fallbackAvatarUrl = displayAvatarUrl,
            onNicknameChange = { viewModel.onNicknameChange(it) },
            onAvatarClick = { avatarLauncher.launch("image/*") },
            onConfirm = { viewModel.updateNickname() },
            onDismiss = { viewModel.dismissEditProfileDialog() }
        )
    }
}

@Composable
private fun AuthenticatedContent(
    viewModel: ProfileViewModel,
    displayAvatarUrl: String?,
    onAvatarClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onWatchHistoryClick: () -> Unit,
    onProfileEditClick: () -> Unit = {},
    onFeedback: () -> Unit = {},
    onAbout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.user
    if (user == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = SakuraPrimary)
        }
        return
    }
    val avatarUri = uiState.localAvatarUri

    var showDevDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // 更新检查状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.momo.app.data.update.AppVersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun checkUpdate() {
        isCheckingUpdate = true
        updateCheckMessage = null
        scope.launch {
            val info = UpdateChecker.checkForUpdate()
            isCheckingUpdate = false
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            } else {
                updateCheckMessage = "已是最新版本"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkSurface, DarkBackground)
                )
            )
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable { onAvatarClick() },
                contentAlignment = Alignment.Center
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = null,
                        error = null,
                        fallback = null
                    )
                } else if (!displayAvatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = displayAvatarUrl,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = null,
                        error = null,
                        fallback = null
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonOutline,
                            contentDescription = "头像",
                            tint = TextTertiary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName.ifBlank { "用户" },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // VIP 徽章
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFFFD700), Color(0xFFFFA500))
                                )
                            )
                    ) {
                        Text(
                            text = "VIP 永久",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MenuSectionTitle("功能")
        MenuItem(icon = Icons.Outlined.FavoriteBorder, title = "我的收藏", onClick = onFavoritesClick)
        MenuItem(icon = Icons.Outlined.History, title = "观看历史", onClick = onWatchHistoryClick)

        Spacer(modifier = Modifier.height(8.dp))
        MenuSectionTitle("设置")
        MenuItem(icon = Icons.Outlined.Palette, title = "主题设置", onClick = { showDevDialog = true })
        MenuItem(icon = Icons.Outlined.PlayCircleOutline, title = "播放设置", onClick = { showDevDialog = true })
        MenuItem(icon = Icons.Outlined.Email, title = "我要反馈", onClick = onFeedback)
        MenuItem(
            icon = Icons.Outlined.DeleteSweep,
            title = "清除缓存",
            subtitle = uiState.cacheSize.ifEmpty { "计算中..." },
            onClick = { showClearCacheDialog = true }
        )
        MenuItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            subtitle = if (isCheckingUpdate) "检查中..." else updateCheckMessage ?: "",
            onClick = { checkUpdate() }
        )
        MenuItem(icon = Icons.Outlined.Info, title = "关于", onClick = onAbout)

        Spacer(modifier = Modifier.height(16.dp))

        if (showDevDialog) {
            AlertDialog(
                onDismissRequest = { showDevDialog = false },
                title = { Text("提示", color = TextPrimary) },
                text = { Text("该功能正在开发中，敬请期待！", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { showDevDialog = false }) {
                        Text("好的", color = SakuraPrimary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("清除缓存", color = TextPrimary) },
                text = {
                    Text(
                        "确定要清除所有缓存吗？缓存大小：${uiState.cacheSize.ifEmpty { "未知" }}",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearCacheDialog = false
                            viewModel.clearCache()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                    ) {
                        Text("清除", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("取消", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        Button(
            onClick = onLogoutClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkSurfaceVariant,
                contentColor = Color(0xFFFF6B6B)
            )
        ) {
            Icon(
                imageVector = Icons.Filled.ExitToApp,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("退出登录", fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // 更新对话框
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) showUpdateDialog = false
            },
            title = { Text("发现新版本 v${updateInfo!!.versionName}", color = TextPrimary) },
            text = {
                Column {
                    Text(updateInfo!!.updateLog, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    if (isDownloadingUpdate) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = SakuraPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${(downloadProgress * 100).toInt()}%", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (isDownloadingUpdate) {
                    Text(text = "下载中...", color = TextTertiary)
                } else {
                    Button(
                        onClick = {
                            isDownloadingUpdate = true
                            scope.launch {
                                val apk = UpdateChecker.downloadApk(context, updateInfo!!.apkDownloadUrl) { progress ->
                                    downloadProgress = progress
                                }
                                isDownloadingUpdate = false
                                if (apk != null) {
                                    showUpdateDialog = false
                                    UpdateChecker.installApk(context, apk)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                    ) {
                        Text("立即更新", color = Color.White)
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("稍后", color = TextSecondary)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun GuestContent(
    isGuest: Boolean,
    onLoginClick: () -> Unit,
    onRequireLogin: () -> Unit,
    onFeedback: () -> Unit = {},
    onAbout: () -> Unit = {},
    viewModel: ProfileViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    var showDevDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // 更新检查状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.momo.app.data.update.AppVersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateCheckMessage by remember { mutableStateOf<String?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun checkUpdate() {
        isCheckingUpdate = true
        updateCheckMessage = null
        scope.launch {
            val info = UpdateChecker.checkForUpdate()
            isCheckingUpdate = false
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            } else {
                updateCheckMessage = "已是最新版本"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonOutline,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (isGuest) "游客模式" else "未登录",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "登录后可享受更多功能",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onLoginClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(PinkGradientStart, PinkGradientEnd)
                    ),
                    RoundedCornerShape(12.dp)
                ),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(
                text = "立即登录",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "部分功能可能受限",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MenuSectionTitle("功能")
        MenuItem(icon = Icons.Outlined.FavoriteBorder, title = "我的收藏", onClick = onRequireLogin)
        MenuItem(icon = Icons.Outlined.History, title = "观看历史", onClick = onRequireLogin)

        Spacer(modifier = Modifier.height(8.dp))
        MenuSectionTitle("设置")
        MenuItem(icon = Icons.Outlined.Palette, title = "主题设置", onClick = { showDevDialog = true })
        MenuItem(icon = Icons.Outlined.PlayCircleOutline, title = "播放设置", onClick = { showDevDialog = true })
        MenuItem(icon = Icons.Outlined.Email, title = "我要反馈", onClick = onFeedback)
        MenuItem(
            icon = Icons.Outlined.DeleteSweep,
            title = "清除缓存",
            subtitle = uiState.cacheSize.ifEmpty { "计算中..." },
            onClick = { showClearCacheDialog = true }
        )
        MenuItem(
            icon = Icons.Outlined.SystemUpdate,
            title = "检查更新",
            subtitle = if (isCheckingUpdate) "检查中..." else updateCheckMessage ?: "",
            onClick = { checkUpdate() }
        )
        MenuItem(icon = Icons.Outlined.Info, title = "关于", onClick = onAbout)

        Spacer(modifier = Modifier.height(32.dp))

        if (showDevDialog) {
            AlertDialog(
                onDismissRequest = { showDevDialog = false },
                title = { Text("提示", color = TextPrimary) },
                text = { Text("该功能正在开发中，敬请期待！", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { showDevDialog = false }) {
                        Text("好的", color = SakuraPrimary)
                    }
                },
                containerColor = DarkSurface
            )
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("清除缓存", color = TextPrimary) },
                text = {
                    Text(
                        "确定要清除所有缓存吗？缓存大小：${uiState.cacheSize.ifEmpty { "未知" }}",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearCacheDialog = false
                            viewModel.clearCache()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                    ) {
                        Text("清除", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("取消", color = TextSecondary)
                    }
                },
                containerColor = DarkSurface
            )
        }
    }

    // 更新对话框
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) showUpdateDialog = false
            },
            title = { Text("发现新版本 v${updateInfo!!.versionName}", color = TextPrimary) },
            text = {
                Column {
                    Text(updateInfo!!.updateLog, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    if (isDownloadingUpdate) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = SakuraPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${(downloadProgress * 100).toInt()}%", color = TextTertiary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (isDownloadingUpdate) {
                    Text(text = "下载中...", color = TextTertiary)
                } else {
                    Button(
                        onClick = {
                            isDownloadingUpdate = true
                            scope.launch {
                                val apk = UpdateChecker.downloadApk(context, updateInfo!!.apkDownloadUrl) { progress ->
                                    downloadProgress = progress
                                }
                                isDownloadingUpdate = false
                                if (apk != null) {
                                    showUpdateDialog = false
                                    UpdateChecker.installApk(context, apk)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                    ) {
                        Text("立即更新", color = Color.White)
                    }
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("稍后", color = TextSecondary)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun MenuSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = TextTertiary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = SakuraPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LogoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认退出", color = TextPrimary) },
        text = { Text("确定要退出登录吗？", color = TextSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
            ) {
                Text("退出", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = DarkSurface
    )
}

@Composable
private fun EditProfileDialog(
    nickname: String,
    isUpdating: Boolean,
    error: String?,
    localAvatarUri: Uri?,
    fallbackAvatarUrl: String?,
    onNicknameChange: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料", color = TextPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (localAvatarUri != null) {
                        AsyncImage(
                            model = localAvatarUri,
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (fallbackAvatarUrl != null) {
                        AsyncImage(
                            model = fallbackAvatarUrl,
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(SakuraPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = "头像",
                                tint = SakuraPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    if (isUpdating) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = SakuraPrimary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = nickname,
                    onValueChange = onNicknameChange,
                    label = { Text("昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SakuraPrimary,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedLabelColor = SakuraPrimary,
                        unfocusedLabelColor = TextTertiary,
                        cursorColor = SakuraPrimary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(error, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isUpdating,
                colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
            ) {
                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("保存", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = DarkSurface
    )
}
