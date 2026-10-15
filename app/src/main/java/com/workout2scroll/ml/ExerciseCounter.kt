package com.workout2scroll.ml

enum class ExerciseType(val minutesPerRep: Int) {
    SQUAT(1),
    PUSHUP(2);

    fun earnedMinutes(repetitions: Int): Int {
        require(repetitions >= 0)
        return Math.multiplyExact(repetitions, minutesPerRep)
    }
}

class ExerciseCounter(private val onRepDetected: (ExerciseType) -> Unit) : RepCounter {
    var exercise = ExerciseType.SQUAT
        private set
    private val squats = SquatCounter { onRepDetected(ExerciseType.SQUAT) }
    private val pushups = PushupCounter { onRepDetected(ExerciseType.PUSHUP) }
    private val activeCounter: RepCounter
        get() = if (exercise == ExerciseType.SQUAT) squats else pushups

    fun selectExercise(type: ExerciseType) {
        exercise = type
        squats.reset()
        pushups.reset()
    }

    fun isCurrentlyLowered(): Boolean = if (exercise == ExerciseType.SQUAT)
        squats.isCurrentlySquatting() else pushups.isCurrentlyLowered()

    fun isTrackingPushup(): Boolean = pushups.isTracking

    override fun analyzePose(poseData: PoseData) = activeCounter.analyzePose(poseData)
    override fun getCount(): Int = activeCounter.getCount()
    override fun reset() = activeCounter.reset()
    override fun resetMovement() = activeCounter.resetMovement()
}