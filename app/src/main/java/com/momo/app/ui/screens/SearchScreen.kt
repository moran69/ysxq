package com.momo.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.momo.app.data.VideoItem
import com.momo.app.data.VideoSource
import com.momo.app.ui.components.*
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.SearchResultItem
import com.momo.app.viewmodel.SearchSourceFilter
import com.momo.app.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onVideoClick: (Int) -> Unit,
    onSusouClick: (VideoItem, List<VideoSource>) -> Unit = { _, _ -> },
    onKanjuAiClick: (VideoItem, List<VideoSource>) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    initialQuery: String = "",
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && state.query != initialQuery) {
            viewModel.updateQuery(initialQuery)
            viewModel.search()
        }
    }

    LaunchedEffect(gridState, state.allResults) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 3 && totalItems > 0
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) viewModel.searchMore()
        }
    }

    var showClearHistoryDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
    ) {
        // 搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("全网聚合搜索 (本站/速搜/看剧AI)...", color = TextTertiary, fontSize = 13.sp)
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SakuraPrimary,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    cursorColor = SakuraPrimary
                ),
                trailingIcon = {
                    if (state.query.isNotBlank()) {
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "清除",
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() })
            )

            Spacer(modifier = Modifier.width(4.dp))

            TextButton(onClick = { viewModel.search() }) {
                Text("搜索", color = SakuraPrimary, fontSize = 14.sp)
            }
        }

        HorizontalDivider(color = DarkSurfaceVariant, thickness = 0.5.dp)

        // 搜索结果或建议
        when {
            state.hasSearched -> {
                // 来源过滤标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchSourceFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            SearchSourceFilter.ALL -> state.totalCount
                            SearchSourceFilter.MAC_CMS -> state.macResults.size
                            SearchSourceFilter.SUSOU -> state.susouResults.size
                            SearchSourceFilter.KANJU_AI -> state.kanjuResults.size
                        }
                        val isSelected = state.selectedFilter == filter
                        Surface(
                            onClick = { viewModel.selectFilter(filter) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) SakuraPrimary else DarkSurface,
                            border = if (isSelected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = filter.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) DarkBackground else TextSecondary
                                )
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) DarkBackground.copy(alpha = 0.22f) else DarkSurfaceVariant
                                    ) {
                                        Text(
                                            text = if (count > 99) "99+" else "$count",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) DarkBackground else TextTertiary,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 内容区
                when {
                    state.isLoading -> {
                        LoadingIndicator(modifier = Modifier.weight(1f))
                    }
                    state.displayResults.isEmpty() -> {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    message = if (state.totalCount > 0) {
                                        "「${state.selectedFilter.displayName}」暂无该影片，可在其他来源查看"
                                    } else {
                                        state.error ?: "全网未找到相关影片，换个关键词试试吧~"
                                    }
                                )
                                if (state.totalCount > 0 && state.selectedFilter != SearchSourceFilter.ALL) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.selectFilter(SearchSourceFilter.ALL) },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SakuraPrimary),
                                        border = BorderStroke(1.dp, SakuraPrimary.copy(alpha = 0.5f))
                                    ) {
                                        Text("查看全部来源 (${state.totalCount})")
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(state.displayResults, key = { it.id }) { item ->
                                UnifiedSearchGridItem(
                                    result = item,
                                    isResolving = state.resolvingId == item.id,
                                    onClick = {
                                        when (item) {
                                            is SearchResultItem.MacCms -> {
                                                onVideoClick(item.video.id)
                                            }
                                            is SearchResultItem.Susou -> {
                                                scope.launch {
                                                    val detail = viewModel.resolveSusou(item.item)
                                                    if (detail != null) {
                                                        onSusouClick(detail.first, detail.second)
                                                    } else {
                                                        Toast.makeText(context, "该片源暂无可用播放源", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                            is SearchResultItem.KanjuAi -> {
                                                scope.launch {
                                                    val detail = viewModel.resolveKanjuAi(item.item)
                                                    if (detail != null) {
                                                        onKanjuAiClick(detail.first, detail.second)
                                                    } else {
                                                        Toast.makeText(context, "该片源暂无可用播放源", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                            if (state.isLoadingMore) {
                                item { LoadingIndicator() }
                            }
                        }
                    }
                }
            }
            state.suggestions.isNotEmpty() -> {
                // 显示搜索建议（影片名称列表）
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(state.suggestions) { video ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateQuery(video.name)
                                    viewModel.search()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = video.name,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            state.isLoadingSuggestions -> {
                Box(modifier = Modifier.weight(1f)) {
                    LoadingIndicator()
                }
            }
            else -> {
                // 空状态：显示搜索历史或提示
                if (state.searchHistory.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "搜索历史",
                                color = TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(
                                onClick = { showClearHistoryDialog = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "清空历史",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.searchHistory.forEach { query ->
                                SuggestionChip(
                                    onClick = {
                                        viewModel.updateQuery(query)
                                        viewModel.search()
                                    },
                                    label = {
                                        Text(
                                            text = query,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            Icons.Filled.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = TextTertiary
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = DarkSurface,
                                        labelColor = TextPrimary,
                                        iconContentColor = TextTertiary
                                    ),
                                    border = null
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "输入关键词开始全网聚合搜索",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "自动汇总自建主源、速搜、看剧AI等各大片源",
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("清空搜索历史", color = TextPrimary) },
            text = { Text("确定要清空所有搜索历史吗？", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryDialog = false
                        viewModel.clearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                ) { Text("清空", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("取消", color = TextTertiary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun UnifiedSearchGridItem(
    result: SearchResultItem,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurfaceVariant)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(14.dp))
        ) {
            if (result.pic.isNotBlank()) {
                AsyncImage(
                    model = result.pic,
                    contentDescription = result.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkSurfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = result.title.take(2),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextTertiary.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // 来源角标（左上角）
            val badgeBg = when (result.sourceFilter) {
                SearchSourceFilter.MAC_CMS -> Color(0xFF1976D2) // 经典科技蓝
                SearchSourceFilter.SUSOU -> Color(0xFF764BA2)   // 优雅紫
                SearchSourceFilter.KANJU_AI -> Color(0xFFC77DA0)// 樱花暗粉
                else -> DarkSurface
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
                shape = RoundedCornerShape(6.dp),
                color = badgeBg.copy(alpha = 0.92f)
            ) {
                Text(
                    text = result.sourceBadgeName,
                    fontSize = 9.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            // 备注/集数角标（右上角）
            if (result.remarks.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = result.remarks,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // 加载详情浮层
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = SakuraPrimary,
                        strokeWidth = 2.5.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = result.title,
            fontSize = 12.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
