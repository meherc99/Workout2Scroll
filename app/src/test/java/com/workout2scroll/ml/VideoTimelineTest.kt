package com.workout2scroll.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelineTest {
    @Test fun samplesEntireClipInMicroseconds() {
        val timestamps = VideoTimeline.timestampsUs(10_000).toList()
        assertEquals(100, timestamps.size)
        assertEquals(0L, timestamps.first())
        assertEquals(9_900_000L, timestamps.last())
        assertTrue(timestamps.zipWithNext().all { (before, after) -> after - before == 100_000L })
    }

    @Test fun handlesPartialIntervalsAndMaximumLength() {
        assertEquals(11, VideoTimeline.frameCount(1_050))
        assertEquals(6_000, VideoTimeline.frameCount(600_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedClips() { VideoTimeline.timestampsUs(600_001) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortClips() { VideoTimeline.timestampsUs(999) }
}