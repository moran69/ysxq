package com.ysxq.app.data.proxy

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CastDiagnostics {
    private const val TAG = "CastDiag"
    private const val FILENAME = "cast_diagnostic.txt"
    private const val MAX_LOG_SIZE = 50000

    private val sb = StringBuilder()
    private var filePath: String? = null

    fun init(context: Context) {
        val dir = context.cacheDir
        filePath = File(dir, FILENAME).absolutePath
        sb.clear()
        log("=== Cast Diagnostic Started ===")
        log("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        log("Diagnostic file: $filePath")
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        sb.appendLine(msg)
        if (sb.length > MAX_LOG_SIZE) {
            sb.delete(0, sb.length - MAX_LOG_SIZE / 2)
        }
        flush()
    }

    fun logError(msg: String, e: Exception? = null) {
        val full = if (e != null) "$msg: ${e.message}\n${e.stackTraceToString().take(500)}" else msg
        Log.e(TAG, full)
        sb.appendLine("ERROR: $full")
        if (sb.length > MAX_LOG_SIZE) {
            sb.delete(0, sb.length - MAX_LOG_SIZE / 2)
        }
        flush()
    }

    private fun flush() {
        try {
            filePath?.let { File(it).writeText(sb.toString()) }
        } catch (_: Exception) {}
    }

    fun analyzeSegment(data: ByteArray, index: Int, url: String) {
        if (data.isEmpty()) {
            log("Segment #$index: EMPTY (0 bytes), url=${url.take(80)}")
            return
        }

        val firstByte = data[0]
        val isTs = firstByte == 0x47.toByte()

        val hexStart = data.take(16).joinToString(" ") { "%02x".format(it) }

        log("Segment #$index: ${data.size} bytes, sync=0x%02x (%s), hex=%s, url=%s".format(
            firstByte.toInt() and 0xFF,
            if (isTs) "TS" else "NOT-TS",
            hexStart,
            url.take(80)
        ))

        if (isTs && data.size >= 188) {
            var patFound = false
            var pmtFound = false
            var videoPid = -1
            var audioPid = -1
            var hasPts = false
            var offset = 0
            var packetCount = 0

            while (offset + 188 <= data.size && packetCount < 50) {
                if (data[offset] != 0x47.toByte()) { offset++; continue }
                val pid = ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset + 2].toInt() and 0xFF)
                val pusi = (data[offset + 1].toInt() and 0x40) != 0

                when (pid) {
                    0 -> patFound = true
                }

                if (pusi && pid != 0) {
                    val adaptationCtrl = (data[offset + 3].toInt() shr 4) and 0x03
                    var payloadOff = 4
                    if (adaptationCtrl == 0x03 || adaptationCtrl == 0x02) {
                        payloadOff += 1 + (data[payloadOff].toInt() and 0xFF)
                    }
                    if (payloadOff + 3 < offset + 188) {
                        val streamId = data[payloadOff + 3].toInt() and 0xFF
                        when {
                            streamId in 0xE0..0xEF -> {
                                videoPid = pid
                                if (payloadOff + 9 < offset + 188) {
                                    val flags = data[payloadOff + 7].toInt() and 0xC0
                                    if (flags == 0x80 || flags == 0xC0) hasPts = true
                                }
                            }
                            streamId in 0xC0..0xDF -> audioPid = pid
                            streamId == 0xBC -> log("  Packet at $offset: MAP stream (fMP4 init?)")
                        }
                    }
                }

                offset += 188
                packetCount++
            }

            log("  TS analysis: $packetCount packets scanned, PAT=$patFound, PMT/PIDs: video=$videoPid audio=$audioPid, hasPTS=$hasPts")
            if (!patFound) log("  WARNING: No PAT found in first 50 packets!")
        }

        if (!isTs) {
            if (data.size >= 8) {
                val boxType = String(data, 0, 4, Charsets.US_ASCII)
                val boxSize = ((data[0].toLong() and 0xFF) shl 24) or
                        ((data[1].toLong() and 0xFF) shl 16) or
                        ((data[2].toLong() and 0xFF) shl 8) or
                        (data[3].toLong() and 0xFF)
                log("  Container: MP4 box '$boxType' size=$boxSize")
                if (boxType == "moof") log("  → fMP4 segment (not MPEG-TS!)")
                if (boxType == "ftyp") log("  → MP4 init segment")
            }
            val asText = String(data, 0, minOf(200, data.size), Charsets.UTF_8)
            if (asText.contains("<") || asText.contains("{")) {
                log("  WARNING: Segment looks like HTML/JSON, not video data!")
                log("  Content preview: ${asText.take(200)}")
            }
        }
    }
}
