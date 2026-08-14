package com.momo.app.data.proxy

import java.net.URI

data class M3u8Segment(val url: String, val duration: Double, val isAfterDiscontinuity: Boolean = false)

data class M3u8Playlist(
    val segments: List<M3u8Segment>,
    val totalDuration: Double,
    val initSegmentUri: String? = null,
    val isMaster: Boolean = false,
    val mediaPlaylistUrl: String? = null
)

/** Parse m3u8/HLS playlist. Resolves relative URLs against [baseUrl]. Returns null for empty/invalid content. */
fun parseM3u8(content: String, baseUrl: String): M3u8Playlist? {
    if (content.isBlank()) return null

    val lines = content.lines()
    if (lines.isEmpty()) return null

    if (isMasterPlaylist(content)) {
        val variantUrl = getMediaPlaylistUrl(content, baseUrl)
        return M3u8Playlist(
            segments = emptyList(),
            totalDuration = 0.0,
            isMaster = true,
            mediaPlaylistUrl = variantUrl
        )
    }

    val segments = mutableListOf<M3u8Segment>()
    var totalDuration = 0.0
    var initSegmentUri: String? = null
    var pendingDuration = -1.0
    var afterDiscontinuity = false
    var i = 0

    while (i < lines.size) {
        val line = lines[i].trim()

        when {
            line == "#EXT-X-DISCONTINUITY" -> {
                afterDiscontinuity = true
                i++
            }
            line.isEmpty() || line.startsWith("#") && !line.startsWith("#EXTINF:") && !line.startsWith("#EXT-X-MAP:") -> {
                i++
            }
            line.startsWith("#EXT-X-MAP:") -> {
                val uriMatch = Regex("""URI="([^"]+)""").find(line)
                if (uriMatch != null) {
                    val uriPath = uriMatch.groupValues[1]
                    initSegmentUri = try {
                        URI(baseUrl).resolve(uriPath).toString()
                    } catch (_: Exception) {
                        uriPath
                    }
                }
                i++
            }
            line.startsWith("#EXTINF:") -> {
                val parts = line.removePrefix("#EXTINF:").split(",", limit = 2)
                val duration = parts[0].trim().toDoubleOrNull() ?: 0.0
                pendingDuration = duration

                var urlLine: String? = null
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                        urlLine = candidate
                        break
                    }
                    j++
                }

                if (urlLine != null && pendingDuration >= 0) {
                    val resolvedUrl = try {
                        URI(baseUrl).resolve(urlLine).toString()
                    } catch (_: Exception) {
                        urlLine
                    }
                    segments.add(M3u8Segment(url = resolvedUrl, duration = pendingDuration, isAfterDiscontinuity = afterDiscontinuity))
                    afterDiscontinuity = false
                    totalDuration += pendingDuration
                    pendingDuration = -1.0
                    i = j + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }

    return M3u8Playlist(
        segments = segments,
        totalDuration = totalDuration,
        initSegmentUri = initSegmentUri
    )
}

/** Find segment index for [timeMs]. Clamped to valid range. */
fun timeToSegmentIndex(playlist: M3u8Playlist, timeMs: Long): Int {
    val segments = playlist.segments
    if (segments.isEmpty()) return 0

    var accumulated = 0.0
    val targetSeconds = timeMs / 1000.0

    for ((index, segment) in segments.withIndex()) {
        accumulated += segment.duration
        if (accumulated > targetSeconds) {
            return index
        }
    }

    return (segments.size - 1).coerceAtLeast(0)
}

fun isMasterPlaylist(content: String): Boolean {
    return content.contains("#EXT-X-STREAM-INF")
}

/** Extract first variant URL from master playlist, resolved against [baseUrl]. */
fun getMediaPlaylistUrl(content: String, baseUrl: String): String? {
    if (!isMasterPlaylist(content)) return null

    val lines = content.lines()
    for (i in lines.indices) {
        val line = lines[i].trim()
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            for (j in (i + 1) until lines.size) {
                val nextLine = lines[j].trim()
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    return try {
                        URI(baseUrl).resolve(nextLine).toString()
                    } catch (_: Exception) {
                        nextLine
                    }
                }
            }
        }
    }
    return null
}
