package com.workout2scroll.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.workout2scroll.utils.VideoDurationReader
import kotlinx.coroutines.*
import kotlin.math.min

class VideoProcessor(
    private val context: Context,
    private val poseDetector: PoseDetector,
    private val squatCounter: RepCounter,
    private val onProgress: (progress: Int, repetitions: Int) -> Unit,
    private val onComplete: (totalRepetitions: Int) -> Unit,
    private val onError: (error: String) -> Unit
) {

    companion object {
        private const val TAG = "W2S.Video"
        private const val FRAME_INTERVAL_MS = 100 // Process every 100ms (10 FPS)
        private const val MAX_VIDEO_DURATION_MS = 600_000 // 10 minutes max
        private const val MIN_FRAME_WIDTH = 224
        private const val MIN_FRAME_HEIGHT = 224
        private const val MIN_VIDEO_DURATION_MS = 1000 // At least 1 second
        private const val DEFAULT_FRAME_RATE = 30.0f
    }

    private var processingJob: Job? = null
    private var isProcessing = false

    suspend fun processVideo(videoUri: Uri): Unit = coroutineScope {
        if (isProcessing) {
            Log.w(TAG, "Video processing request rejected: already running")
            onError("Video processing already in progress")
            return@coroutineScope
        }
        isProcessing = true
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Video processing started: scheme=${videoUri.scheme} poseReady=${poseDetector.isReady}")
        processingJob = coroutineContext[Job]
        try {
          withContext(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            try {
                squatCounter.reset()

                retriever = MediaMetadataRetriever()

                // Try to set data source with better error handling
                try {
                    Log.d(TAG, "Opening video data source: scheme=${videoUri.scheme}")
                    retriever.setDataSource(context, videoUri)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception accessing video", e)
                    withContext(Dispatchers.Main) {
                        onError("Permission denied. Please grant storage permission and try again.")
                    }
                    return@withContext
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid video file argument", e)
                    withContext(Dispatchers.Main) {
                        onError("Invalid video file. Please select a valid video format (MP4, MOV, AVI).")
                    }
                    return@withContext
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting data source", e)
                    withContext(Dispatchers.Main) {
                        onError("Unable to read video file. Error: ${e.message}")
                    }
                    return@withContext
                }

                // Extract video metadata with multiple fallback approaches
                val videoMetadata = extractVideoMetadata(retriever, videoUri)

                Log.d(TAG, "Extracted metadata: duration=${videoMetadata.duration}ms, " +
                        "size=${videoMetadata.width}x${videoMetadata.height}, " +
                        "hasVideo=${videoMetadata.hasVideo}, frameRate=${videoMetadata.frameRate}")

                // Validate the extracted metadata
                val validationResult = validateVideoMetadata(videoMetadata)
                if (!validationResult.isValid) {
                    Log.w(TAG, "Video validation failed: ${validationResult.errorMessage}")
                    withContext(Dispatchers.Main) {
                        onError(validationResult.errorMessage)
                    }
                    return@withContext
                }

                val duration = videoMetadata.duration

                // Progress update: Video loaded successfully
                withContext(Dispatchers.Main) {
                    onProgress(5, 0)
                }

                // Process frames with improved error handling
                val totalFrames = VideoTimeline.frameCount(duration)
                Log.i(TAG, "Video sampling plan: durationMs=$duration intervalMs=${VideoTimeline.INTERVAL_MS} frames=$totalFrames")
                var processedFrames = 0
                var currentSquatCount = 0
                var successfulFrames = 0

                for (timeUs in VideoTimeline.timestampsUs(duration)) {
                    ensureActive()

                    try {
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                            try {
                                // Detect pose in frame
                                val poseData = poseDetector.detectPoseFromBitmap(bitmap)

                                squatCounter.analyzePose(poseData)
                                currentSquatCount = squatCounter.getCount()
                                successfulFrames++
                                if (successfulFrames == 1 || successfulFrames % 60 == 0) {
                                    Log.d(TAG, "Frame inferred: timestampUs=$timeUs successful=$successfulFrames/$totalFrames confidence=${poseData.confidence} squats=$currentSquatCount elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        } else {
                            squatCounter.resetMovement()
                            Log.w(TAG, "Frame extraction returned no usable bitmap: timestampUs=$timeUs")
                        }

                        processedFrames++
                        val progress = ((processedFrames * 95 / totalFrames) + 5).coerceAtMost(100)

                        // Update progress on main thread every 10 frames to avoid overwhelming UI
                        if (processedFrames % 10 == 0 || processedFrames == totalFrames) {
                            withContext(Dispatchers.Main) {
                                onProgress(progress, currentSquatCount)
                            }
                        }

                        // Small delay to prevent overwhelming the system
                        delay(5)

                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        squatCounter.resetMovement()
                        if (!poseDetector.isReady) throw e
                        Log.w(TAG, "Frame processing failed: timestampUs=$timeUs attempted=$processedFrames successful=$successfulFrames", e)
                        // Skip frame if there's an error but continue processing
                        processedFrames++
                        continue
                    }
                }

                // Final validation
                if (successfulFrames == 0) {
                    Log.e(TAG, "No video frames successfully inferred: attempted=$processedFrames planned=$totalFrames")
                    withContext(Dispatchers.Main) {
                        onError("No frames could be processed. The video format may not be supported.")
                    }
                    return@withContext
                }

                // Complete processing
                withContext(Dispatchers.Main) {
                    if (isProcessing) {
                        Log.i(TAG, "Video processing complete: attempted=$processedFrames successful=$successfulFrames planned=$totalFrames squats=$currentSquatCount elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                        onProgress(100, currentSquatCount)
                        onComplete(currentSquatCount)
                    }
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Video processing cancelled after ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Error processing video: ${e.message ?: "Unknown error"}")
                }
                Log.e(TAG, "Video processing failed", e)
            } finally {
                try {
                    retriever?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Video retriever release failed", e)
                }
            }
          }
        } finally {
            Log.i(TAG, "Video processing ended: elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
            isProcessing = false
            processingJob = null
        }
    }

    fun cancelProcessing() {
        Log.d(TAG, "Cancelling video processing")
        isProcessing = false
        processingJob?.cancel()
    }

    fun isCurrentlyProcessing(): Boolean = isProcessing

    private data class VideoMetadata(
        val duration: Long,
        val width: Int,
        val height: Int,
        val frameRate: Float,
        val hasVideo: Boolean,
        val mimeType: String?
    )

    private data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String = ""
    )

    private fun extractVideoMetadata(retriever: MediaMetadataRetriever, videoUri: Uri): VideoMetadata {
        Log.d(TAG, "Extracting video metadata: scheme=${videoUri.scheme}")

        // Extract basic metadata
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
        val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

        Log.d(TAG, "Raw metadata - duration: $durationStr, width: $widthStr, height: $heightStr, " +
                "frameRate: $frameRateStr, hasVideo: $hasVideoStr, mimeType: $mimeType")

        // Try multiple approaches to get duration
        val duration = VideoDurationReader.read(context, videoUri, retriever)

        val width = widthStr?.toIntOrNull() ?: 0
        val height = heightStr?.toIntOrNull() ?: 0
        val frameRate = frameRateStr?.toFloatOrNull() ?: DEFAULT_FRAME_RATE
        val hasVideo = hasVideoStr == "yes"

        Log.d(TAG, "Processed metadata - duration: ${duration}ms, width: $width, height: $height, " +
                "frameRate: $frameRate, hasVideo: $hasVideo")

        return VideoMetadata(duration, width, height, frameRate, hasVideo, mimeType)
    }

    private fun extractDurationWithFallbacks(retriever: MediaMetadataRetriever, durationStr: String?, videoUri: Uri): Long {
        // Method 1: Direct metadata extraction
        durationStr?.toLongOrNull()?.let { duration ->
            if (duration > 0) {
                Log.d(TAG, "Duration extracted from metadata: ${duration}ms")
                return duration
            }
        }

        // Method 2: Estimate from frame count and frame rate
        try {
            val frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)

            val frameCount = frameCountStr?.toLongOrNull()
            val frameRate = frameRateStr?.toFloatOrNull()

            if (frameCount != null && frameRate != null && frameRate > 0) {
                val estimatedDuration = ((frameCount / frameRate) * 1000).toLong()
                if (estimatedDuration > 0) {
                    Log.d(TAG, "Duration estimated from frame count: ${estimatedDuration}ms " +
                            "(${frameCount} frames at ${frameRate} fps)")
                    return estimatedDuration
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to estimate duration from frame count", e)
        }

        // Method 3: Try to extract frames at different times to estimate duration
        try {
            val testTimes = listOf(1000000L, 5000000L, 10000000L, 30000000L) // 1s, 5s, 10s, 30s in microseconds
            var maxValidTime = 0L

            for (timeUs in testTimes) {
                try {
                    val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        maxValidTime = timeUs
                        bitmap.recycle()
                        Log.d(TAG, "Frame found at ${timeUs / 1000}ms")
                    } else {
                        break // No more frames available
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Duration probe failed: timestampUs=$timeUs", e)
                    break // Can't extract frame at this time
                }
            }

            if (maxValidTime > 0) {
                val estimatedDuration = maxValidTime / 1000 // Convert microseconds to milliseconds
                Log.d(TAG, "Duration estimated from frame sampling: ${estimatedDuration}ms")
                return estimatedDuration
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to estimate duration from frame sampling", e)
        }

        // Method 4: Check file properties if possible
        try {
            context.contentResolver.openInputStream(videoUri)?.use { inputStream ->
                val fileSize = inputStream.available()
                if (fileSize > 0) {
                    // Very rough estimate: assume 1MB per minute for standard video
                    val estimatedDuration = (fileSize / (1024 * 1024)) * 60 * 1000L
                    if (estimatedDuration > MIN_VIDEO_DURATION_MS) {
                        Log.d(TAG, "Duration estimated from file size: ${estimatedDuration}ms (${fileSize} bytes)")
                        return estimatedDuration
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to estimate duration from file size", e)
        }

        Log.w(TAG, "All duration extraction methods failed")
        return 0L
    }

    private fun validateVideoMetadata(metadata: VideoMetadata): ValidationResult {
        // Check if it's actually a video file
        if (!metadata.hasVideo) {
            return ValidationResult(false, "Selected file does not contain video data. Please select a video file.")
        }

        // Check MIME type
        metadata.mimeType?.let { mimeType ->
            if (!mimeType.startsWith("video/")) {
                return ValidationResult(false, "Selected file is not a video ($mimeType). Please select a video file.")
            }
        }

        // Check duration
        if (metadata.duration <= 0) {
            return ValidationResult(false, "Cannot read video duration. This video format may not be supported. Try MP4, MOV, or AVI format.")
        }

        if (metadata.duration < MIN_VIDEO_DURATION_MS) {
            return ValidationResult(false, "Video too short: ${metadata.duration/1000}s. Minimum duration is ${MIN_VIDEO_DURATION_MS/1000}s.")
        }

        if (metadata.duration > MAX_VIDEO_DURATION_MS) {
            return ValidationResult(false, "Video too long: ${metadata.duration/1000}s. Maximum duration is ${MAX_VIDEO_DURATION_MS/1000}s (10 minutes).")
        }

        // Check resolution
        if (metadata.width <= 0 || metadata.height <= 0) {
            return ValidationResult(false, "Invalid video resolution. Please select a valid video file.")
        }

        if (metadata.width < MIN_FRAME_WIDTH || metadata.height < MIN_FRAME_HEIGHT) {
            return ValidationResult(false, "Video resolution too low: ${metadata.width}x${metadata.height}. Minimum required: ${MIN_FRAME_WIDTH}x${MIN_FRAME_HEIGHT}")
        }

        return ValidationResult(true)
    }
}
