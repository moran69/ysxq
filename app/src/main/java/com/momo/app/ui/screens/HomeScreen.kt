package com.momo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.pager.*
import com.momo.app.data.VideoItem
import com.momo.app.ui.components.*
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.HomeState
import com.momo.app.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalPagerApi::class)
@Composable
fun HomeScreen(
    onVideoClick: (Int) -> Unit,
    onCategoryMore: (Int) -> Unit,
    onSearchClick: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBar(onSearchClick = onSearchClick)
            HomeContent(
                state = state,
                onVideoClick = onVideoClick,
                onCategoryMore = onCategoryMore,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun SearchBar(onSearchClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onSearchClick),
        shape = RoundedCornerShape(24.dp),
        color = DarkSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.07f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, "搜索", tint = SakuraPrimary.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("搜索影片、演员、导演...", color = TextTertiary, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalPagerApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeState,
    onVideoClick: (Int) -> Unit,
    onCategoryMore: (Int) -> Unit,
    viewModel: HomeViewModel
) {
    val refreshState = rememberPullToRefreshState()

    if (state.isLoading && state.bannerVideos.isEmpty()) {
        LoadingIndicator(modifier = Modifier.fillMaxWidth().padding(top = 100.dp))
    } else if (state.error != null && state.bannerVideos.isEmpty()) {
        ErrorState(message = state.error, onRetry = { viewModel.loadHome() })
    } else {
        PullToRefreshBox(
            isRefreshing = state.isLoading && state.bannerVideos.isNotEmpty(),
            onRefresh = { viewModel.loadHome(forceRefresh = true) },
            state = refreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
            // Banner 轮播
            if (state.bannerVideos.isNotEmpty()) {
                BannerCarousel(
                    videos = state.bannerVideos,
                    onVideoClick = onVideoClick
                )
            }

            // 快捷分类入口
            if (state.categories.isNotEmpty()) {
                CategoryQuickTabs(
                    categories = state.categories,
                    onCategoryClick = { cat -> onCategoryMore(cat.id) }
                )
            }

            // 热门推荐
            if (state.movies.isNotEmpty()) {
                VideoSection("热门推荐", state.movies, onVideoClick) { onCategoryMore(6) }
            }

            // 热播剧集
            if (state.tvSeries.isNotEmpty()) {
                VideoSection("热播剧集", state.tvSeries, onVideoClick) { onCategoryMore(13) }
            }

            // 动漫番剧
            if (state.anime.isNotEmpty()) {
                VideoSection("动漫番剧", state.anime, onVideoClick) { onCategoryMore(30) }
            }

            // 热门综艺
            if (state.variety.isNotEmpty()) {
                VideoSection("热门综艺", state.variety, onVideoClick) { onCategoryMore(25) }
            }

            Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun CategoryQuickTabs(
    categories: List<com.momo.app.data.VideoCategory>,
    onCategoryClick: (com.momo.app.data.VideoCategory) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val iconMap = mapOf(
            "电影" to Icons.Filled.Movie,
            "连续剧" to Icons.Filled.Tv,
            "综艺" to Icons.Filled.LiveTv,
            "动漫" to Icons.Filled.Animation,
            "B站" to Icons.Filled.PlayCircle,
            "体育赛事" to Icons.Filled.SportsSoccer,
            "短剧" to Icons.Filled.VideoLibrary,
            "番剧" to Icons.Filled.SlowMotionVideo,
            "直播" to Icons.Filled.LiveTv
        )

        items(categories.filter { it.pid == 0 }) { category ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = DarkSurfaceVariant,
                modifier = Modifier.clickable { onCategoryClick(category) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = iconMap[category.name] ?: Icons.Filled.VideoLibrary
                    Icon(icon, null, tint = SakuraPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(category.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalPagerApi::class)
@Composable
private fun BannerCarousel(
    videos: List<VideoItem>,
    onVideoClick: (Int) -> Unit
) {
    val pagerState = rememberPagerState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        while (true) {
            delay(4000)
            if (videos.isEmpty()) break
            val nextPage = (pagerState.currentPage + 1) % videos.size
            pagerState.animateScrollToPage(nextPage)
        }
    }

    Column {
        HorizontalPager(
            count = videos.size,
            state = pagerState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) { page ->
            BannerItem(video = videos[page], onClick = { onVideoClick(videos[page].id) })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(videos.size) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (pagerState.currentPage == index) 18.dp else 6.dp, 6.dp)
                        .background(
                            color = if (pagerState.currentPage == index) SakuraPrimary else TextTertiary.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun VideoSection(
    title: String,
    videos: List<VideoItem>,
    onVideoClick: (Int) -> Unit,
    onMoreClick: () -> Unit
) {
    SectionHeader(title = title, onMoreClick = onMoreClick)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(videos) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video.id) })
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}
