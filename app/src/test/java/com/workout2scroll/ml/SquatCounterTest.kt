package com.workout2scroll.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SquatCounterTest {
    private var rewards = 0
    private val counter = SquatCounter { rewards++ }

    private fun pose(bent: Boolean = false, confidence: Float = 1f): PoseData {
        val points = MutableList(17) { Keypoint(0.5f, 0.5f, confidence) }
        for (offset in 0..1) {
            points[11 + offset] = Keypoint(if (bent) 0.2f else 0.5f, if (bent) 0.5f else 0.2f, confidence)
            points[13 + offset] = Keypoint(0.5f, 0.5f, confidence)
            points[15 + offset] = Keypoint(0.5f, 0.9f, confidence)
        }
        return PoseData(points, confidence)
    }

    @Test fun completeRepetitionsRewardExactlyOnce() {
        repeat(3) {
            repeat(5) { counter.analyzePose(pose()) }
            repeat(5) { counter.analyzePose(pose(bent = true)) }
            repeat(5) { counter.analyzePose(pose()) }
        }
        assertEquals(3, counter.getCount())
        assertEquals(3, rewards)
    }

    @Test fun mustObserveStandingBeforeFirstRepetition() {
        counter.analyzePose(pose(bent = true))
        counter.analyzePose(pose())
        assertEquals(0, counter.getCount())
    }

    @Test fun trackingLossDiscardsPartialRepetition() {
        counter.analyzePose(pose())
        counter.analyzePose(pose(bent = true))
        counter.analyzePose(pose(confidence = 0.1f))
        counter.analyzePose(pose())
        assertEquals(0, counter.getCount())
    }

    @Test fun missingAndDegenerateKeypointsNeverReward() {
        counter.analyzePose(pose())
        counter.analyzePose(PoseData(emptyList(), 1f))
        counter.analyzePose(PoseData(List(17) { Keypoint(0f, 0f, 1f) }, 1f))
        counter.analyzePose(pose())
        assertEquals(0, rewards)
    }

    @Test fun resetClearsSessionAndPartialRepetition() {
        counter.analyzePose(pose())
        counter.analyzePose(pose(bent = true))
        counter.reset()
        counter.analyzePose(pose())
        assertEquals(0, counter.getCount())
        assertFalse(counter.isCurrentlySquatting())
    }

    @Test fun requiresBothLegsToStandBeforeRewarding() {
        counter.analyzePose(pose())
        counter.analyzePose(pose(bent = true))
        val oneLegStanding = pose(bent = true).keypoints.toMutableList()
        for (index in listOf(11, 13, 15)) oneLegStanding[index] = pose().keypoints[index]
        counter.analyzePose(PoseData(oneLegStanding, 1f))
        assertEquals(0, rewards)
        counter.analyzePose(pose())
        assertEquals(1, rewards)
    }

    @Test fun invalidCoordinatesDiscardPartialRepetitions() {
        counter.analyzePose(pose())
        counter.analyzePose(pose(bent = true))
        val points = pose().keypoints.toMutableList()
        points[11] = Keypoint(Float.NaN, 0f, 1f)
        counter.analyzePose(PoseData(points, 1f))
        counter.analyzePose(pose())
        assertEquals(0, rewards)
    }
}