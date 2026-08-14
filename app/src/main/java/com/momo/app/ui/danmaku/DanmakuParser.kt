package com.momo.app.ui.danmaku

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 弹幕解析器
 *
 * 支持标准 B站 XML 弹幕格式:
 * <i>
 *   <d p="time,type,color,uid">text</d>
 *   ...
 * </i>
 *
 * 也支持简单 JSON 格式。
 */
object DanmakuParser {

    /**
     * 解析 B站标准 XML 弹幕
     * 格式: <d p="12.345,1,16777215,123456,0">弹幕文本</d>
     * p 属性: time,type,color,uid,pool
     *   type: 1=滚动 4=底部 5=顶部
     *   color: 十进制 RGB
     */
    fun parseXml(xml: String): List<DanmakuItem> {
        val result = mutableListOf<DanmakuItem>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "d") {
                    val p = parser.getAttributeValue(null, "p")
                    val text = parser.nextText()
                    if (p != null && text.isNotEmpty()) {
                        val parts = p.split(",")
                        if (parts.size >= 3) {
                            val time = (parts[0].toFloatOrNull() ?: 0f) * 1000  // 秒→毫秒
                            val typeCode = parts[1].toIntOrNull() ?: 1
                            val color = parts[2].toLongOrNull() ?: 16777215L  // 默认白色

                            val type = when (typeCode) {
                                4 -> DanmakuType.BOTTOM
                                5 -> DanmakuType.TOP
                                else -> DanmakuType.SCROLL
                            }

                            result.add(DanmakuItem(
                                text = text,
                                time = time.toLong(),
                                color = color,
                                type = type
                            ))
                        }
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            // 解析失败返回空列表
        }
        return result
    }

    /**
     * 生成示例弹幕（用于没有弹幕源时也能看到效果）
     */
    fun generateSampleDanmaku(): List<DanmakuItem> {
        val samples = listOf(
            "前方高能" to 1000L,
            "哈哈哈哈" to 3000L,
            "来了来了" to 5000L,
            "名场面" to 7000L,
            "好家伙" to 9000L,
            "前方高能注意" to 11000L,
            "这波操作666" to 13000L,
            "我裂开了" to 15000L,
            "草（一种植物）" to 17000L,
            "awsl" to 19000L,
            "泪目了" to 21000L,
            "弹幕护体" to 23000L,
            "爷青回" to 25000L,
            "这音乐绝了" to 27000L,
            "笔记+1" to 29000L,
            "下集催更" to 31000L,
            "完结撒花" to 33000L,
            "太真实了" to 35000L,
            "不明觉厉" to 37000L,
            "学到了学到了" to 39000L,
            "这画质太棒了" to 41000L,
            "一键三连" to 43000L,
            "硬币奉上" to 45000L,
            "收藏了" to 47000L,
            "关注了关注了" to 49000L
        )
        return samples.mapIndexed { i, (text, time) ->
            DanmakuItem(
                text = text,
                time = time,
                color = if (i % 5 == 0) 0xFF6666 else if (i % 3 == 0) 0xFFFF00 else 0xFFFFFF,
                type = if (i % 7 == 0) DanmakuType.TOP else if (i % 11 == 0) DanmakuType.BOTTOM else DanmakuType.SCROLL
            )
        }
    }
}
