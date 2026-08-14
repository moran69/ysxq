package com.momo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.app.ui.theme.*

private const val FEEDBACK_EMAIL = "wuliang@duck.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedToast by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedToast) {
        if (showCopiedToast) {
            kotlinx.coroutines.delay(2000)
            showCopiedToast = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("意见反馈", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "联系邮箱",
                            style = MaterialTheme.typography.titleMedium,
                            color = SakuraPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "您可以通过邮箱向我们发送反馈，我们会认真阅读每一条建议 ✉️",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(DarkSurfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                tint = SakuraPrimary,
                                modifier = Modifier.size(20.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = FEEDBACK_EMAIL,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(FEEDBACK_EMAIL))
                                    showCopiedToast = true
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "复制邮箱",
                                    tint = SakuraPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showCopiedToast) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SakuraPrimary.copy(alpha = 0.9f))
                ) {
                    Text(
                        text = "✅ 邮箱已复制到剪贴板",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

        }
    }
}
