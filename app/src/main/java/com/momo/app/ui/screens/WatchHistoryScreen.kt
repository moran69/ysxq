package com.momo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momo.app.data.local.WatchHistoryEntry
import com.momo.app.data.local.watchHistoryStore
import com.momo.app.data.sync.WatchHistorySyncRepository
import com.momo.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHistoryScreen(
    onBack: () -> Unit,
    onVideoClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val historyStore = remember { context.watchHistoryStore() }
    val historySyncRepo = remember { WatchHistorySyncRepository(historyStore) }
    val history by historyStore.history.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var syncError by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingDeleteEntry by remember { mutableStateOf<WatchHistoryEntry?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        syncError = false
        val result = historySyncRepo.pullFromCloud()
        isLoading = false
        if (result.isFailure) {
            syncError = true
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空历史", color = TextPrimary) },
            text = { Text("确定要清空所有观看历史吗？", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        scope.launch(Dispatchers.IO) {
                            historySyncRepo.clearCloud()
                            historyStore.clearAll()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                ) { Text("清空", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消", color = TextSecondary) }
            },
            containerColor = DarkSurface
        )
    }

    pendingDeleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("删除记录", color = TextPrimary) },
            text = { Text("确定要删除「${entry.videoName}」的观看记录吗？", color = TextSecondary, maxLines = 2) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            historyStore.removeHistory(entry.videoId)
                            historySyncRepo.deleteFromCloud(entry.videoId)
                        }
                        pendingDeleteEntry = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                ) { Text("删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntry = null }) { Text("取消", color = TextSecondary) }
            },
            containerColor = DarkSurface
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        TopAppBar(
            title = { Text("观看历史", color = TextPrimary, fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TextPrimary)
                }
            },
            actions = {
                if (history.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.DeleteSweep, "清空", tint = TextTertiary)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SakuraPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在同步...", color = TextTertiary, fontSize = 14.sp)
                    }
                }
            }
            syncError -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("同步失败", color = TextTertiary, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("无法从云端获取观看历史", color = TextTertiary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    syncError = false
                                    val result = historySyncRepo.pullFromCloud()
                                    isLoading = false
                                    if (result.isFailure) {
                                        syncError = true
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                        ) { Text("重试", color = Color.White) }
                    }
                }
            }
            history.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无观看记录", color = TextTertiary, fontSize = 16.sp)
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(history, key = { it.videoId }) { entry ->
                        HistoryItemRow(
                            entry = entry,
                            onClick = { onVideoClick(entry.videoId) },
                            onRemove = { pendingDeleteEntry = entry }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemRow(
    entry: WatchHistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit
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
                model = entry.pic,
                contentDescription = entry.videoName,
                modifier = Modifier
                    .size(width = 100.dp, height = 65.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.videoName,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val progressText = buildString {
                    append(entry.episodeName)
                    if (entry.positionMs > 0) {
                        val totalSeconds = entry.positionMs / 1000
                        val minutes = totalSeconds / 60
                        val seconds = totalSeconds % 60
                        append(" · 观看到${minutes}分${seconds}秒")
                    }
                }
                Text(progressText, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
                Spacer(modifier = Modifier.height(2.dp))
                val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
                Text(sdf.format(Date(entry.updatedAt)), color = TextTertiary, fontSize = 11.sp)
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
