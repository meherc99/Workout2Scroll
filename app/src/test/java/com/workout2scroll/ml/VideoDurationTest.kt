package com.workout2scroll.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDurationTest {
    @Test fun prefersValidRetrieverDuration() {
        assertEquals(2_000L, VideoDuration.resolve("2000", listOf(3_000_000L)))
    }

    @Test fun missingMetadataUsesTrackMicroseconds() {
        assertEquals(2_500L, VideoDuration.resolve(null, listOf(2_500_000L)))
    }

    @Test fun malformedOrZeroMetadataUsesTrackDuration() {
        for (metadata in listOf("bad", "0", "-1", "")) {
            assertEquals(2_000L, VideoDuration.resolve(metadata, listOf(2_000_000L)))
        }
    }

    @Test fun ignoresInvalidTracksAndUsesLongestVideoTrack() {
        assertEquals(3_000L, VideoDuration.resolve(null, listOf(-1L, 0L, 2_000_000L, 3_000_000L)))
    }

    @Test fun doesNotInventMissingDuration() {
        assertEquals(0L, VideoDuration.resolve(null, emptyList()))
    }
}