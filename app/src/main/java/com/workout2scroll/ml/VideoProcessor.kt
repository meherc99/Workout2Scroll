package com.workout2scroll.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.*
import kotlin.math.min

class VideoProcessor(
    private val context: Context,
    private val poseDetector: PoseDetector,
    private val squatCounter: SquatCounter,
    private val onProgress: (progress: Int, squatCount: Int) -> Unit,
    private val onComplete: (totalSquats: Int) -> Unit,
    private val onError: (error: String) -> Unit
) {
    
    companion object {
        private const val FRAME_INTERVAL_MS = 100 // Process every 100ms (10 FPS)
        private const val MAX_VIDEO_DURATION_MS = 600_000 // 10 minutes max
        private const val MIN_FRAME_WIDTH = 224
        private const val MIN_FRAME_HEIGHT = 224
    }
    
    private var processingJob: Job? = null
    private var isProcessing = false
    
    suspend fun processVideo(videoUri: Uri) {
        if (isProcessing) {
            onError("Video processing already in progress")
            return
        }
        
        processingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                isProcessing = true
                squatCounter.reset()
                
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, videoUri)
                
                // Get video metadata
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                
                val duration = durationStr?.toLongOrNull() ?: 0L
                val width = widthStr?.toIntOrNull() ?: 0
                val height = heightStr?.toIntOrNull() ?: 0
                
                // Validate video
                if (duration == 0L) {
                    withContext(Dispatchers.Main) {
                        onError("Unable to read video duration")
                    }
                    return@launch
                }
                
                if (duration > MAX_VIDEO_DURATION_MS) {
                    withContext(Dispatchers.Main) {
                        onError("Video too long. Maximum duration is 10 minutes.")
                    }
                    return@launch
                }
                
                if (width < MIN_FRAME_WIDTH || height < MIN_FRAME_HEIGHT) {
                    withContext(Dispatchers.Main) {
                        onError("Video resolution too low. Minimum ${MIN_FRAME_WIDTH}x${MIN_FRAME_HEIGHT}")
                    }
                    return@launch
                }
                
                // Process frames
                val totalFrames = (duration / FRAME_INTERVAL_MS).toInt()
                var processedFrames = 0
                var currentSquatCount = 0
                
                for (timeUs in 0L until duration step (FRAME_INTERVAL_MS * 1000L)) {
                    if (!isProcessing) break // Check if cancelled
                    
                    try {
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        
                        if (bitmap != null) {
                            // Detect pose in frame
                            val poseData = poseDetector.detectPoseFromBitmap(bitmap)
                            
                            if (poseData != null) {
                                val previousCount = squatCounter.getCount()
                                squatCounter.analyzePose(poseData)
                                val newCount = squatCounter.getCount()
                                
                                if (newCount > currentSquatCount) {
                                    currentSquatCount = newCount
                                }
                            }
                            
                            bitmap.recycle()
                        }
                        
                        processedFrames++
                        val progress = (processedFrames * 100 / totalFrames)
                        
                        // Update progress on main thread
                        withContext(Dispatchers.Main) {
                            onProgress(progress, currentSquatCount)
                        }
                        
                        // Small delay to prevent overwhelming the system
                        delay(10)
                        
                    } catch (e: Exception) {
                        // Skip frame if there's an error
                        continue
                    }
                }
                
                retriever.release()
                
                // Complete processing
                withContext(Dispatchers.Main) {
                    if (isProcessing) {
                        onComplete(currentSquatCount)
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Error processing video: ${e.message}")
                }
            } finally {
                isProcessing = false
            }
        }
    }
    
    fun cancelProcessing() {
        isProcessing = false
        processingJob?.cancel()
    }
    
    fun isCurrentlyProcessing(): Boolean = isProcessing
}
