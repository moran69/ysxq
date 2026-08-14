package com.momo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Search, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Text(
                        text = "搜索看剧AI片源",
                        color = TextTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "聚合看剧AI片源，个人自用",
                        color = TextTertiary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
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
