package com.ysxq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ysxq.app.data.download.DownloadTask
import com.ysxq.app.data.download.DownloadStatus
import com.ysxq.app.viewmodel.DownloadViewModel
import com.ysxq.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlayLocal: ((videoId: Int, episodeIndex: Int) -> Unit)? = null
) {
    val viewModel: DownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state by viewModel.state.collectAsState()
    var selectedVideoId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf<DownloadTask?>(null) }
    var showVideoDeleteDialog by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        when {
            state.tasks.isEmpty() -> {
                TopAppBar(
                    title = { Text("我的下载", color = TextPrimary, fontSize = 18.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无下载内容", color = TextTertiary, fontSize = 16.sp)
                    }
                }
            }
            selectedVideoId == null -> {
                TopAppBar(
                    title = { Text("我的下载", color = TextPrimary, fontSize = 18.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )
                // 外层 — 视频列表（按下载时间降序）
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.groupedByVideo.entries.toList().sortedByDescending { it.value.maxOfOrNull { t -> t.createdAt } ?: 0 }) { (videoId, tasks) ->
                        val video = tasks.firstOrNull()
                        if (video != null) {
                            DownloadVideoItem(
                                video = video,
                                tasks = tasks,
                                onClick = { selectedVideoId = videoId },
                                onVideoDeleteClick = { showVideoDeleteDialog = videoId }
                            )
                        }
                    }
                }
            }
            else -> {
                // 内层 — 集数页面（带 Tab 分页）
                val videoTasks = state.groupedByVideo[selectedVideoId] ?: emptyList()
                val video = videoTasks.firstOrNull()

                if (video != null) {
                    val activeTasks = videoTasks.filter { it.status != DownloadStatus.COMPLETED.name }
                    val smartTab = if (activeTasks.isNotEmpty()) 0 else 1
                    EpisodeDetailPage(
                        video = video,
                        tasks = videoTasks,
                        initialTab = smartTab,
                        onBack = { selectedVideoId = null },
                        onPauseClick = { viewModel.pauseDownload(it) },
                        onResumeClick = { viewModel.resumeDownload(it) },
                        onDeleteClick = { showDeleteDialog = it },
                        onPlayLocal = if (onPlayLocal != null) {{ epIndex ->
                            onPlayLocal(selectedVideoId!!, epIndex)
                        }} else null
                    )
                } else {
                    selectedVideoId = null
                }
            }
        }
    }

    // 删除单集确认弹窗
    showDeleteDialog?.let { task ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除下载", color = TextPrimary) },
            text = { Text("确定要删除「${task.episodeName}」的下载吗？", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = null; viewModel.deleteDownload(task.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                ) { Text("删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消", color = TextTertiary) }
            },
            containerColor = DarkSurface
        )
    }

    // 删除整部视频确认弹窗
    showVideoDeleteDialog?.let { videoId ->
        AlertDialog(
            onDismissRequest = { showVideoDeleteDialog = null },
            title = { Text("删除视频下载", color = TextPrimary) },
            text = { Text("确定要删除「${state.groupedByVideo[videoId]?.firstOrNull()?.videoName ?: ""}」的所有下载吗？", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showVideoDeleteDialog = null; viewModel.deleteVideoDownloads(videoId) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                ) { Text("删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showVideoDeleteDialog = null }) { Text("取消", color = TextTertiary) }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun DownloadVideoItem(
    video: DownloadTask,
    tasks: List<DownloadTask>,
    onClick: () -> Unit,
    onVideoDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = video.videoPic,
                contentDescription = video.videoName,
                modifier = Modifier
                    .size(width = 100.dp, height = 65.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    video.videoName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val completedCount = tasks.count { it.status == DownloadStatus.COMPLETED.name }
                val totalCount = tasks.size
                Text("已下载 $completedCount/$totalCount 集", color = TextSecondary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                val totalSize = tasks.sumOf { it.totalBytes }
                if (totalSize > 0) {
                    Text(formatFileSize(totalSize), color = TextTertiary, fontSize = 11.sp)
                }
            }

            IconButton(onClick = onVideoDeleteClick) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = TextTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeDetailPage(
    video: DownloadTask,
    tasks: List<DownloadTask>,
    initialTab: Int = 0,
    onBack: () -> Unit,
    onPauseClick: (String) -> Unit,
    onResumeClick: (String) -> Unit,
    onDeleteClick: (DownloadTask) -> Unit,
    onPlayLocal: ((Int) -> Unit)?
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val activeTasks = tasks.filter { it.status != DownloadStatus.COMPLETED.name }
    val completedTasks = tasks.filter { it.status == DownloadStatus.COMPLETED.name }
        .sortedByDescending { it.downloadedBytes }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary, modifier = Modifier.size(22.dp))
            }
            Text(video.videoName, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkBackground,
            contentColor = SakuraPrimary,
            divider = { HorizontalDivider(color = DarkSurfaceVariant) }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("正在下载 (${activeTasks.size})", color = if (selectedTab == 0) SakuraPrimary else TextSecondary) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("已完成 (${completedTasks.size})", color = if (selectedTab == 1) SakuraPrimary else TextSecondary) }
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val displayTasks = if (selectedTab == 0) activeTasks else completedTasks
            items(displayTasks) { task ->
                if (selectedTab == 0) {
                    ActiveDownloadItem(
                        task = task,
                        onPauseClick = { onPauseClick(task.id) },
                        onResumeClick = { onResumeClick(task.id) },
                        onDeleteClick = { onDeleteClick(task) }
                    )
                } else {
                    CompletedDownloadItem(
                        task = task,
                        onPlayClick = if (onPlayLocal != null) {{
                            val idx = completedTasks.indexOf(task)
                            onPlayLocal(idx)
                        }} else null,
                        onDeleteClick = { onDeleteClick(task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadItem(
    task: DownloadTask,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (task.status) {
                        DownloadStatus.PENDING.name -> Icons.Filled.Schedule
                        DownloadStatus.DOWNLOADING.name -> Icons.Filled.Downloading
                        DownloadStatus.PAUSED.name -> Icons.Filled.Pause
                        DownloadStatus.FAILED.name -> Icons.Filled.Error
                        else -> Icons.Filled.Schedule
                    },
                    contentDescription = null,
                    tint = when (task.status) {
                        DownloadStatus.PAUSED.name -> TextTertiary
                        DownloadStatus.FAILED.name -> Color(0xFFFF6B6B)
                        else -> SakuraPrimary
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(task.episodeName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = SakuraPrimary,
                        trackColor = DarkSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${(task.progress * 100).toInt()}%",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        if (task.status == DownloadStatus.DOWNLOADING.name && task.speed > 0) {
                            Text("${formatFileSize(task.speed)}/s", color = SakuraPrimary, fontSize = 12.sp)
                        } else if (task.status == DownloadStatus.PAUSED.name) {
                            Text("已暂停", color = TextTertiary, fontSize = 12.sp)
                        } else if (task.status == DownloadStatus.FAILED.name) {
                            Text(task.errorMsg ?: "下载失败", color = Color(0xFFFF6B6B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else if (task.status == DownloadStatus.PENDING.name) {
                            Text("等待中", color = TextTertiary, fontSize = 12.sp)
                        }
                        if (task.downloadedBytes > 0) {
                            Text("${formatFileSize(task.downloadedBytes)} / ${formatFileSize(task.totalBytes)}", color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (task.status == DownloadStatus.DOWNLOADING.name || task.status == DownloadStatus.PAUSED.name) {
                    IconButton(onClick = {
                        if (task.status == DownloadStatus.PAUSED.name) onResumeClick() else onPauseClick()
                    }) {
                        Icon(
                            if (task.status == DownloadStatus.PAUSED.name) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null, tint = SakuraPrimary, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (task.status == DownloadStatus.FAILED.name) {
                    IconButton(onClick = onResumeClick) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重试", tint = SakuraPrimary, modifier = Modifier.size(20.dp))
                    }
                }

                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = TextTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CompletedDownloadItem(
    task: DownloadTask,
    onPlayClick: (() -> Unit)?,
    onDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onPlayClick != null) Modifier.clickable(onClick = onPlayClick) else Modifier),
        color = DarkSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SakuraPrimary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.episodeName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(formatFileSize(task.downloadedBytes), color = TextTertiary, fontSize = 12.sp)
            }
            if (onPlayClick != null) {
                IconButton(onClick = onPlayClick) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = SakuraPrimary, modifier = Modifier.size(22.dp))
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
