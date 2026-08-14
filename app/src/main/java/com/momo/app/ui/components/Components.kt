package com.momo.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.momo.app.data.VideoItem
import com.momo.app.ui.theme.*

@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRemarks: Boolean = true
) {
    Card(
        modifier = modifier
            .width(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                AsyncImage(
                    model = video.pic,
                    contentDescription = video.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )

                // 底部渐变遮罩
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                )

                // 标签
                if (showRemarks && video.remarks.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = SakuraPrimary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = video.remarks,
                            fontSize = 10.sp,
                            color = DarkBackground,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 评分
                if (video.score.isNotBlank() && video.score != "0.0") {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFFB300).copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = "★ ${video.score}",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 标题
            Text(
                text = video.name,
                fontSize = 13.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                lineHeight = 18.sp
            )

            // 类型
            if (video.typeName.isNotBlank()) {
                Text(
                    text = video.typeName,
                    fontSize = 11.sp,
                    color = TextTertiary,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
fun VideoGridItem(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            AsyncImage(
                model = video.pic,
                contentDescription = video.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
            )

            if (video.remarks.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = SakuraPrimary.copy(alpha = 0.85f)
                ) {
                    Text(
                        text = video.remarks,
                        fontSize = 9.sp,
                        color = DarkBackground,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = video.name,
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = video.typeName,
            fontSize = 10.sp,
            color = TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BannerItem(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = video.pic,
            contentDescription = video.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
        )

        // 底部信息
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = SakuraPrimary.copy(alpha = 0.85f)
            ) {
                Text(
                    text = video.typeName,
                    fontSize = 10.sp,
                    color = DarkBackground,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = video.name,
                fontSize = 20.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (video.blurb.isNotBlank() || video.remarks.isNotBlank()) {
                Text(
                    text = video.remarks.ifBlank { video.blurb },
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size((48 * scale).dp),
            shape = CircleShape,
            color = SakuraPrimary.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = SakuraPrimary,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    message: String = "暂无数据",
    icon: @Composable () -> Unit = {
        Icon(
            Icons.Outlined.Movie,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = TextTertiary
        )
    }
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            color = TextTertiary,
            fontSize = 14.sp
        )
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFFFF5252)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRetry,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SakuraPrimary
                )
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    onMoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(SakuraPrimary, Lavender)
                        )
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        if (onMoreClick != null) {
            TextButton(onClick = onMoreClick) {
                Text(
                    text = "更多",
                    color = SakuraPrimary,
                    fontSize = 13.sp
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = SakuraPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
