package com.workout2scroll.ml

import org.junit.Assert.*
import org.junit.Test

class VideoFingerprintTest {
    @Test fun sameContentHasSameFingerprintRegardlessOfStream() {
        val content = ByteArray(200_000) { (it % 256).toByte() }
        val first = VideoFingerprint.calculate(content.inputStream())
        assertEquals(64, first.length)
        assertEquals(first, VideoFingerprint.calculate(content.inputStream()))
        content[100_000] = 42
        assertNotEquals(first, VideoFingerprint.calculate(content.inputStream()))
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun hashingHonorsCancellation() {
        VideoFingerprint.calculate(ByteArray(10).inputStream()) {
            throw java.util.concurrent.CancellationException()
        }
    }
}