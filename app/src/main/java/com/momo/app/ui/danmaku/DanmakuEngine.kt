package com.momo.app.ui.danmaku

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 纯 Compose 自绘弹幕渲染引擎
 *
 * 支持滚动弹幕、顶部固定、底部固定三种类型
 * 自动轨道分配，避免弹幕重叠
 */
@Composable
fun DanmakuOverlay(
    danmakuList: List<DanmakuItem>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    config: DanmakuConfig,
    modifier: Modifier = Modifier
) {
    if (!config.enabled || danmakuList.isEmpty()) return

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // 基准字体大小（sp）
    val baseFontSize = 16f * config.fontScale
    val fontSizeSp = baseFontSize.sp

    // 轨道管理：记录每条轨道最后一条弹幕的结束 x 坐标
    // scrollTracks[trackIndex] = 最后弹幕尾部的 x 位置（如果尾部还在屏幕内则不可放入）
    val scrollTracks = remember { mutableStateMapOf<Int, Float>() }
    val topTracks = remember { mutableStateMapOf<Int, Long>() }
    val bottomTracks = remember { mutableStateMapOf<Int, Long>() }
    val maxScrollTracks = remember { mutableStateOf(0) }

    // 活跃弹幕列表
    val activeScrollDanmaku = remember { mutableStateListOf<DanmakuTrack>() }
    val activeFixedDanmaku = remember { mutableStateListOf<FixedDanmakuTrack>() }
    // 已渲染过的弹幕时间戳集合，防止重复添加
    val renderedSet = remember { mutableSetOf<Pair<String, Long>>() }

    // 视口尺寸（在 Canvas 中获取）
    var viewportWidth by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }

    // 帧率控制
    var frameTime by remember { mutableLongStateOf(0L) }

    // 主动画循环
    LaunchedEffect(danmakuList, isPlaying, config) {
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            val now = currentPositionMs
            frameTime = System.currentTimeMillis()

            // 计算轨道高度
            val trackHeight = with(density) { (baseFontSize * 1.6f * density.density).toFloat() }
            val displayHeight = viewportHeight * config.displayAreaRatio
            val maxTracks = (displayHeight / trackHeight).toInt().coerceAtLeast(1)
            maxScrollTracks.value = maxTracks

            // 添加到达时间的弹幕
            danmakuList.forEach { item ->
                val key = item.text to item.time
                if (key in renderedSet) return@forEach

                if (now >= item.time && now < item.time + 10000) {
                    // 检查类型是否被允许
                    val allowed = when (item.type) {
                        DanmakuType.SCROLL -> config.allowScroll
                        DanmakuType.TOP -> config.allowTop
                        DanmakuType.BOTTOM -> config.allowBottom
                    }
                    if (!allowed) {
                        renderedSet.add(key)
                        return@forEach
                    }

                    val measured = textMeasurer.measure(
                        AnnotatedString(item.text),
                        TextStyle(fontSize = fontSizeSp)
                    )
                    val textWidth = measured.size.width.toFloat()
                    val textHeight = measured.size.height.toFloat()

                    when (item.type) {
                        DanmakuType.SCROLL -> {
                            // 找一个可用轨道
                            for (trackIndex in 0 until maxTracks) {
                                val lastEnd = scrollTracks[trackIndex] ?: -Float.MAX_VALUE
                                // 如果上一条弹幕尾部已经移出屏幕左边足够远（留 20dp 间距）
                                val spacing = with(density) { 20.dp.toPx() }
                                if (lastEnd + spacing < viewportWidth) {
                                    // 新弹幕从右边进入
                                    val y = trackIndex * trackHeight + trackHeight * 0.2f
                                    activeScrollDanmaku.add(DanmakuTrack(
                                        item = item,
                                        x = viewportWidth,
                                        y = y,
                                        startTime = frameTime,
                                        textWidth = textWidth,
                                        trackIndex = trackIndex
                                    ))
                                    scrollTracks[trackIndex] = viewportWidth + textWidth
                                    renderedSet.add(key)
                                    break
                                }
                            }
                        }
                        DanmakuType.TOP, DanmakuType.BOTTOM -> {
                            val trackMap = if (item.type == DanmakuType.TOP) topTracks else bottomTracks
                            val isTop = item.type == DanmakuType.TOP
                            for (trackIndex in 0 until (maxTracks / 2).coerceAtLeast(1)) {
                                val lastEnd = trackMap[trackIndex] ?: 0L
                                if (now >= lastEnd) {
                                    val y = if (isTop) {
                                        trackIndex * trackHeight + trackHeight * 0.2f
                                    } else {
                                        viewportHeight - (trackIndex + 1) * trackHeight + trackHeight * 0.2f
                                    }
                                    activeFixedDanmaku.add(FixedDanmakuTrack(
                                        item = item,
                                        y = y,
                                        startTime = frameTime,
                                        textWidth = textWidth,
                                        trackIndex = trackIndex,
                                        isTop = isTop
                                    ))
                                    // 固定弹幕显示 4 秒
                                    trackMap[trackIndex] = now + 4000
                                    renderedSet.add(key)
                                    break
                                }
                            }
                        }
                    }
                }
            }

            // 更新滚动弹幕位置
            val moveSpeed = with(density) {
                // 基准速度：屏幕宽度 / 滚动时长(秒) * speedFactor
                // speedFactor 越大越慢
                val scrollDurationSec = 8f * config.speedFactor
                (viewportWidth / scrollDurationSec) / density.density * density.density
            }
            val deltaMs = 16L // 约 60fps
            val deltaPx = moveSpeed * (deltaMs / 1000f)

            val iter = activeScrollDanmaku.iterator()
            while (iter.hasNext()) {
                val track = iter.next()
                track.x -= deltaPx
                // 更新轨道最后位置（用于判断新弹幕能否进入）
                scrollTracks[track.trackIndex] = track.x + track.textWidth
                // 如果完全移出屏幕左边，移除
                if (track.x + track.textWidth < -100f) {
                    iter.remove()
                    if (scrollTracks[track.trackIndex] == track.x + track.textWidth) {
                        scrollTracks.remove(track.trackIndex)
                    }
                }
            }

            // 清理过期固定弹幕
            val fixedIter = activeFixedDanmaku.iterator()
            while (fixedIter.hasNext()) {
                val fixed = fixedIter.next()
                if (frameTime - fixed.startTime > 4000) {
                    fixedIter.remove()
                    val trackMap = if (fixed.isTop) topTracks else bottomTracks
                    trackMap.remove(fixed.trackIndex)
                }
            }

            delay(deltaMs)
        }
    }

    // 暂停时不清除弹幕，保持画面
    // seek 时需要清除并重置
    LaunchedEffect(currentPositionMs) {
        // 如果位置跳跃超过 5 秒，清除所有弹幕重新开始
        // 通过 renderedSet 的清理实现
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            viewportWidth = size.width
            viewportHeight = size.height

            if (viewportWidth <= 0f || viewportHeight <= 0f) return@Canvas

            val alpha = config.opacity

            // 绘制滚动弹幕
            activeScrollDanmaku.forEach { track ->
                val color = Color(track.item.toColorInt())
                drawDanmakuText(
                    drawScope = this,
                    text = track.item.text,
                    x = track.x,
                    y = track.y,
                    fontSizePx = with(density) { baseFontSize.dp.toPx() },
                    color = color.copy(alpha = alpha),
                    strokeWidth = config.strokeWidth
                )
            }

            // 绘制固定弹幕
            activeFixedDanmaku.forEach { fixed ->
                val color = Color(fixed.item.toColorInt())
                // 固定弹幕居中
                val x = (viewportWidth - fixed.textWidth) / 2f
                drawDanmakuText(
                    drawScope = this,
                    text = fixed.item.text,
                    x = x,
                    y = fixed.y,
                    fontSizePx = with(density) { baseFontSize.dp.toPx() },
                    color = color.copy(alpha = alpha),
                    strokeWidth = config.strokeWidth
                )
            }
        }
    }
}

/**
 * 使用 nativeCanvas 绘制带描边的弹幕文本
 */
private fun drawDanmakuText(
    drawScope: DrawScope,
    text: String,
    x: Float,
    y: Float,
    fontSizePx: Float,
    color: Color,
    strokeWidth: Float
) {
    drawScope.drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas

        val textPaint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            this.textSize = fontSizePx
            this.isAntiAlias = true
            this.typeface = Typeface.DEFAULT_BOLD
        }

        val strokePaint = android.graphics.Paint().apply {
            this.color = android.graphics.Color.BLACK
            this.textSize = fontSizePx
            this.isAntiAlias = true
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            this.typeface = Typeface.DEFAULT_BOLD
        }

        // 先画描边
        canvas.nativeCanvas.drawText(text, x, y + fontSizePx, strokePaint)
        // 再画填充
        canvas.nativeCanvas.drawText(text, x, y + fontSizePx, textPaint)
    }
}

/**
 * 颜色辅助函数
 */
fun Color.toArgb(): Int {
    val a = (alpha * 255).toInt()
    val r = (red * 255).toInt()
    val g = (green * 255).toInt()
    val b = (blue * 255).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
