package com.workout2scroll.utils

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.workout2scroll.ml.VideoDuration

object VideoDurationReader {
    private const val TAG = "W2S.VideoMetadata"

    fun read(context: Context, uri: Uri, retriever: MediaMetadataRetriever): Long {
        val metadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val directDuration = VideoDuration.resolve(metadata, emptyList())
        if (directDuration > 0) return directDuration
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val durations = mutableListOf<Long>()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true && format.containsKey(MediaFormat.KEY_DURATION)) {
                    durations.add(format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            VideoDuration.resolve(metadata, durations).also {
                Log.i(TAG, "Track duration fallback: durationMs=$it videoTracksWithDuration=${durations.size}")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read video track duration", error)
            0L
        } finally {
            extractor.release()
        }
    }
}