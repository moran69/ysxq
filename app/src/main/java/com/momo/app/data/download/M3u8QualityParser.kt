package com.momo.app.data.download

import java.net.URI

data class M3u8Variant(
    val bandwidth: Long,
    val resolution: String?,
    val url: String
)

object M3u8QualityParser {

    fun parseMasterPlaylist(content: String, baseUrl: String): List<M3u8Variant> {
        if (!content.contains("#EXT-X-STREAM-INF")) {
            return listOf(M3u8Variant(bandwidth = 0, resolution = null, url = baseUrl))
        }

        val variants = mutableListOf<M3u8Variant>()
        val lines = content.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = extractBandwidth(line)
                val resolution = extractResolution(line)

                for (j in (i + 1) until lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        val resolvedUrl = resolveUrl(baseUrl, nextLine)
                        variants.add(M3u8Variant(bandwidth = bandwidth, resolution = resolution, url = resolvedUrl))
                        break
                    }
                }
            }
            i++
        }

        return variants.ifEmpty { listOf(M3u8Variant(bandwidth = 0, resolution = null, url = baseUrl)) }
    }

    fun selectBestQuality(variants: List<M3u8Variant>): M3u8Variant {
        if (variants.size <= 1) return variants.firstOrNull()
            ?: throw IllegalArgumentException("No variants available")
        return variants.maxByOrNull { it.bandwidth }
            ?: variants.first()
    }

    private fun extractBandwidth(line: String): Long {
        val match = Regex("""BANDWIDTH=(\d+)""").find(line)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun extractResolution(line: String): String? {
        val match = Regex("""RESOLUTION=(\S+)""").find(line)
        return match?.groupValues?.get(1)
    }

    private fun resolveUrl(baseUrl: String, relativePath: String): String {
        return try {
            URI(baseUrl).resolve(relativePath).toString()
        } catch (_: Exception) {
            relativePath
        }
    }
}
