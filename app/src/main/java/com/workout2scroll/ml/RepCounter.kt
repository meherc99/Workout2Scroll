package com.workout2scroll.ml

interface RepCounter {
    fun analyzePose(poseData: PoseData)
    fun getCount(): Int
    fun reset()
    fun resetMovement()
}