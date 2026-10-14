package com.workout2scroll.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.workout2scroll.R
import com.workout2scroll.ml.PoseData
import com.workout2scroll.ml.PoseDetector

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var poseData: PoseData? = null
    
    private val linePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.pose_line_color)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val pointPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.pose_point_color)
        strokeWidth = 4f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val confidenceThreshold = 0.3f
    
    // Define pose connections for skeleton drawing
    private val poseConnections = listOf(
        // Head
        Pair(PoseDetector.LEFT_EAR, PoseDetector.LEFT_EYE),
        Pair(PoseDetector.LEFT_EYE, PoseDetector.NOSE),
        Pair(PoseDetector.NOSE, PoseDetector.RIGHT_EYE),
        Pair(PoseDetector.RIGHT_EYE, PoseDetector.RIGHT_EAR),
        
        // Torso
        Pair(PoseDetector.LEFT_SHOULDER, PoseDetector.RIGHT_SHOULDER),
        Pair(PoseDetector.LEFT_SHOULDER, PoseDetector.LEFT_HIP),
        Pair(PoseDetector.RIGHT_SHOULDER, PoseDetector.RIGHT_HIP),
        Pair(PoseDetector.LEFT_HIP, PoseDetector.RIGHT_HIP),
        
        // Left arm
        Pair(PoseDetector.LEFT_SHOULDER, PoseDetector.LEFT_ELBOW),
        Pair(PoseDetector.LEFT_ELBOW, PoseDetector.LEFT_WRIST),
        
        // Right arm
        Pair(PoseDetector.RIGHT_SHOULDER, PoseDetector.RIGHT_ELBOW),
        Pair(PoseDetector.RIGHT_ELBOW, PoseDetector.RIGHT_WRIST),
        
        // Left leg
        Pair(PoseDetector.LEFT_HIP, PoseDetector.LEFT_KNEE),
        Pair(PoseDetector.LEFT_KNEE, PoseDetector.LEFT_ANKLE),
        
        // Right leg
        Pair(PoseDetector.RIGHT_HIP, PoseDetector.RIGHT_KNEE),
        Pair(PoseDetector.RIGHT_KNEE, PoseDetector.RIGHT_ANKLE)
    )
    
    fun updatePose(pose: PoseData) {
        this.poseData = pose
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val pose = poseData ?: return
        
        if (width == 0 || height == 0 || pose.imageWidth <= 0 || pose.imageHeight <= 0) return
        val scale = minOf(width.toFloat() / pose.imageWidth, height.toFloat() / pose.imageHeight)
        val scaleX = pose.imageWidth * scale
        val scaleY = pose.imageHeight * scale
        val checkpoint = canvas.save()
        canvas.translate((width - scaleX) / 2f, (height - scaleY) / 2f)
        if (pose.mirrored) {
            canvas.translate(scaleX, 0f)
            canvas.scale(-1f, 1f)
        }
        
        // Draw pose connections (skeleton)
        for (connection in poseConnections) {
            val startKeypoint = pose.keypoints.getOrNull(connection.first)
            val endKeypoint = pose.keypoints.getOrNull(connection.second)
            
            if (startKeypoint != null && endKeypoint != null &&
                startKeypoint.confidence > confidenceThreshold &&
                endKeypoint.confidence > confidenceThreshold) {
                
                val startX = startKeypoint.x * scaleX
                val startY = startKeypoint.y * scaleY
                val endX = endKeypoint.x * scaleX
                val endY = endKeypoint.y * scaleY
                
                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
        
        // Draw keypoints
        for (keypoint in pose.keypoints) {
            if (keypoint.confidence > confidenceThreshold) {
                val x = keypoint.x * scaleX
                val y = keypoint.y * scaleY
                canvas.drawCircle(x, y, 12f, pointPaint)
            }
        }
        
        // Highlight squat-relevant joints (hips, knees, ankles) with different color
        highlightSquatJoints(canvas, pose, scaleX, scaleY)
        canvas.restoreToCount(checkpoint)
    }
    
    private fun highlightSquatJoints(canvas: Canvas, pose: PoseData, scaleX: Float, scaleY: Float) {
        val squatJointPaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 6f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        val squatJoints = listOf(
            PoseDetector.LEFT_HIP,
            PoseDetector.RIGHT_HIP,
            PoseDetector.LEFT_KNEE,
            PoseDetector.RIGHT_KNEE,
            PoseDetector.LEFT_ANKLE,
            PoseDetector.RIGHT_ANKLE
        )
        
        for (jointIndex in squatJoints) {
            val keypoint = pose.keypoints.getOrNull(jointIndex)
            if (keypoint != null && keypoint.confidence > confidenceThreshold) {
                val x = keypoint.x * scaleX
                val y = keypoint.y * scaleY
                canvas.drawCircle(x, y, 16f, squatJointPaint)
            }
        }
    }
    
    fun clearPose() {
        poseData = null
        invalidate()
    }
}
