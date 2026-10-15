package com.workout2scroll.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PushupCounterTest {
    private var rewards = 0
    private val counter = PushupCounter { rewards++ }

    private fun pose(lowered: Boolean = false, confidence: Float = 1f, aspect: Int = 1): PoseData {
        val points = MutableList(17) { Keypoint(0f, 0f, 0f) }
        for (side in 0..1) {
            points[5 + side] = Keypoint(0.2f / aspect, if (lowered) 0.5f else 0.3f, confidence)
            points[7 + side] = Keypoint((if (lowered) 0.35f else 0.2f) / aspect, 0.5f, confidence)
            points[9 + side] = Keypoint(0.2f / aspect, 0.7f, confidence)
            points[11 + side] = Keypoint(0.5f / aspect, if (lowered) 0.5f else 0.3f, confidence)
            points[15 + side] = Keypoint(0.9f / aspect, if (lowered) 0.5f else 0.3f, confidence)
        }
        return PoseData(points, confidence, aspect * 400, 400)
    }

    private fun frames(pose: PoseData) = repeat(3) { counter.analyzePose(pose) }

    @Test fun completeRepetitionsRewardExactlyOnce() {
        repeat(3) {
            frames(pose())
            frames(pose(lowered = true))
            frames(pose())
        }
        assertEquals(3, counter.getCount())
        assertEquals(3, rewards)
    }

    @Test fun mustObserveExtendedArmsBeforeFirstRepetition() {
        frames(pose(lowered = true))
        frames(pose())
        assertEquals(0, rewards)
    }

    @Test fun trackingLossDiscardsPartialRepetition() {
        frames(pose())
        frames(pose(lowered = true))
        frames(pose(confidence = 0.1f))
        frames(pose())
        assertEquals(0, rewards)
    }

    @Test fun uprightArmBendsNeverCount() {
        for (lowered in listOf(false, true, false)) {
            val points = pose(lowered).keypoints.map { Keypoint(it.y, it.x, it.confidence) }
            frames(PoseData(points, 1f))
        }
        assertEquals(0, rewards)
        assertFalse(counter.isTracking)
    }

    @Test fun oneVisibleSideCanCompletePushup() {
        for (lowered in listOf(false, true, false)) {
            val points = pose(lowered).keypoints.toMutableList()
            for (index in listOf(6, 8, 10, 12, 16)) points[index] = Keypoint(0f, 0f, 0f)
            frames(PoseData(points, 1f))
        }
        assertEquals(1, rewards)
    }

    @Test fun singleFrameJitterNeverCounts() {
        frames(pose())
        counter.analyzePose(pose(lowered = true))
        frames(pose())
        assertEquals(0, rewards)
    }

    @Test fun losingPlankPostureDiscardsPartialRepetition() {
        frames(pose())
        frames(pose(lowered = true))
        val points = pose(lowered = true).keypoints.toMutableList()
        for (side in 0..1) points[11 + side] = Keypoint(0.5f, 0.1f, 1f)
        frames(PoseData(points, 1f))
        frames(pose())
        assertEquals(0, rewards)
        frames(pose(lowered = true))
        frames(pose())
        assertEquals(1, rewards)
    }

    @Test fun changingVisibleSideDiscardsPartialRepetition() {
        frames(pose())
        frames(pose(lowered = true))
        val points = pose().keypoints.toMutableList()
        for (index in listOf(5, 7, 9, 11, 15)) points[index] = Keypoint(0f, 0f, 0f)
        frames(PoseData(points, 1f))
        assertEquals(0, rewards)
        frames(pose(lowered = true))
        frames(pose())
        assertEquals(1, rewards)
    }

    @Test fun aspectRatioDoesNotChangeCount() {
        frames(pose(aspect = 2))
        frames(pose(lowered = true, aspect = 2))
        frames(pose(aspect = 2))
        assertEquals(1, rewards)
    }

    @Test fun invalidAndDegeneratePosesDiscardPartialRep() {
        frames(pose())
        frames(pose(lowered = true))
        counter.analyzePose(PoseData(emptyList(), 1f))
        counter.analyzePose(PoseData(List(17) { Keypoint(Float.NaN, 0f, 1f) }, 1f))
        counter.analyzePose(PoseData(List(17) { Keypoint(0.5f, 0.5f, 1f) }, 1f))
        frames(pose())
        assertEquals(0, rewards)
    }

    @Test fun resetClearsCountAndMovement() {
        frames(pose())
        frames(pose(lowered = true))
        frames(pose())
        counter.reset()
        assertEquals(0, counter.getCount())
        assertFalse(counter.isCurrentlyLowered())
    }

    @Test fun exerciseSelectionResetsRepsAndUsesTwoMinuteRewards() {
        var minutes = 0
        val exercises = ExerciseCounter { minutes += it.earnedMinutes(1) }
        exercises.selectExercise(ExerciseType.PUSHUP)
        for (lowered in listOf(false, true, false)) {
            repeat(3) { exercises.analyzePose(pose(lowered)) }
        }
        assertEquals(1, exercises.getCount())
        assertEquals(2, minutes)
        assertEquals(6, ExerciseType.PUSHUP.earnedMinutes(3))
        assertEquals(3, ExerciseType.SQUAT.earnedMinutes(3))
        exercises.selectExercise(ExerciseType.SQUAT)
        assertEquals(0, exercises.getCount())
    }
}