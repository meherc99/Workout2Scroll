package com.workout2scroll.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

object VideoDebugHelper {
    
    private const val TAG = "W2S.VideoMetadata"
    
    fun logVideoInfo(context: Context, videoUri: Uri): String {
        val retriever = MediaMetadataRetriever()
        val info = StringBuilder()
        
        try {
            retriever.setDataSource(context, videoUri)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            
            info.append("Video Info:\n")
            info.append("Source scheme: ${videoUri.scheme}\n")
            info.append("Duration: ${duration}ms (${(duration?.toLongOrNull() ?: 0) / 1000}s)\n")
            info.append("Resolution: ${width}x${height}\n")
            info.append("Frame Rate: $frameRate fps\n")
            info.append("Bitrate: $bitrate\n")
            info.append("MIME Type: $mimeType\n")
            info.append("Rotation: $rotation°\n")
            
            // Test frame extraction
            try {
                val testFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (testFrame != null) {
                    info.append("Frame extraction: SUCCESS (${testFrame.width}x${testFrame.height})\n")
                    testFrame.recycle()
                } else {
                    info.append("Frame extraction: FAILED - null bitmap\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Diagnostic frame extraction failed", e)
                info.append("Frame extraction: FAILED - ${e.message}\n")
            }
            
            Log.d(TAG, info.toString())
            
        } catch (e: Exception) {
            val error = "Error reading video metadata: ${e.message}"
            info.append(error)
            Log.e(TAG, error, e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
            }
        }
        
        return info.toString()
    }
    
    fun validateVideoForProcessing(context: Context, videoUri: Uri): ValidationResult {
        val retriever = MediaMetadataRetriever()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Video validation started: scheme=${videoUri.scheme}")
        
        try {
            retriever.setDataSource(context, videoUri)
            
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            Log.d(TAG, "Raw validation metadata: duration=$durationStr width=$widthStr height=$heightStr mime=$mimeType hasVideo=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)} rotation=${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)}")
            
            val duration = VideoDurationReader.read(context, videoUri, retriever)
            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0
            
            val issues = mutableListOf<String>()
            
            if (duration == 0L) {
                Log.w(TAG, "No duration available from retriever or video tracks: raw=$durationStr")
                issues.add("Cannot read video duration. Download the video to your phone or export it as MP4 and try again.")
            } else if (duration < 1_000) {
                issues.add("Video too short. Minimum duration is 1 second.")
            } else if (duration > 600_000) { // 10 minutes
                issues.add("Video too long (${duration/1000}s > 600s)")
            }
            
            if (width < 224 || height < 224) {
                issues.add("Resolution too low (${width}x${height} < 224x224)")
            }
            
            if (mimeType == null) {
                issues.add("Cannot determine video format")
            } else if (!mimeType.startsWith("video/")) {
                issues.add("Not a video file: $mimeType")
            }
            
            // Test frame extraction
            try {
                val testFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (testFrame == null) {
                    Log.w(TAG, "Validation frame extraction returned null at timestampUs=0")
                    issues.add("Cannot extract video frames")
                } else {
                    Log.d(TAG, "Validation frame decoded: size=${testFrame.width}x${testFrame.height}")
                    testFrame.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Validation frame decoding failed", e)
                issues.add("Frame extraction failed: ${e.message}")
            }
            
            if (issues.isEmpty()) {
                Log.i(TAG, "Video validation passed: durationMs=$duration size=${width}x${height}")
            } else {
                Log.w(TAG, "Video validation failed: issues=$issues")
            }
            return ValidationResult(
                isValid = issues.isEmpty(),
                issues = issues,
                duration = duration,
                width = width,
                height = height,
                mimeType = mimeType
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Video validation could not read metadata", e)
            return ValidationResult(
                isValid = false,
                issues = listOf("Error accessing video file: ${e.message}"),
                duration = 0,
                width = 0,
                height = 0,
                mimeType = null
            )
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever: ${e.message}")
            }
            Log.i(TAG, "Video validation ended: elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
        }
    }
    
    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>,
        val duration: Long,
        val width: Int,
        val height: Int,
        val mimeType: String?
    )
}
