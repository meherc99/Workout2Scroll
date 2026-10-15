package com.workout2scroll.ml

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

class PushupCounter(private val onPushupDetected: () -> Unit) : RepCounter {
    private var count = 0
    private var wasExtended = false
    private var lowered = false
    private var trackedSide: Int? = null
    private var extendedFrames = 0
    private var loweredFrames = 0
    var isTracking = false
        private set

    override fun analyzePose(poseData: PoseData) {
        if (!poseData.confidence.isFinite() || poseData.confidence < 0.3f ||
            poseData.imageWidth <= 0 || poseData.imageHeight <= 0) {
            resetMovement()
            return
        }
        val aspect = poseData.imageWidth.toFloat() / poseData.imageHeight
        val candidates = (0..1).mapNotNull { side ->
            val points = listOf(5, 7, 9, 11, 15).map { index ->
                poseData.keypoints.getOrNull(index + side)
            }
            if (points.any { point -> point == null || !point.confidence.isFinite() ||
                    point.confidence < 0.3f || !point.x.isFinite() || !point.y.isFinite() }) {
                null
            } else {
                side to points.filterNotNull()
            }
        }
        val candidate = candidates.firstOrNull { it.first == trackedSide }
            ?: candidates.maxByOrNull { (_, points) -> points.minOf { it.confidence } }
        if (candidate == null) {
            resetMovement()
            return
        }
        if (trackedSide != null && trackedSide != candidate.first) resetMovement()
        trackedSide = candidate.first
        val (shoulder, elbow, wrist, hip, ankle) = candidate.second
        val torsoHorizontal = abs(shoulder.x - hip.x) * aspect > abs(shoulder.y - hip.y) * 1.2f
        val bodyHorizontal = abs(shoulder.x - ankle.x) * aspect > abs(shoulder.y - ankle.y) * 1.2f
        val bodyLength = hypot((shoulder.x - ankle.x) * aspect, shoulder.y - ankle.y)
        val bodyAngle = angle(shoulder, hip, ankle, aspect)
        val elbowAngle = angle(shoulder, elbow, wrist, aspect)
        if (!torsoHorizontal || !bodyHorizontal || bodyLength < 0.2f ||
            wrist.y <= shoulder.y + 0.02f || !bodyAngle.isFinite() || bodyAngle < 150f ||
            !elbowAngle.isFinite()) {
            resetMovement()
            return
        }
        isTracking = true
        extendedFrames = if (elbowAngle > 155f) extendedFrames + 1 else 0
        loweredFrames = if (elbowAngle < 105f) loweredFrames + 1 else 0
        when {
            extendedFrames >= 2 && lowered -> {
                count++
                lowered = false
                wasExtended = true
                onPushupDetected()
            }
            loweredFrames >= 2 && wasExtended -> {
                lowered = true
                wasExtended = false
            }
            extendedFrames >= 2 -> wasExtended = true
        }
    }

    private fun angle(first: Keypoint, joint: Keypoint, last: Keypoint, aspect: Float): Float {
        val firstHorizontal = (first.x - joint.x) * aspect
        val firstVertical = first.y - joint.y
        val lastHorizontal = (last.x - joint.x) * aspect
        val lastVertical = last.y - joint.y
        val magnitude = hypot(firstHorizontal, firstVertical) * hypot(lastHorizontal, lastVertical)
        if (magnitude <= 0f) return Float.NaN
        val cosine = ((firstHorizontal * lastHorizontal + firstVertical * lastVertical) / magnitude)
            .coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }

    fun isCurrentlyLowered(): Boolean = lowered

    override fun getCount(): Int = count

    override fun reset() {
        count = 0
        resetMovement()
    }

    override fun resetMovement() {
        wasExtended = false
        lowered = false
        trackedSide = null
        extendedFrames = 0
        loweredFrames = 0
        isTracking = false
    }
}