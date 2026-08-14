package com.momo.app.ui.danmaku

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * 弹幕类型
 */
enum class DanmakuType {
    SCROLL,   // 滚动弹幕（从右到左）
    TOP,      // 顶部固定弹幕
    BOTTOM    // 底部固定弹幕
}

/**
 * 弹幕数据模型
 */
@Serializable
data class DanmakuItem(
    val text: String,
    val time: Long,          // 出现时间（毫秒）
    val color: Long = 0xFFFFFF,  // 颜色 (RGB)
    val type: DanmakuType = DanmakuType.SCROLL,
    val fontSize: Int = 16   // sp
) {
    fun toColorInt(): Int = color.toInt() and 0xFFFFFF
}

/**
 * 弹幕配置
 */
data class DanmakuConfig(
    val enabled: Boolean = true,
    val opacity: Float = 1.0f,          // 透明度 0-1
    val fontScale: Float = 1.0f,        // 字体缩放
    val speedFactor: Float = 1.0f,      // 速度倍率（越大越慢）
    val displayAreaRatio: Float = 0.5f,  // 显示区域比例 0.25/0.5/0.75/1.0
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val strokeWidth: Float = 2.5f        // 描边宽度
)

/**
 * 弹幕轨道状态（运行时）
 */
data class DanmakuTrack(
    val item: DanmakuItem,
    var x: Float,           // 当前 x 坐标
    val y: Float,           // y 坐标（轨道位置）
    var startTime: Long,   // 开始渲染时间
    val textWidth: Float,  // 文本宽度
    val trackIndex: Int     // 轨道编号
)

/**
 * 固定弹幕轨道状态（顶部/底部）
 */
data class FixedDanmakuTrack(
    val item: DanmakuItem,
    val y: Float,
    var startTime: Long,
    val textWidth: Float,
    val trackIndex: Int,
    val isTop: Boolean
)
