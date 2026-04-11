package com.ysxq.app.data.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8ParserTest {

    @Test
    fun `parse media playlist with 5 segments`() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,seg1
            seg001.ts
            #EXTINF:10.0,seg2
            seg002.ts
            #EXTINF:10.0,seg3
            seg003.ts
            #EXTINF:10.0,seg4
            seg004.ts
            #EXTINF:10.0,seg5
            seg005.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = parseM3u8(m3u8, "https://cdn.example.com/video/index.m3u8")

        assertNotNull(playlist)
        playlist!!
        assertEquals(5, playlist.segments.size)
        assertEquals(50.0, playlist.totalDuration, 0.001)
        assertEquals(
            "https://cdn.example.com/video/seg001.ts",
            playlist?.segments?.get(0)?.url
        )
        assertEquals(
            "https://cdn.example.com/video/seg005.ts",
            playlist?.segments?.get(4)?.url
        )
    }

    @Test
    fun `parse master playlist`() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()

        val playlist = parseM3u8(m3u8, "https://cdn.example.com/video/master.m3u8")

        assertNotNull(playlist)
        playlist!!
        assertTrue(playlist.isMaster)
        assertEquals(
            "https://cdn.example.com/video/1080p/index.m3u8",
            playlist?.mediaPlaylistUrl
        )
        assertEquals(0, playlist?.segments?.size)
    }

    @Test
    fun `resolve relative URLs`() {
        val m3u8 = """
            #EXTM3U
            #EXTINF:10.0,
            seg001.ts
            #EXTINF:10.0,
            /absolute/path/seg002.ts
            #EXTINF:10.0,
            https://other.cdn.com/full/path/seg003.ts
        """.trimIndent()

        val baseUrl = "https://cdn.example.com/video/index.m3u8"
        val playlist = parseM3u8(m3u8, "https://cdn.example.com/video/index.m3u8")

        assertNotNull(playlist)
        playlist!!
        assertEquals(3, playlist.segments.size)

        assertEquals(
            "https://cdn.example.com/video/seg001.ts",
            playlist?.segments?.get(0)?.url
        )
        assertEquals(
            "https://cdn.example.com/absolute/path/seg002.ts",
            playlist?.segments?.get(1)?.url
        )
        assertEquals(
            "https://other.cdn.com/full/path/seg003.ts",
            playlist?.segments?.get(2)?.url
        )
    }

    @Test
    fun `parse EXT-X-MAP init segment`() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:10.0,seg1
            seg001.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = parseM3u8(m3u8, "https://cdn.example.com/video/index.m3u8")

        assertNotNull(playlist)
        playlist!!
        assertEquals(
            "https://cdn.example.com/video/init.mp4",
            playlist?.initSegmentUri
        )
    }

    @Test
    fun `edge cases handle empty, no EXTINF, and malformed input`() {
        assertNull(parseM3u8("", "https://example.com/index.m3u8"))
        assertNull(parseM3u8("   ", "https://example.com/index.m3u8"))

        val noExtinf = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-ENDLIST
        """.trimIndent()
        val result = parseM3u8(noExtinf, "https://example.com/index.m3u8")
        assertNotNull(result)
        result!!
        assertEquals(0, result.segments.size)
        assertEquals(0.0, result.totalDuration, 0.001)

        val malformed = """
            garbage line
            #EXTINF:notanumber,
            seg.ts
        """.trimIndent()
        val malformedResult = parseM3u8(malformed, "https://example.com/index.m3u8")
        assertNotNull(malformedResult)
        malformedResult!!
        assertEquals(1, malformedResult.segments.size)
        assertEquals(0.0, malformedResult.segments[0].duration, 0.001)
    }

    @Test
    fun `timeToSegmentIndex accuracy`() {
        val m3u8 = """
            #EXTM3U
            #EXTINF:10.0,a
            seg0.ts
            #EXTINF:10.0,b
            seg1.ts
            #EXTINF:10.0,c
            seg2.ts
            #EXTINF:10.0,d
            seg3.ts
            #EXTINF:10.0,e
            seg4.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = parseM3u8(m3u8, "https://cdn.example.com/video/index.m3u8")
        assertNotNull(playlist)

        assertEquals(0, timeToSegmentIndex(playlist!!, 0))
        assertEquals(0, timeToSegmentIndex(playlist, 5000))
        assertEquals(1, timeToSegmentIndex(playlist, 10000))
        assertEquals(2, timeToSegmentIndex(playlist, 25000))
        assertEquals(4, timeToSegmentIndex(playlist, 49999))
        assertEquals(4, timeToSegmentIndex(playlist, 50000))
    }
}
