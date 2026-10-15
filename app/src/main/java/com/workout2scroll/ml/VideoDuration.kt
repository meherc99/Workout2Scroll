package com.workout2scroll.ml

object VideoDuration {
    fun resolve(metadataMs: String?, trackDurationsUs: List<Long>): Long {
        val metadata = metadataMs?.toLongOrNull()
        if (metadata != null && metadata > 0) return metadata
        return (trackDurationsUs.filter { it > 0 }.maxOrNull() ?: 0L) / 1_000L
    }
}