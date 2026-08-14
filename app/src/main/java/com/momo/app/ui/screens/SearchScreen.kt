package com.momo.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momo.app.ui.components.*
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.SearchViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onVideoClick: (Int) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && state.query != initialQuery) {
            viewModel.updateQuery(initialQuery)
            viewModel.search()
        }
    }

    LaunchedEffect(gridState, state.results) {
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
                modifier = Modifier
                    .weight(1f),
                placeholder = {
                    Text("搜索影片名称...", color = TextTertiary, fontSize = 14.sp)
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
                // 已执行搜索，显示完整结果
                when {
                    state.isLoading -> {
                        LoadingIndicator(modifier = Modifier.weight(1f))
                    }
                    state.error != null -> {
                        Box(modifier = Modifier.weight(1f)) {
                            ErrorState(
                                message = state.error ?: "搜索失败",
                                onRetry = { viewModel.search() }
                            )
                        }
                    }
                    state.results.isEmpty() -> {
                        Box(modifier = Modifier.weight(1f)) {
                            EmptyState(message = "没有找到相关影片，换个关键词试试吧~")
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(state.results) { video ->
                                VideoGridItem(
                                    video = video,
                                    onClick = { onVideoClick(video.id) }
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
                                    // 点击建议项：填入名称并执行搜索
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
                            text = "输入关键词开始搜索",
                            color = TextTertiary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "支持影片名称、演员、导演等",
                            color = TextTertiary.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
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
