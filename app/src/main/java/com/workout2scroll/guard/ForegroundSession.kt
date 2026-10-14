package com.workout2scroll.guard

class ForegroundSession(startMs: Long) {
    private var lastUpdateMs = startMs
    private var active = false

    fun update(nowMs: Long, instagramForeground: Boolean, interactive: Boolean): Long {
        val elapsed = if (active) (nowMs - lastUpdateMs).coerceAtLeast(0) else 0L
        lastUpdateMs = nowMs
        active = instagramForeground && interactive
        return elapsed
    }
}