package com.workout2scroll.ml

import kotlin.math.*

class SquatCounter(private val onSquatDetected: () -> Unit) {
    
    private var squatCount = 0
    private var isInSquatPosition = false
    private var wasStanding = true
    
    // Thresholds for squat detection
    private val squatAngleThreshold = 120f // degrees
    private val standingAngleThreshold = 160f // degrees
    private val confidenceThreshold = 0.3f
    
    fun analyzePose(poseData: PoseData) {
        if (poseData.confidence < confidenceThreshold) return
        
        val keypoints = poseData.keypoints
        
        // Get required keypoints for squat detection
        val leftHip = keypoints.getOrNull(PoseDetector.LEFT_HIP)
        val rightHip = keypoints.getOrNull(PoseDetector.RIGHT_HIP)
        val leftKnee = keypoints.getOrNull(PoseDetector.LEFT_KNEE)
        val rightKnee = keypoints.getOrNull(PoseDetector.RIGHT_KNEE)
        val leftAnkle = keypoints.getOrNull(PoseDetector.LEFT_ANKLE)
        val rightAnkle = keypoints.getOrNull(PoseDetector.RIGHT_ANKLE)
        
        // Check if all required keypoints are detected with good confidence
        if (leftHip == null || rightHip == null || leftKnee == null || 
            rightKnee == null || leftAnkle == null || rightAnkle == null) return
            
        if (listOf(leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle)
            .any { it.confidence < confidenceThreshold }) return
        
        // Calculate knee angles for both legs
        val leftKneeAngle = calculateKneeAngle(leftHip, leftKnee, leftAnkle)
        val rightKneeAngle = calculateKneeAngle(rightHip, rightKnee, rightAnkle)
        
        // Use the minimum angle (more bent leg) for squat detection
        val minKneeAngle = min(leftKneeAngle, rightKneeAngle)
        
        detectSquatMovement(minKneeAngle)
    }
    
    private fun calculateKneeAngle(hip: Keypoint, knee: Keypoint, ankle: Keypoint): Float {
        // Calculate angle between hip-knee and knee-ankle vectors
        val hipToKnee = Vector2D(knee.x - hip.x, knee.y - hip.y)
        val kneeToAnkle = Vector2D(ankle.x - knee.x, ankle.y - knee.y)
        
        val dotProduct = hipToKnee.x * kneeToAnkle.x + hipToKnee.y * kneeToAnkle.y
        val magnitude1 = sqrt(hipToKnee.x * hipToKnee.x + hipToKnee.y * hipToKnee.y)
        val magnitude2 = sqrt(kneeToAnkle.x * kneeToAnkle.x + kneeToAnkle.y * kneeToAnkle.y)
        
        if (magnitude1 == 0f || magnitude2 == 0f) return 180f
        
        val cosAngle = dotProduct / (magnitude1 * magnitude2)
        val clampedCosAngle = cosAngle.coerceIn(-1f, 1f)
        
        return Math.toDegrees(acos(clampedCosAngle.toDouble())).toFloat()
    }
    
    private fun detectSquatMovement(kneeAngle: Float) {
        when {
            // Person is in squat position (knees bent)
            kneeAngle < squatAngleThreshold && !isInSquatPosition && wasStanding -> {
                isInSquatPosition = true
                wasStanding = false
            }
            
            // Person stood up from squat (completed one squat)
            kneeAngle > standingAngleThreshold && isInSquatPosition -> {
                isInSquatPosition = false
                wasStanding = true
                squatCount++
                onSquatDetected()
            }
            
            // Person is standing normally
            kneeAngle > standingAngleThreshold -> {
                wasStanding = true
                isInSquatPosition = false
            }
        }
    }
    
    fun getCount(): Int = squatCount
    
    fun reset() {
        squatCount = 0
        isInSquatPosition = false
        wasStanding = true
    }
    
    fun isCurrentlySquatting(): Boolean = isInSquatPosition
    
    private data class Vector2D(val x: Float, val y: Float)
}
