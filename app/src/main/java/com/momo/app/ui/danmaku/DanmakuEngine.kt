package com.momo.app.ui.danmaku

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 纯 Compose 自绘弹幕渲染引擎（性能优化版）
 *
 * 性能优化点（针对 2000+ 条真实弹幕）:
 * 1. 弹幕列表按时间排序 + 游标推进：主循环不再每帧遍历全量列表
 * 2. 轨道满的弹幕进入"延迟重试队列"，而不是每帧重新测量文本
 * 3. Paint 对象复用（避免每帧创建对象导致 GC 卡顿）
 * 4. 文本宽度缓存（同一文本只 measure 一次）
 * 5. seek 跳跃检测：位置跳变 >5s 时清空重来
 */
@Composable
fun DanmakuOverlay(
    danmakuList: List<DanmakuItem>,
    currentPositionMs: Long,
    isPlaying: Boolean,
    config: DanmakuConfig,
    modifier: Modifier = Modifier,
    playbackSpeed: Float = 1f
) {
    if (!config.enabled || danmakuList.isEmpty()) return

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // 基准字体大小（sp）
    val baseFontSize = 16f * config.fontScale
    val fontSizeSp = baseFontSize.sp

    // 轨道管理
    val scrollTracks = remember { mutableStateMapOf<Int, Float>() }
    val topTracks = remember { mutableStateMapOf<Int, Long>() }
    val bottomTracks = remember { mutableStateMapOf<Int, Long>() }
    val maxScrollTracks = remember { mutableStateOf(0) }

    // 活跃弹幕列表
    val activeScrollDanmaku = remember { mutableStateListOf<DanmakuTrack>() }
    val activeFixedDanmaku = remember { mutableStateListOf<FixedDanmakuTrack>() }

    // 已处理弹幕集合（成功上屏/类型不允许/已过期）
    val processedSet = remember { mutableStateOf(mutableSetOf<Pair<String, Long>>()) }
    // 轨道满待重试的弹幕：key -> DanmakuItem
    val retryQueue = remember { mutableStateOf(mutableMapOf<Pair<String, Long>, DanmakuItem>()) }

    // 视口尺寸
    var viewportWidth by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }

    // 帧时间戳
    var frameTime by remember { mutableLongStateOf(0L) }

    // 文本宽度缓存：text -> width（同一文本只 measure 一次）
    val textWidthCache = remember(config.fontScale) { mutableMapOf<String, Float>() }

    // 复用 Paint 对象（避免每帧创建）
    val fillPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            style = android.graphics.Paint.Style.FILL
        }
    }
    val strokePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            style = android.graphics.Paint.Style.STROKE
        }
    }

    // 按时间排序 + 游标：只处理当前时刻之后到期的弹幕
    val sortedDanmaku = remember(danmakuList) { danmakuList.sortedBy { it.time } }
    var cursorIndex by remember { mutableIntStateOf(0) }
    // 上一帧的弹幕列表引用（区分"切集换列表"与"发送追加/重组"）
    var lastListRef by remember { mutableStateOf<List<DanmakuItem>?>(null) }
    // 上一帧时间戳（用于按真实帧间隔推进，保证匀速）
    var lastMoveFrameTime by remember { mutableLongStateOf(0L) }

    // 上一帧位置（用于检测 seek 跳跃）
    var lastPositionMs by remember { mutableLongStateOf(-1L) }

    // ===== 内联工具：测量文本宽度（带缓存） =====
    fun measureWidth(text: String): Float {
        textWidthCache[text]?.let { return it }
        val measured = textMeasurer.measure(
            AnnotatedString(text),
            TextStyle(fontSize = fontSizeSp)
        )
        val w = measured.size.width.toFloat()
        textWidthCache[text] = w
        return w
    }

    // ===== 主循环 =====
    LaunchedEffect(danmakuList, isPlaying, config, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect

        // 仅在弹幕列表真正更换（如切集）时重置全部状态；
        // 发送弹幕追加 / 暂停后恢复 都不清屏，避免弹幕集体消失
        val isNewList = danmakuList !== lastListRef
        lastListRef = danmakuList
        if (isNewList) {
            processedSet.value.clear()
            retryQueue.value.clear()
            activeScrollDanmaku.clear()
            activeFixedDanmaku.clear()
            scrollTracks.clear()
            topTracks.clear()
            bottomTracks.clear()
            lastPositionMs = -1L
            cursorIndex = 0
        }
        lastMoveFrameTime = 0L

        while (isActive) {
            val now = currentPositionMs
            frameTime = System.currentTimeMillis()

            // 检测 seek 跳跃：位置跳变超过 5 秒，清空重来
            if (lastPositionMs >= 0 && abs(now - lastPositionMs) > 5000) {
                activeScrollDanmaku.clear()
                activeFixedDanmaku.clear()
                scrollTracks.clear()
                topTracks.clear()
                bottomTracks.clear()
                processedSet.value.clear()
                retryQueue.value.clear()
                // 游标回退到当前时间之前
                var ci = 0
                while (ci < sortedDanmaku.size && sortedDanmaku[ci].time < now) ci++
                cursorIndex = ci
            }
            lastPositionMs = now

            // 计算轨道高度和数量
            val trackHeight = with(density) { (baseFontSize * 1.6f * density.density).toFloat() }
            val displayHeight = viewportHeight * config.displayAreaRatio
            val maxTracks = (displayHeight / trackHeight).toInt().coerceAtLeast(1)
            maxScrollTracks.value = maxTracks

            // 轨道满的弹幕重试（不阻塞主遍历）
            if (retryQueue.value.isNotEmpty()) {
                val retryIter = retryQueue.value.entries.iterator()
                while (retryIter.hasNext()) {
                    val (key, item) = retryIter.next()
                    // 超过 10 秒窗口还没上屏 → 丢弃
                    if (now > item.time + 10000) {
                        processedSet.value.add(key)
                        retryIter.remove()
                        continue
                    }
                    val allowed = when (item.type) {
                        DanmakuType.SCROLL -> config.allowScroll
                        DanmakuType.TOP -> config.allowTop
                        DanmakuType.BOTTOM -> config.allowBottom
                    }
                    if (!allowed) {
                        processedSet.value.add(key)
                        retryIter.remove()
                        continue
                    }
                    val textWidth = measureWidth(item.text)
                    val added = when (item.type) {
                        DanmakuType.SCROLL -> addScrollDanmaku(
                            item, key, now, textWidth, viewportWidth, viewportHeight,
                            trackHeight, maxTracks, scrollTracks, activeScrollDanmaku,
                            processedSet.value, retryQueue.value, frameTime, density
                        )
                        else -> addFixedDanmaku(
                            item, key, now, textWidth, viewportWidth, viewportHeight,
                            trackHeight, maxTracks, topTracks, bottomTracks,
                            activeFixedDanmaku, processedSet.value, retryQueue.value, frameTime
                        )
                    }
                    if (added) retryIter.remove()
                }
            }

            // 游标推进：处理当前时刻到期的弹幕
            while (cursorIndex < sortedDanmaku.size && sortedDanmaku[cursorIndex].time <= now) {
                val item = sortedDanmaku[cursorIndex]
                val key = item.text to item.time
                cursorIndex++

                if (key in processedSet.value) continue

                // 超过窗口还没上屏 → 丢弃
                if (now > item.time + 10000) {
                    processedSet.value.add(key)
                    continue
                }

                val allowed = when (item.type) {
                    DanmakuType.SCROLL -> config.allowScroll
                    DanmakuType.TOP -> config.allowTop
                    DanmakuType.BOTTOM -> config.allowBottom
                }
                if (!allowed) {
                    processedSet.value.add(key)
                    continue
                }

                val textWidth = measureWidth(item.text)
                val added = when (item.type) {
                    DanmakuType.SCROLL -> addScrollDanmaku(
                        item, key, now, textWidth, viewportWidth, viewportHeight,
                        trackHeight, maxTracks, scrollTracks, activeScrollDanmaku,
                        processedSet.value, retryQueue.value, frameTime, density
                    )
                    else -> addFixedDanmaku(
                        item, key, now, textWidth, viewportWidth, viewportHeight,
                        trackHeight, maxTracks, topTracks, bottomTracks,
                        activeFixedDanmaku, processedSet.value, retryQueue.value, frameTime
                    )
                }
                // added=false 时已加入 retryQueue，由重试队列处理
            }

            // 更新滚动弹幕位置：按实际帧间隔推进（匀速），帧率波动/掉帧不再导致忽快忽慢；
            // 倍速播放时弹幕同步加速（2x 播放 → 弹幕 2x 滚动）
            val moveSpeed = with(density) {
                val scrollDurationSec = 8f * config.speedFactor / playbackSpeed.coerceIn(0.5f, 4f)
                viewportWidth / scrollDurationSec
            }
            val frameDeltaMs = if (lastMoveFrameTime > 0L) {
                (frameTime - lastMoveFrameTime).coerceIn(1L, 100L)
            } else 16L
            lastMoveFrameTime = frameTime
            val deltaPx = moveSpeed * (frameDeltaMs / 1000f)

            val iter = activeScrollDanmaku.iterator()
            while (iter.hasNext()) {
                val track = iter.next()
                track.x -= deltaPx
                scrollTracks[track.trackIndex] = track.x + track.textWidth
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

            delay(16)
        }
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
                    strokeWidth = config.strokeWidth,
                    fillPaint = fillPaint,
                    strokePaint = strokePaint
                )
            }

            // 绘制固定弹幕
            activeFixedDanmaku.forEach { fixed ->
                val color = Color(fixed.item.toColorInt())
                val x = (viewportWidth - fixed.textWidth) / 2f
                drawDanmakuText(
                    drawScope = this,
                    text = fixed.item.text,
                    x = x,
                    y = fixed.y,
                    fontSizePx = with(density) { baseFontSize.dp.toPx() },
                    color = color.copy(alpha = alpha),
                    strokeWidth = config.strokeWidth,
                    fillPaint = fillPaint,
                    strokePaint = strokePaint
                )
            }
        }
    }
}

/**
 * 添加滚动弹幕，返回是否成功。失败时加入重试队列。
 */
private fun addScrollDanmaku(
    item: DanmakuItem,
    key: Pair<String, Long>,
    now: Long,
    textWidth: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    trackHeight: Float,
    maxTracks: Int,
    scrollTracks: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Float>,
    activeScrollDanmaku: androidx.compose.runtime.snapshots.SnapshotStateList<DanmakuTrack>,
    processedSet: MutableSet<Pair<String, Long>>,
    retryQueue: MutableMap<Pair<String, Long>, DanmakuItem>,
    frameTime: Long,
    density: androidx.compose.ui.unit.Density
): Boolean {
    val spacing = with(density) { 20.dp.toPx() }
    for (trackIndex in 0 until maxTracks) {
        val lastEnd = scrollTracks[trackIndex] ?: -Float.MAX_VALUE
        if (lastEnd + spacing < viewportWidth) {
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
            processedSet.add(key)
            return true
        }
    }
    // 所有轨道都满：放入重试队列，等有轨道空出再上屏
    retryQueue[key] = item
    return false
}

/**
 * 添加固定弹幕（顶部/底部），返回是否成功。失败时加入重试队列。
 */
private fun addFixedDanmaku(
    item: DanmakuItem,
    key: Pair<String, Long>,
    now: Long,
    textWidth: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    trackHeight: Float,
    maxTracks: Int,
    topTracks: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Long>,
    bottomTracks: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Long>,
    activeFixedDanmaku: androidx.compose.runtime.snapshots.SnapshotStateList<FixedDanmakuTrack>,
    processedSet: MutableSet<Pair<String, Long>>,
    retryQueue: MutableMap<Pair<String, Long>, DanmakuItem>,
    frameTime: Long
): Boolean {
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
            trackMap[trackIndex] = now + 4000
            processedSet.add(key)
            return true
        }
    }
    retryQueue[key] = item
    return false
}

/**
 * 使用 nativeCanvas 绘制带描边的弹幕文本（复用 Paint）
 */
private fun drawDanmakuText(
    drawScope: DrawScope,
    text: String,
    x: Float,
    y: Float,
    fontSizePx: Float,
    color: Color,
    strokeWidth: Float,
    fillPaint: android.graphics.Paint,
    strokePaint: android.graphics.Paint
) {
    drawScope.drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas

        fillPaint.color = color.toArgb()
        fillPaint.textSize = fontSizePx
        strokePaint.color = android.graphics.Color.BLACK
        strokePaint.textSize = fontSizePx
        strokePaint.strokeWidth = strokeWidth

        // 先画描边
        nativeCanvas.drawText(text, x, y + fontSizePx, strokePaint)
        // 再画填充
        nativeCanvas.drawText(text, x, y + fontSizePx, fillPaint)
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
