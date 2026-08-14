package com.momo.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momo.app.R
import com.momo.app.data.update.AppVersionInfo
import com.momo.app.ui.components.CuteCatLoader
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.SplashViewModel

@Composable
fun SplashScreen(
    onInitialized: () -> Unit,
    viewModel: SplashViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.isInitialized) {
        if (state.isInitialized) {
            onInitialized()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_cover),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.5f }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f)
                        ),
                        startY = size.height * 0.3f,
                        endY = size.height
                    )
                )
            }
        }

        if (state.updateAvailable != null) {
            UpdateDialog(
                versionInfo = state.updateAvailable!!,
                isDownloading = state.isDownloading,
                downloadProgress = state.downloadProgress,
                statusMessage = state.loadingMessage,
                onUpdate = { viewModel.startUpdate(context) }
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CuteCatLoader()

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = state.loadingMessage,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .width(180.dp)
                        .height(4.dp),
                    color = SakuraPrimary,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    versionInfo: AppVersionInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    statusMessage: String,
    onUpdate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "发现新版本 v${versionInfo.versionName}",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (versionInfo.updateLog.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 200.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = DarkBackground
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = versionInfo.updateLog,
                                color = TextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            color = SakuraPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = SakuraPrimary,
                            trackColor = DarkSurfaceVariant,
                            strokeCap = StrokeCap.Round
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = statusMessage,
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Button(
                        onClick = onUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SakuraPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "立即更新",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (statusMessage.contains("失败", ignoreCase = true)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = statusMessage,
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp
                        )
                    }
                }

                if (!versionInfo.forceUpdate && !isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { }) {
                        Text(
                            text = "稍后再说",
                            color = TextTertiary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
