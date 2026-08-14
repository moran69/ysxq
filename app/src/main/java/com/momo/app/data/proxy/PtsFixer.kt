package com.momo.app.data.proxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixes PTS/DTS discontinuities in MPEG-TS segments.
 *
 * HLS segments separated by #EXT-X-DISCONTINUITY have PTS values that jump or reset.
 * Many TV decoders (especially VIDAA/Hisense) crash (1002 error) on non-monotonic PTS.
 *
 * This class scans 188-byte TS packets, extracts PTS/DTS from PES headers,
 * and rewrites them to be monotonically increasing across all segments.
 *
 * Reference: ISO/IEC 13818-1, taktik/mpegts-streamer ContinuityFixer
 */
class PtsFixer {

    companion object {
        private const val TAG = "PtsFixer"
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE: Byte = 0x47
        private const val PTS_DTS_MASK: Int = 0xC0
        private const val PTS_ONLY: Int = 0x80
        private const val PTS_AND_DTS: Int = 0xC0
        private const val PTS_FREQ = 90000.0
        private const val MAX_PTS = 0x1FFFFFFFFL // 33-bit max
    }

    private val pidState = ConcurrentHashMap<Int, PidState>()

    private class PidState(
        var lastOutputPts: Long = -1,
        var ptsOffset: Long = 0
    )

    @Synchronized
    fun fixSegment(data: ByteArray): ByteArray {
        if (data.size < TS_PACKET_SIZE) return data
        if (data[0] != TS_SYNC_BYTE) return data

        val result = data.copyOf()
        var offset = 0

        while (offset + TS_PACKET_SIZE <= result.size) {
            if (result[offset] != TS_SYNC_BYTE) {
                offset++
                continue
            }

            val packet = result.copyOfRange(offset, offset + TS_PACKET_SIZE)
            val fixedPacket = fixPacket(packet)
            System.arraycopy(fixedPacket, 0, result, offset, TS_PACKET_SIZE)

            offset += TS_PACKET_SIZE
        }

        return result
    }

    private fun fixPacket(packet: ByteArray): ByteArray {
        // Byte 1: TEI(1) | PUSI(1) | Transport Priority(1) | PID(13)
        // Byte 2: PID continued
        // Byte 3: Scrambling(2) | Adaptation field ctrl(2) | Continuity counter(4)
        val pusi = (packet[1].toInt() and 0x40) != 0
        if (!pusi) return packet

        val adaptationCtrl = (packet[3].toInt() shr 4) and 0x03
        var payloadOffset = 4

        if (adaptationCtrl == 0x02 || adaptationCtrl == 0x00) return packet

        if (adaptationCtrl == 0x03) {
            if (payloadOffset >= TS_PACKET_SIZE) return packet
            val adaptationFieldLength = packet[payloadOffset].toInt() and 0xFF
            payloadOffset += 1 + adaptationFieldLength
        }

        if (payloadOffset + 9 >= TS_PACKET_SIZE) return packet

        // Check PES start code: 00 00 01
        if (packet[payloadOffset].toInt() != 0 ||
            packet[payloadOffset + 1].toInt() != 0 ||
            packet[payloadOffset + 2].toInt() != 1
        ) return packet

        val streamId = packet[payloadOffset + 3].toInt() and 0xFF
        // Only fix video (0xE0-0xEF) and audio (0xC0-0xDF) streams
        val isVideo = streamId in 0xE0..0xEF
        val isAudio = streamId in 0xC0..0xDF
        if (!isVideo && !isAudio) return packet

        val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
        val ptsDtsFlags = (packet[payloadOffset + 7].toInt() and PTS_DTS_MASK)

        if (ptsDtsFlags == PTS_ONLY || ptsDtsFlags == PTS_AND_DTS) {
            val ptsOffset = payloadOffset + 9
            if (ptsOffset + 5 > TS_PACKET_SIZE) return packet

            val originalPts = readPts(packet, ptsOffset)
            val state = pidState.getOrPut(pid) { PidState() }

            val newPts: Long
            if (state.lastOutputPts < 0) {
                // First packet for this PID — use original PTS as base
                newPts = originalPts
                state.ptsOffset = 0
            } else {
                // Expected next PTS: last + some delta
                // Detect discontinuity: if PTS jumps backward or forward by more than 5 seconds
                val delta = originalPts - (state.lastOutputPts - state.ptsOffset)
                val fiveSecondsInPts = (5.0 * PTS_FREQ).toLong()

                if (delta < -fiveSecondsInPts || delta > fiveSecondsInPts) {
                    // Discontinuity detected — adjust offset to make PTS continuous
                    val expectedPts = state.lastOutputPts + 1
                    state.ptsOffset += expectedPts - originalPts
                    Log.d(TAG, "PTS discontinuity on PID $pid: original=$originalPts, last=${state.lastOutputPts}, new offset=${state.ptsOffset}")
                }

                newPts = originalPts + state.ptsOffset
            }

            // Clamp to 33-bit range
            val clampedPts = newPts and MAX_PTS
            writePts(packet, ptsOffset, clampedPts)
            state.lastOutputPts = clampedPts

            // Fix DTS if present (PTS_DTS_flags = 11)
            if (ptsDtsFlags == PTS_AND_DTS) {
                val dtsOffset = ptsOffset + 5
                if (dtsOffset + 5 <= TS_PACKET_SIZE) {
                    val originalDts = readPts(packet, dtsOffset)
                    val newDts = originalDts + state.ptsOffset
                    writePts(packet, dtsOffset, newDts and MAX_PTS)
                }
            }
        }

        return packet
    }

    /** Read 33-bit PTS/DTS value from 5 bytes at given offset in PES header */
    private fun readPts(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        val b4 = data[offset + 4].toInt() and 0xFF

        return ((b0.toLong() and 0x0E) shl 29) or
                ((b1.toLong()) shl 22) or
                ((b2.toLong() and 0xFE) shl 14) or
                (b3.toLong() shl 7) or
                ((b4.toLong() shr 1) and 0x7F)
    }

    /** Write 33-bit PTS/DTS value to 5 bytes at given offset in PES header */
    private fun writePts(data: ByteArray, offset: Int, pts: Long) {
        val p = pts and MAX_PTS
        // Marker bits: '0010' (PTS only) or '0011' (PTS+DTS) for first nibble
        // We preserve the existing top nibble and marker bits
        data[offset] = ((data[offset].toInt() and 0xF1) or ((p shr 29).toInt() and 0x0E) or 0x01).toByte()
        data[offset + 1] = ((p shr 22) and 0xFF).toByte()
        data[offset + 2] = (((p shr 14) and 0xFE) or 0x01).toByte()
        data[offset + 3] = ((p shr 7) and 0xFF).toByte()
        data[offset + 4] = (((p and 0x7F) shl 1) or 0x01).toByte()
    }

    @Synchronized
    fun reset() {
        pidState.clear()
    }
}
