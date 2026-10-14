package com.workout2scroll.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

object VideoDebugHelper {
    
    private const val TAG = "VideoDebugHelper"
    
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
            info.append("URI: $videoUri\n")
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
        
        try {
            retriever.setDataSource(context, videoUri)
            
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            
            val duration = durationStr?.toLongOrNull() ?: 0L
            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0
            
            val issues = mutableListOf<String>()
            
            if (duration == 0L) {
                issues.add("Cannot read video duration")
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
                    issues.add("Cannot extract video frames")
                } else {
                    testFrame.recycle()
                }
            } catch (e: Exception) {
                issues.add("Frame extraction failed: ${e.message}")
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
