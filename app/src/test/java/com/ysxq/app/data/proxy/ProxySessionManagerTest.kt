package com.ysxq.app.data.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProxySessionManagerTest {

    private lateinit var manager: ProxySessionManager

    private fun createPlaylist(segmentCount: Int, durationPerSegment: Double = 10.0): M3u8Playlist {
        val segments = (0 until segmentCount).map { i ->
            M3u8Segment(url = "https://example.com/seg_$i.ts", duration = durationPerSegment)
        }
        return M3u8Playlist(segments = segments, totalDuration = segmentCount * durationPerSegment)
    }

    private fun createTestSession(
        playlist: M3u8Playlist,
        startIndex: Int = 0,
        resumePositionMs: Long = 0L
    ): ProxySession {
        return ProxySession(
            sessionId = "test-session-id",
            m3u8Url = "https://example.com/playlist.m3u8",
            playlist = playlist,
            startSegmentIndex = startIndex,
            currentSegmentIndex = startIndex,
            resumePositionMs = resumePositionMs
        )
    }

    @Before
    fun setUp() {
        manager = ProxySessionManager()
    }

    @Test
    fun `getNextSegment returns segments in order and null when done`() {
        val playlist = createPlaylist(segmentCount = 5)
        val session = createTestSession(playlist, startIndex = 0)
        manager.addTestSession(session)

        for (i in 0 until 5) {
            val segment = manager.getNextSegment(session.sessionId)
            assertNotNull("Segment $i should not be null", segment)
            assertEquals("https://example.com/seg_$i.ts", segment?.url)
        }

        val nullSegment = manager.getNextSegment(session.sessionId)
        assertNull("6th call should return null", nullSegment)
    }

    @Test
    fun `seekToTime updates currentSegmentIndex correctly`() {
        val playlist = createPlaylist(segmentCount = 5)
        val session = createTestSession(playlist, startIndex = 0)
        manager.addTestSession(session)

        val result = manager.seekToTime(session.sessionId, 35000)
        assertTrue("seekToTime should return true for existing session", result)

        val segment = manager.getNextSegment(session.sessionId)
        assertNotNull(segment)
        assertEquals("https://example.com/seg_3.ts", segment?.url)
    }

    @Test
    fun `isSessionComplete detects end`() {
        val playlist = createPlaylist(segmentCount = 3)

        val completeSession = createTestSession(playlist, startIndex = 3)
        manager.addTestSession(completeSession)
        assertTrue("Should be complete when index equals size", manager.isSessionComplete(completeSession.sessionId))

        val activeSession = createTestSession(playlist, startIndex = 2)
        manager.addTestSession(activeSession)
        assertFalse("Should not be complete when index is less than size", manager.isSessionComplete(activeSession.sessionId))
    }

    @Test
    fun `destroySession removes session`() {
        val playlist = createPlaylist(segmentCount = 3)
        val session = createTestSession(playlist)
        manager.addTestSession(session)

        assertNotNull("Session should exist before destroy", manager.getSession(session.sessionId))
        assertFalse("Session should be active before destroy", manager.getSession(session.sessionId)?.isActive == false)

        manager.destroySession(session.sessionId)

        assertNull("Session should be null after destroy", manager.getSession(session.sessionId))
    }

    @Test
    fun `createSession calculates correct startSegmentIndex from resumePosition`() {
        val playlist = createPlaylist(segmentCount = 5, durationPerSegment = 10.0)
        val expectedIndex = timeToSegmentIndex(playlist, 25000L)
        assertEquals("timeToSegmentIndex should return 2 for 25000ms with 10s segments", 2, expectedIndex)

        val session = createTestSession(playlist, startIndex = expectedIndex, resumePositionMs = 25000L)
        assertEquals(2, session.startSegmentIndex)
        assertEquals(2, session.currentSegmentIndex)
    }
}
