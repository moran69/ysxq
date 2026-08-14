package com.momo.app.ui.danmaku

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * B站风格弹幕发送输入栏
 *
 * 在播放器顶部弹出，包含：
 * - 输入框
 * - 颜色选择按钮
 * - 弹幕类型选择（滚动/顶部/底部）
 * - 发送按钮（粉色 #FB7299）
 */
@Composable
fun DanmakuInputBar(
    visible: Boolean,
    onSend: (String, Long, DanmakuType) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(0xFFFFFF) }
    var showColorPicker by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(DanmakuType.SCROLL) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // 输入栏主体
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 关闭按钮
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 输入框
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发个弹幕见证当下", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp) },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFB7299),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        cursorColor = Color(0xFFFB7299),
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    ),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 颜色选择按钮（显示当前选中的颜色）
                IconButton(
                    onClick = { showColorPicker = !showColorPicker },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(selectedColor.toInt() and 0xFFFFFF))
                ) {
                    if (showColorPicker) {
                        Icon(Icons.Filled.Close, "关闭颜色选择", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 发送按钮
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text.trim(), selectedColor, selectedType)
                            text = ""
                            onDismiss()
                        }
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFB7299),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFFB7299).copy(alpha = 0.3f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("发送", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // 颜色选择器
            AnimatedVisibility(visible = showColorPicker) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val colors = listOf(
                        0xFFFFFFL to "白",
                        0xFF0000L to "红",
                        0xFFB6C1L to "粉",
                        0xFFD700L to "金",
                        0x00FF00L to "绿",
                        0x00BFFFL to "青",
                        0x0000FFL to "蓝",
                        0xA020F0L to "紫"
                    )
                    colors.forEach { (color, label) ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(color.toInt() and 0xFFFFFF))
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == color) {
                                Icon(Icons.Filled.Send, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            // 弹幕类型选择
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DanmakuTypeOption("滚动", DanmakuType.SCROLL, selectedType) { selectedType = it }
                DanmakuTypeOption("顶部", DanmakuType.TOP, selectedType) { selectedType = it }
                DanmakuTypeOption("底部", DanmakuType.BOTTOM, selectedType) { selectedType = it }
            }
        }
    }
}

@Composable
private fun DanmakuTypeOption(
    label: String,
    type: DanmakuType,
    selectedType: DanmakuType,
    onSelect: (DanmakuType) -> Unit
) {
    val isSelected = type == selectedType
    Surface(
        modifier = Modifier.clickable { onSelect(type) },
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) Color(0xFFFB7299) else Color.White.copy(alpha = 0.1f)
    ) {
        Text(
            label,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}
