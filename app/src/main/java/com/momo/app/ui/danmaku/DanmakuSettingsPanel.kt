package com.momo.app.ui.danmaku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * B站风格弹幕设置面板
 *
 * 从底部弹出，包含：
 * - 弹幕开关
 * - 透明度滑块
 * - 字体大小滑块
 * - 速度滑块
 * - 显示区域选择（1/4, 半屏, 3/4, 全屏）
 * - 类型屏蔽（滚动/顶部/底部）
 */
@Composable
fun DanmakuSettingsPanel(
    config: DanmakuConfig,
    onConfigChange: (DanmakuConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var localOpacity by remember { mutableFloatStateOf(config.opacity) }
    var localFontScale by remember { mutableFloatStateOf(config.fontScale) }
    var localSpeed by remember { mutableFloatStateOf(config.speedFactor) }
    var localDisplayArea by remember { mutableFloatStateOf(config.displayAreaRatio) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(enabled = false) { },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = Color(0xFF1A1A1A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "弹幕设置",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 弹幕开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("弹幕", color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFB7299),
                            checkedTrackColor = Color(0xFFFB7299).copy(alpha = 0.3f)
                        )
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.1f))

                // 透明度
                DanmakuSliderItem(
                    label = "透明度",
                    value = localOpacity,
                    range = 0.1f..1.0f,
                    displayValue = "${(localOpacity * 100).toInt()}%",
                    onValueChange = { localOpacity = it },
                    onValueChangeFinished = { onConfigChange(config.copy(opacity = localOpacity)) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 字体大小
                DanmakuSliderItem(
                    label = "字体大小",
                    value = localFontScale,
                    range = 0.5f..2.0f,
                    displayValue = "${(localFontScale * 100).toInt()}%",
                    onValueChange = { localFontScale = it },
                    onValueChangeFinished = { onConfigChange(config.copy(fontScale = localFontScale)) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 速度（越大越慢）
                DanmakuSliderItem(
                    label = "弹幕速度",
                    value = localSpeed,
                    range = 0.5f..2.0f,
                    displayValue = if (localSpeed < 0.8f) "很快" else if (localSpeed < 1.2f) "正常" else "较慢",
                    onValueChange = { localSpeed = it },
                    onValueChangeFinished = { onConfigChange(config.copy(speedFactor = localSpeed)) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 显示区域
                Text("显示区域", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.25f to "1/4屏", 0.5f to "半屏", 0.75f to "3/4屏", 1.0f to "全屏").forEach { (ratio, label) ->
                        val selected = abs(localDisplayArea - ratio) < 0.01f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    localDisplayArea = ratio
                                    onConfigChange(config.copy(displayAreaRatio = ratio))
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFFFB7299) else Color.White.copy(alpha = 0.1f)
                        ) {
                            Text(
                                label,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 类型屏蔽
                Text("类型屏蔽", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DanmakuTypeToggle("滚动", config.allowScroll) {
                        onConfigChange(config.copy(allowScroll = it))
                    }
                    DanmakuTypeToggle("顶部", config.allowTop) {
                        onConfigChange(config.copy(allowTop = it))
                    }
                    DanmakuTypeToggle("底部", config.allowBottom) {
                        onConfigChange(config.copy(allowBottom = it))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DanmakuSliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 14.sp)
            Text(displayValue, color = Color(0xFFFB7299), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFB7299),
                activeTrackColor = Color(0xFFFB7299),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun DanmakuTypeToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(8.dp),
        color = if (checked) Color(0xFFFB7299).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                null,
                tint = if (checked) Color(0xFFFB7299) else Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label,
                color = if (checked) Color.White else Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        }
    }
}
