package com.workout2scroll.ml

import kotlin.math.*

class SquatCounter(private val onSquatDetected: () -> Unit) : RepCounter {
    
    private var squatCount = 0
    private var isInSquatPosition = false
    private var wasStanding = false
    
    // Thresholds for squat detection
    private val squatAngleThreshold = 120f // degrees
    private val standingAngleThreshold = 160f // degrees
    private val confidenceThreshold = 0.3f
    
    override fun analyzePose(poseData: PoseData) {
        if (!poseData.confidence.isFinite() || poseData.confidence < confidenceThreshold) {
            resetMovement()
            return
        }
        
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
            rightKnee == null || leftAnkle == null || rightAnkle == null) {
            resetMovement()
            return
        }
            
        if (listOf(leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle)
            .any { !it.confidence.isFinite() || it.confidence < confidenceThreshold ||
                !it.x.isFinite() || !it.y.isFinite() }) {
            resetMovement()
            return
        }
        
        // Calculate knee angles for both legs
        val aspectRatio = poseData.imageWidth.toFloat() / poseData.imageHeight
        if (!aspectRatio.isFinite() || aspectRatio <= 0f) {
            resetMovement()
            return
        }
        val leftKneeAngle = calculateKneeAngle(leftHip, leftKnee, leftAnkle, aspectRatio)
        val rightKneeAngle = calculateKneeAngle(rightHip, rightKnee, rightAnkle, aspectRatio)
        
        if (!leftKneeAngle.isFinite() || !rightKneeAngle.isFinite()) {
            resetMovement()
            return
        }
        detectSquatMovement(max(leftKneeAngle, rightKneeAngle), min(leftKneeAngle, rightKneeAngle))
    }
    
    private fun calculateKneeAngle(hip: Keypoint, knee: Keypoint, ankle: Keypoint, aspectRatio: Float): Float {
        // Calculate angle between hip-knee and knee-ankle vectors
        val hipToKnee = Vector2D((hip.x - knee.x) * aspectRatio, hip.y - knee.y)
        val kneeToAnkle = Vector2D((ankle.x - knee.x) * aspectRatio, ankle.y - knee.y)
        
        val dotProduct = hipToKnee.x * kneeToAnkle.x + hipToKnee.y * kneeToAnkle.y
        val magnitude1 = sqrt(hipToKnee.x * hipToKnee.x + hipToKnee.y * hipToKnee.y)
        val magnitude2 = sqrt(kneeToAnkle.x * kneeToAnkle.x + kneeToAnkle.y * kneeToAnkle.y)
        
        if (magnitude1 == 0f || magnitude2 == 0f) return Float.NaN
        
        val cosAngle = dotProduct / (magnitude1 * magnitude2)
        val clampedCosAngle = cosAngle.coerceIn(-1f, 1f)
        
        return Math.toDegrees(acos(clampedCosAngle.toDouble())).toFloat()
    }
    
    private fun detectSquatMovement(squatAngle: Float, standingAngle: Float) {
        when {
            // Person is in squat position (knees bent)
            squatAngle < squatAngleThreshold && !isInSquatPosition && wasStanding -> {
                isInSquatPosition = true
                wasStanding = false
            }
            
            // Person stood up from squat (completed one squat)
            standingAngle > standingAngleThreshold && isInSquatPosition -> {
                isInSquatPosition = false
                wasStanding = true
                squatCount++
                onSquatDetected()
            }
            
            // Person is standing normally
            standingAngle > standingAngleThreshold -> {
                wasStanding = true
                isInSquatPosition = false
            }
        }
    }
    
    override fun getCount(): Int = squatCount
    
    override fun reset() {
        squatCount = 0
        resetMovement()
    }

    override fun resetMovement() {
        isInSquatPosition = false
        wasStanding = false
    }
    
    fun isCurrentlySquatting(): Boolean = isInSquatPosition
    
    private data class Vector2D(val x: Float, val y: Float)
}
