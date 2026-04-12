package com.ysxq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ysxq.app.ui.components.*
import com.ysxq.app.ui.theme.*
import com.ysxq.app.viewmodel.CategoryViewModel
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset

@Composable
fun CategoryScreen(
    onVideoClick: (Int) -> Unit,
    initialCategoryId: Int = 0,
    viewModel: CategoryViewModel = viewModel()
) {
    LaunchedEffect(initialCategoryId) {
        viewModel.setInitialCategory(initialCategoryId)
    }

    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()

    // 触发加载更多
    LaunchedEffect(gridState, state.videos) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 3 && totalItems > 0
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) viewModel.loadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        if (state.mainCategories.isEmpty() && state.isLoading) {
            LoadingIndicator(modifier = Modifier.weight(1f))
        } else {
            // 主分类 Tab
            if (state.mainCategories.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = state.mainCategories.indexOfFirst { it.id == state.selectedMainCategoryId }.coerceAtLeast(0),
                    containerColor = DarkSurface,
                    contentColor = SakuraPrimary,
                    edgePadding = 16.dp,
                    indicator = { tabPositions ->
                        val idx = state.mainCategories.indexOfFirst { it.id == state.selectedMainCategoryId }.coerceAtLeast(0)
                        if (idx < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[idx]),
                                height = 3.dp,
                                color = SakuraPrimary
                            )
                        }
                    },
                    divider = {}
                ) {
                    state.mainCategories.forEach { category ->
                        val selected = category.id == state.selectedMainCategoryId
                        Tab(
                            selected = selected,
                            onClick = { viewModel.selectMainCategory(category.id) },
                            text = {
                                Text(
                                    category.name,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) SakuraPrimary else TextSecondary
                                )
                            }
                        )
                    }
                }
            }

            // 子分类（类型）筛选
            if (state.subCategories.isNotEmpty()) {
                FilterChipsRow(
                    options = listOf("全部") + state.subCategories.map { it.name },
                    selectedIndex = if (state.selectedGenreId == 0) 0
                        else state.subCategories.indexOfFirst { it.id == state.selectedGenreId } + 1,
                    onSelect = { index ->
                        if (index == 0) {
                            viewModel.selectGenre(0, "全部")
                        } else {
                            val sub = state.subCategories.getOrNull(index - 1)
                            if (sub != null) viewModel.selectGenre(sub.id, sub.name)
                        }
                    }
                )
            }

            // 筛选栏加载状态
            val filtersLoading = state.areaOptions.size <= 1 && state.yearOptions.size <= 1 && state.isLoading

            if (filtersLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = SakuraPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载筛选选项...", color = TextTertiary, fontSize = 12.sp)
                }
            } else {
                // 地区筛选
                FilterChipsRow(
                    label = "地区",
                    options = state.areaOptions,
                    selectedIndex = state.areaOptions.indexOf(state.selectedArea).coerceAtLeast(0),
                    onSelect = { viewModel.selectArea(state.areaOptions[it]) }
                )

                // 年份筛选
                FilterChipsRow(
                    label = "年份",
                    options = state.yearOptions,
                    selectedIndex = state.yearOptions.indexOf(state.selectedYear).coerceAtLeast(0),
                    onSelect = { viewModel.selectYear(state.yearOptions[it]) }
                )
            }

            // 视频网格
            when {
                state.isLoading && state.videos.isEmpty() -> {
                    LoadingIndicator(modifier = Modifier.weight(1f))
                }
                state.error != null && state.videos.isEmpty() && !state.isLoading -> {
                    Box(modifier = Modifier.weight(1f)) {
                        ErrorState(
                            message = state.error ?: "加载失败",
                            onRetry = { viewModel.retry() }
                        )
                    }
                }
                state.videos.isEmpty() && !state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "📺",
                                fontSize = 48.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无相关视频",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
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
                        items(state.videos) { video ->
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
    }
}

@Composable
private fun FilterChipsRow(
    label: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    if (options.size <= 1) return
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (label != null) {
                Text(
                    label,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(36.dp)
                )
            }
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = Color.Transparent,
                contentColor = SakuraPrimary,
                edgePadding = 4.dp,
                indicator = {},
                divider = {},
                modifier = Modifier.weight(1f)
            ) {
                options.forEachIndexed { index, option ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        text = {
                            Text(
                                option,
                                fontSize = 12.sp,
                                color = if (index == selectedIndex) SakuraPrimary else TextSecondary,
                                fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp)
                    )
                }
            }
        }
    }
}
