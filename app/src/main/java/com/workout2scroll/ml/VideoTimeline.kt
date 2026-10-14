package com.workout2scroll.ml

object VideoTimeline {
    const val INTERVAL_MS = 100L

    fun timestampsUs(durationMs: Long): LongProgression {
        require(durationMs in 1_000L..600_000L) { "Video must be between 1 second and 10 minutes" }
        return 0L until durationMs * 1_000L step INTERVAL_MS * 1_000L
    }

    fun frameCount(durationMs: Long): Int = timestampsUs(durationMs).count()
}