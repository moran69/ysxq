package com.momo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momo.app.data.VideoItem
import com.momo.app.data.kanjuai.KanjuAiSuggestion
import com.momo.app.data.kanjuai.KanjuAiHomeSection
import com.momo.app.data.kanjuai.KanjuAiHomeCard
import com.momo.app.data.kanjuai.KanjuAiTrendingCard
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.momo.app.ui.components.*
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.KanjuAiViewModel
import kotlinx.coroutines.launch

/**
 * 看剧AI 片源 Tab — kanju1.com 片源入口
 * 搜索走 HMAC-SHA256 签名 API，播放经 player.baipiaozhe.com 解析 m3u8 直链
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanjuAiScreen(
    onVideoClick: (VideoItem, List<com.momo.app.data.VideoSource>) -> Unit,
    viewModel: KanjuAiViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detailLoadingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
    ) {
        // 顶部标题
        Text(
            text = "看剧AI",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )

        // 搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索影片名称...", color = TextTertiary, fontSize = 14.sp) },
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
                        IconButton(onClick = { viewModel.clearSearch() }) {
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

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(onClick = { viewModel.search() }) {
                Text("搜索", color = SakuraPrimary, fontSize = 14.sp)
            }
        }

        HorizontalDivider(color = DarkSurfaceVariant, thickness = 0.5.dp)

        // 内容区
        when {
            state.isLoading -> {
                LoadingIndicator(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 80.dp))
            }
            state.hasSearched -> {
                when {
                    state.error != null && state.results.isEmpty() -> {
                        Box(modifier = Modifier.weight(1f)) {
                            ErrorState(message = state.error ?: "搜索失败", onRetry = { viewModel.search() })
                        }
                    }
                    state.results.isEmpty() -> {
                        Box(modifier = Modifier.weight(1f)) {
                            EmptyState(message = "没有找到相关影片，换个关键词试试~")
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(state.results) { index, item ->
                                KanjuAiGridItem(
                                    item = item,
                                    isLoadingDetail = detailLoadingId == item.id,
                                    onClick = {
                                        android.util.Log.d("KanjuAiUI", "结果卡片点击: index=$index label=${item.label}")
                                        detailLoadingId = item.id
                                        scope.launch {
                                            val detail = viewModel.resolveDetail(item)
                                            detailLoadingId = null
                                            if (detail != null) {
                                                onVideoClick(detail.first, detail.second)
                                            } else {
                                                Toast.makeText(context, "该片源暂无可用播放源", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                KanjuAiHomeContent(
                    state = state,
                    trending = state.trending,
                    onCardClick = { card ->
                        scope.launch {
                            detailLoadingId = card.id
                            val detail = viewModel.resolveDetailByVariantId(card.variantId, card.title)
                            detailLoadingId = null
                            if (detail != null) {
                                onVideoClick(detail.first, detail.second)
                            } else {
                                Toast.makeText(context, "该片源暂无可用播放源", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun KanjuAiGridItem(
    item: KanjuAiSuggestion,
    isLoadingDetail: Boolean,
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
                .background(DarkSurfaceVariant, RoundedCornerShape(10.dp))
        ) {
            // 看剧AI 搜索结果无海报 URL, 使用渐变占位
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = item.label.take(2),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiary.copy(alpha = 0.5f)
                    )
                }
            }

            if (item.subtitle.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = SakuraPrimary.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = item.subtitle,
                        fontSize = 9.sp,
                        color = DarkBackground,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isLoadingDetail) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkBackground.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = SakuraPrimary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.label,
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = item.subtitle.ifBlank { "看剧AI" },
            fontSize = 10.sp,
            color = TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


@Composable
private fun KanjuAiHomeContent(
    state: com.momo.app.viewmodel.KanjuAiState,
    trending: List<KanjuAiTrendingCard>,
    onCardClick: (KanjuAiHomeCard) -> Unit
) {
    if (state.isHomeLoading && state.homeSections.isEmpty() && trending.isEmpty()) {
        LoadingIndicator(modifier = Modifier.fillMaxSize().padding(top = 80.dp))
        return
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = 24.dp)
    ) {
        // 首页推荐 sections
        state.homeSections.forEach { section ->
            KanjuAiHomeSection(
                section = section,
                onCardClick = onCardClick
            )
        }

        // 热门榜单
        if (trending.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "热门榜单",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(trending) { card ->
                    KanjuAiTrendingItem(
                        card = card,
                        onClick = {
                            onCardClick(KanjuAiHomeCard(
                                id = card.id,
                                variantId = card.variantId,
                                title = card.title,
                                posterUrl = card.posterUrl,
                                year = card.year,
                                remarks = card.remarks
                            ))
                        }
                    )
                }
            }
        }

        if (state.homeSections.isEmpty() && trending.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Search, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Text(
                        text = "搜索看剧AI片源",
                        color = TextTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun KanjuAiHomeSection(
    section: KanjuAiHomeSection,
    onCardClick: (KanjuAiHomeCard) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = section.title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(section.cards) { card ->
                KanjuAiHomeCardItem(card = card, onClick = { onCardClick(card) })
            }
        }
    }
}

@Composable
private fun KanjuAiHomeCardItem(
    card: KanjuAiHomeCard,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = card.title.take(2),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiary.copy(alpha = 0.5f)
                    )
                }
            }

            if (card.remarks.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = SakuraPrimary.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = card.remarks,
                        fontSize = 9.sp,
                        color = DarkBackground,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = card.title,
            fontSize = 11.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (card.year > 0) {
            Text(
                text = card.year.toString(),
                fontSize = 9.sp,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun KanjuAiTrendingItem(
    card: KanjuAiTrendingCard,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = card.title.take(2),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextTertiary.copy(alpha = 0.5f)
                    )
                }
            }

            // 排名角标
            if (card.rankPosition > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = SakuraPrimary
                ) {
                    Text(
                        text = "No.${card.rankPosition}",
                        fontSize = 9.sp,
                        color = DarkBackground,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (card.remarks.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = SakuraPrimary.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = card.remarks,
                        fontSize = 9.sp,
                        color = DarkBackground,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = card.title,
            fontSize = 11.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (card.genres.isNotEmpty()) {
            Text(
                text = card.genres.take(2).joinToString("/"),
                fontSize = 9.sp,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
