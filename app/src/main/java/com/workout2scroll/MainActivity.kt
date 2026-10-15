package com.workout2scroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.provider.Settings
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.google.android.material.tabs.TabLayout
import com.workout2scroll.databinding.ActivityMainBinding
import com.workout2scroll.ml.PoseDetector
import com.workout2scroll.ml.ExerciseCounter
import com.workout2scroll.ml.ExerciseType
import com.workout2scroll.ml.VideoProcessor
import com.workout2scroll.ml.VideoFingerprint
import com.workout2scroll.utils.InstagramTimeManager
import com.workout2scroll.guard.InstagramGuardService
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "W2S.Activity"
    }

    private var analyzedFrames = 0L

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetector: PoseDetector
    private lateinit var exerciseCounter: ExerciseCounter
    private lateinit var videoExerciseCounter: ExerciseCounter
    private var selectedExercise = ExerciseType.SQUAT
    private lateinit var instagramTimeManager: InstagramTimeManager
    private lateinit var videoProcessor: VideoProcessor

    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraReady = false
    private var videoJob: Job? = null
    private var videoFingerprint: String? = null
    private var refreshJob: Job? = null
    @Volatile private var isWorkoutActive = false
    @Volatile private var isLiveMode = true
    @Volatile private var workoutSession = 0
    private var usingFrontCamera = true

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.i(TAG, "Camera permission result: granted=$isGranted lifecycle=${lifecycle.currentState}")
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.camera_permission_required)
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Camera permission")
                    .setMessage("Allow camera access in app settings, or use Upload Video.")
                    .setPositiveButton("App settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        Log.i(TAG, "Video picker result: selected=${uri != null} scheme=${uri?.scheme}")
        if (uri != null) {
            processSelectedVideo(uri)
        } else {
            Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            // Ensure video upload layout is visible if user cancels
            binding.videoProcessingLayout.visibility = View.GONE
            binding.videoUploadLayout.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: restored=${savedInstanceState != null} sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupUI()
        Log.i(TAG, "Components initialized: poseReady=${poseDetector.isReady} cameraPermission=${allPermissionsGranted()}")
        if (!poseDetector.isReady) {
            Log.e(TAG, "Pose model initialization failed: ${poseDetector.initializationError}")
            binding.tvStatus.text = "Pose model unavailable: ${poseDetector.initializationError}"
            binding.startWorkoutButton.isEnabled = false
            binding.selectVideoButton.isEnabled = false
        }
    }

    private fun initializeComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        poseDetector = PoseDetector(this)
        val savedExercise = getSharedPreferences("exercise_settings", MODE_PRIVATE)
            .getString("selected_exercise", ExerciseType.SQUAT.name)
        selectedExercise = ExerciseType.values().firstOrNull { it.name == savedExercise } ?: ExerciseType.SQUAT
        exerciseCounter = ExerciseCounter { exercise -> onRepDetected(exercise) }
        exerciseCounter.selectExercise(selectedExercise)
        videoExerciseCounter = ExerciseCounter {}
        videoExerciseCounter.selectExercise(selectedExercise)
        instagramTimeManager = InstagramTimeManager(this)
        videoProcessor = VideoProcessor(
            context = this,
            poseDetector = poseDetector,
            repCounter = videoExerciseCounter,
            onProgress = { progress, repetitions ->
                runOnUiThread {
                    binding.processingProgressBar.progress = progress
                    binding.processingStatusText.text = getString(R.string.exercise_processing_format,
                        progress, exerciseCountText(videoExerciseCounter.exercise, repetitions))
                }
            },
            onComplete = { totalRepetitions ->
                runOnUiThread {
                    val exercise = videoExerciseCounter.exercise
                    val earnedMinutes = exercise.earnedMinutes(totalRepetitions)
                    Log.i(TAG, "Video completion received: exercise=$exercise reps=$totalRepetitions minutes=$earnedMinutes")
                    binding.processingStatusText.text = "Processing complete!"
                    binding.squatCountText.text = exerciseCountText(exercise, totalRepetitions)

                    val fingerprint = checkNotNull(videoFingerprint)
                    if (!instagramTimeManager.rewardVideoOnce(fingerprint, earnedMinutes)) {
                        Log.w(TAG, "Video reward rejected: previously rewarded content")
                        binding.tvStatus.text = "This video has already earned its reward"
                        return@runOnUiThread
                    }
                    updateInstagramTime()

                    val result = getString(R.string.exercise_video_complete_format,
                        exerciseCountText(exercise, totalRepetitions), earnedMinutes)
                    Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = result
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.tvStatus.text = "Video failed: $error"
                    binding.processingStatusText.text = "Error processing video"
                    Toast.makeText(this@MainActivity,
                        "Error processing video: $error",
                        Toast.LENGTH_LONG).show()

                    binding.videoProcessingLayout.visibility = View.GONE
                    binding.videoUploadLayout.visibility = View.VISIBLE
                }
            }
        )
    }

    private fun setupUI() {
        updateInstagramTime()
        binding.exerciseToggleGroup.check(if (selectedExercise == ExerciseType.SQUAT)
            R.id.squatsOption else R.id.pushupsOption)
        binding.squatCountText.text = exerciseCountText(selectedExercise, 0)
        binding.exerciseToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isWorkoutActive && videoJob?.isActive != true) {
                val exercise = if (checkedId == R.id.pushupsOption) ExerciseType.PUSHUP else ExerciseType.SQUAT
                if (exercise != selectedExercise) {
                    workoutSession++
                    selectedExercise = exercise
                    exerciseCounter.selectExercise(exercise)
                    videoExerciseCounter.selectExercise(exercise)
                    getSharedPreferences("exercise_settings", MODE_PRIVATE).edit()
                        .putString("selected_exercise", exercise.name).apply()
                    binding.squatCountText.text = exerciseCountText(exercise, 0)
                    binding.tvStatus.text = if (isLiveMode) exerciseReadyText() else getString(R.string.video_ready)
                    binding.poseOverlayView.clearPose()
                }
            }
        }
        binding.cameraPreviewView.previewStreamState.observe(this) { state ->
            Log.i(TAG, "Preview stream state: state=$state liveMode=$isLiveMode cameraReady=$cameraReady size=${binding.cameraPreviewView.width}x${binding.cameraPreviewView.height} shown=${binding.cameraPreviewView.isShown}")
        }

        // Setup tab layout
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> switchToLiveMode()
                    1 -> switchToVideoMode()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Initially show live mode
        switchToLiveMode()

        binding.startWorkoutButton.setOnClickListener { startWorkout() }
        binding.stopWorkoutButton.setOnClickListener { stopWorkout() }

        binding.selectVideoButton.setOnClickListener {
            checkStoragePermissionAndSelectVideo()
        }

        binding.cancelProcessingButton.setOnClickListener {
            cancelVideoProcessing()
        }

        binding.openInstagramButton.setOnClickListener {
            openInstagram()
        }
        binding.guardSettingsButton.setOnClickListener { showGuardSetup() }
    }

    private fun exerciseCountText(exercise: ExerciseType, count: Int): String = getString(
        if (exercise == ExerciseType.SQUAT) R.string.squat_count_format else R.string.pushup_count_format, count)

    private fun exerciseReadyText(): String = getString(
        if (selectedExercise == ExerciseType.SQUAT) R.string.stand_in_front_camera else R.string.pushup_ready)

    private fun setExerciseSelectorEnabled(enabled: Boolean) {
        binding.squatsOption.isEnabled = enabled
        binding.pushupsOption.isEnabled = enabled
    }

    private fun onRepDetected(exercise: ExerciseType) {
        runOnUiThread {
            Log.i(TAG, "Rep reward: session=$workoutSession exercise=$exercise count=${exerciseCounter.getCount()} minutes=${exercise.minutesPerRep}")
            binding.squatCountText.text = exerciseCountText(exercise, exerciseCounter.getCount())
            instagramTimeManager.addTime(exercise.earnedMinutes(1))
            updateInstagramTime()
        }
    }

    private fun switchToLiveMode() {
        Log.i(TAG, "Switch to live mode: videoJobActive=${videoJob?.isActive} cameraPermission=${allPermissionsGranted()}")
        if (videoJob?.isActive == true) cancelVideoProcessing()
        isLiveMode = true
        binding.cameraView.visibility = View.VISIBLE
        binding.cameraPreviewView.visibility = View.VISIBLE
        binding.poseOverlayView.visibility = View.VISIBLE
        binding.startWorkoutButton.visibility = if (isWorkoutActive) View.GONE else View.VISIBLE
        binding.stopWorkoutButton.visibility = if (isWorkoutActive) View.VISIBLE else View.GONE
        binding.videoUploadLayout.visibility = View.GONE
        binding.videoProcessingLayout.visibility = View.GONE
        if (!isWorkoutActive) binding.tvStatus.text = exerciseReadyText()
        logWorkoutControlBounds()

        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun switchToVideoMode() {
        Log.i(TAG, "Switch to video mode: workoutActive=$isWorkoutActive")
        isLiveMode = false
        binding.cameraView.visibility = View.GONE
        binding.cameraPreviewView.visibility = View.GONE
        binding.poseOverlayView.visibility = View.GONE
        binding.startWorkoutButton.visibility = View.GONE
        binding.stopWorkoutButton.visibility = View.GONE
        binding.videoUploadLayout.visibility = View.VISIBLE
        binding.videoProcessingLayout.visibility = View.GONE

        // Stop camera when in video mode
        stopWorkout()
        cameraProvider?.unbindAll()
        cameraReady = false
        binding.tvStatus.text = getString(R.string.video_ready)
    }

    private fun checkStoragePermissionAndSelectVideo() {
        selectVideo()
    }

    private fun selectVideo() {
        Log.i(TAG, "Opening video picker")
        try {
            videoPickerLauncher.launch("video/*")
        } catch (e: Exception) {
            Log.e(TAG, "Could not launch video picker", e)
            Toast.makeText(this, "Error opening video picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processSelectedVideo(videoUri: Uri) {
        if (videoJob?.isActive == true || !poseDetector.isReady) {
            Log.w(TAG, "Video processing skipped: jobActive=${videoJob?.isActive} poseReady=${poseDetector.isReady}")
            return
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Video processing requested: scheme=${videoUri.scheme}")
        // Validate URI first
        try {
            requireNotNull(contentResolver.openInputStream(videoUri)).close()
        } catch (e: Exception) {
            Log.e(TAG, "Selected video could not be opened", e)
            Toast.makeText(this, "Cannot access selected video file. Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        binding.videoUploadLayout.visibility = View.GONE
        binding.videoProcessingLayout.visibility = View.VISIBLE
        binding.processingProgressBar.progress = 0
        binding.processingStatusText.text = "Validating video..."
        binding.tvStatus.text = "Processing video"
        videoExerciseCounter.selectExercise(selectedExercise)
        setExerciseSelectorEnabled(false)
        videoJob = lifecycleScope.launch {
          try {
            videoFingerprint = withContext(Dispatchers.IO) {
                requireNotNull(contentResolver.openInputStream(videoUri)).use {
                    VideoFingerprint.calculate(it) { ensureActive() }
                }
            }
            if (instagramTimeManager.hasRewardedVideo(checkNotNull(videoFingerprint))) {
                Log.w(TAG, "Video rejected: previously rewarded content")
                binding.tvStatus.text = "This video has already earned its reward"
                return@launch
            }
            val validation = withContext(Dispatchers.IO) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    com.workout2scroll.utils.VideoDebugHelper.logVideoInfo(this@MainActivity, videoUri)
                com.workout2scroll.utils.VideoDebugHelper.validateVideoForProcessing(this@MainActivity, videoUri)
            }
                if (!validation.isValid) {
                    Log.w(TAG, "Video validation rejected: durationMs=${validation.duration} size=${validation.width}x${validation.height} mime=${validation.mimeType} issues=${validation.issues}")
                    val issues = validation.issues.joinToString("\n• ", "Issues found:\n• ")
                    Toast.makeText(this@MainActivity, issues, Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = validation.issues.joinToString(". ")
                    return@launch
                }

                // Start processing if validation passed
                binding.videoUploadLayout.visibility = View.GONE
                binding.videoProcessingLayout.visibility = View.VISIBLE
                binding.processingProgressBar.progress = 0
                binding.processingStatusText.text = "Validating video... ${validation.width}x${validation.height}, ${validation.duration/1000}s"

                videoProcessor.processVideo(videoUri)
          } catch (cancelled: CancellationException) {
                            Log.i(TAG, "Video job cancelled after ${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
              throw cancelled
          } catch (error: Exception) {
                            Log.e(TAG, "Video job failed after ${android.os.SystemClock.elapsedRealtime() - startedAt}ms", error)
              binding.tvStatus.text = "Video failed: ${error.message}"
          } finally {
              Log.i(TAG, "Video job ended: elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
              videoFingerprint = null
              binding.videoProcessingLayout.visibility = View.GONE
              binding.videoUploadLayout.visibility = if (isLiveMode) View.GONE else View.VISIBLE
              setExerciseSelectorEnabled(!isWorkoutActive)
            }
        }
    }

    private fun cancelVideoProcessing() {
        Log.i(TAG, "Video cancellation requested: jobActive=${videoJob?.isActive}")
        videoProcessor.cancelProcessing()
        videoJob?.cancel()
        binding.videoProcessingLayout.visibility = View.GONE
        binding.videoUploadLayout.visibility = View.VISIBLE
        binding.tvStatus.text = "Video cancelled. No time awarded."
        Toast.makeText(this, "Video processing cancelled", Toast.LENGTH_SHORT).show()
    }


    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showGuardSetup() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Instagram Guard")
            .setMessage(getString(R.string.guard_description))
            .setPositiveButton("Accessibility settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (error: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Accessibility settings unavailable on this device", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCamera() {
        Log.d(TAG, "startCamera: liveMode=$isLiveMode permission=${allPermissionsGranted()} lifecycle=${lifecycle.currentState}")
        if (!isLiveMode || !allPermissionsGranted() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.w(TAG, "Camera startup deferred: liveMode=$isLiveMode permission=${allPermissionsGranted()} lifecycle=${lifecycle.currentState}")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            if (!isLiveMode || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Log.w(TAG, "Camera provider arrived after startup conditions changed: liveMode=$isLiveMode destroyed=$isDestroyed lifecycle=${lifecycle.currentState}")
                return@addListener
            }
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
                }
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(binding.cameraPreviewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                    .build().also { it.setAnalyzer(cameraExecutor, ImageAnalyzer()) }
                usingFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val cameraSelector = if (usingFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                cameraReady = true
                Log.i(TAG, "Camera bound: front=$usingFrontCamera preview=${binding.cameraPreviewView.width}x${binding.cameraPreviewView.height} rotation=${binding.cameraPreviewView.display?.rotation}")
                if (!isWorkoutActive) binding.tvStatus.text = exerciseReadyText()
            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization or lifecycle binding failed", exc)
                cameraReady = false
                stopWorkout()
                binding.tvStatus.text = "Camera unavailable. Upload a video instead."
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startWorkout() {
        Log.i(TAG, "Start workout requested: poseReady=${poseDetector.isReady} permission=${allPermissionsGranted()} cameraReady=$cameraReady")
        if (!poseDetector.isReady) {
            Log.e(TAG, "Workout rejected: pose model unavailable")
            return
        }
        if (!allPermissionsGranted()) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        if (!cameraReady) {
            startCamera()
            binding.tvStatus.text = "Starting camera..."
            return
        }
        workoutSession++
        isWorkoutActive = true
        analyzedFrames = 0L
        Log.i(TAG, "Workout started: session=$workoutSession")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.startWorkoutButton.visibility = View.GONE
        binding.stopWorkoutButton.visibility = View.VISIBLE
        exerciseCounter.reset()
        setExerciseSelectorEnabled(false)
        binding.squatCountText.text = exerciseCountText(selectedExercise, 0)
        binding.tvStatus.text = getString(R.string.detecting_pose)
    }

    private fun stopWorkout() {
        Log.i(TAG, "Stop workout: wasActive=$isWorkoutActive session=$workoutSession analyzedFrames=$analyzedFrames")
        isWorkoutActive = false
        workoutSession++
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.poseOverlayView.clearPose()
        if (::exerciseCounter.isInitialized) exerciseCounter.resetMovement()
        setExerciseSelectorEnabled(videoJob?.isActive != true)
        binding.startWorkoutButton.text = getString(R.string.start_workout)
        binding.startWorkoutButton.visibility = if (isLiveMode) View.VISIBLE else View.GONE
        binding.stopWorkoutButton.visibility = View.GONE
        binding.tvStatus.text = "Workout stopped"
    }

    private fun updateInstagramTime() {
        val balance = instagramTimeManager.getBalance()
        val seconds = (balance.availableMs + 999L) / 1_000L
        binding.instagramTimeText.text = getString(R.string.balance_format, seconds / 60, seconds % 60)
        binding.openInstagramButton.isEnabled = balance.availableMs > 0
        binding.dailyStatsText.text = getString(R.string.daily_stats_format,
            balance.earnedTodayMs / 60_000L, balance.usedTodayMs / 60_000L)
        binding.guardSettingsButton.text = getString(if (InstagramGuardService.isConnected) R.string.guard_active else R.string.guard_disabled)
    }

    private fun openInstagram() {
        val availableTime = instagramTimeManager.getAvailableTime()
        if (availableTime > 0) {
            if (!InstagramGuardService.isConnected) {
                showGuardSetup()
                return
            }
            val intent = packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (intent != null) {
                try {
                    startActivity(intent)
                } catch (error: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Instagram could not be opened. No time was charged.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Install the Instagram app to use earned time. Browser sessions are not monitored.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.no_instagram_time), Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val session = workoutSession
            try {
                if (isWorkoutActive && isLiveMode) {
                    val inferenceStartedAt = android.os.SystemClock.elapsedRealtime()
                    val poseData = poseDetector.detectPose(imageProxy, usingFrontCamera)
                    analyzedFrames++
                    if (analyzedFrames == 1L || analyzedFrames % 60L == 0L) {
                        Log.d(TAG, "Camera inference: session=$session frame=$analyzedFrames size=${imageProxy.width}x${imageProxy.height} format=${imageProxy.format} rotation=${imageProxy.imageInfo.rotationDegrees} confidence=${poseData.confidence} elapsedMs=${android.os.SystemClock.elapsedRealtime() - inferenceStartedAt}")
                    }
                    runOnUiThread {
                        if (isWorkoutActive && isLiveMode && session == workoutSession && !isDestroyed) {
                            exerciseCounter.analyzePose(poseData)
                            binding.poseOverlayView.updatePose(poseData)
                            binding.tvStatus.text = when {
                                poseData.confidence < 0.3f -> "Full body not detected"
                                selectedExercise == ExerciseType.PUSHUP && !exerciseCounter.isTrackingPushup() ->
                                    getString(R.string.pushup_tracking_lost)
                                exerciseCounter.isCurrentlyLowered() -> getString(
                                    if (selectedExercise == ExerciseType.SQUAT) R.string.squat_lowered else R.string.pushup_lowered)
                                selectedExercise == ExerciseType.PUSHUP -> getString(R.string.pushup_extended)
                                else -> "Standing"
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Live frame processing failed: session=$session size=${imageProxy.width}x${imageProxy.height} format=${imageProxy.format}", error)
                runOnUiThread {
                    if (isWorkoutActive && session == workoutSession) {
                        stopWorkout()
                        binding.tvStatus.text = "Pose detection failed: ${error.message}"
                    }
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: liveMode=$isLiveMode cameraPermission=${allPermissionsGranted()}")
        logWorkoutControlBounds()
        if (isLiveMode && allPermissionsGranted()) startCamera()
        if (isLiveMode && !allPermissionsGranted()) binding.tvStatus.text = getString(R.string.camera_permission_required)
        refreshJob = lifecycleScope.launch {
            while (true) {
                updateInstagramTime()
                delay(1_000)
            }
        }
    }

    override fun onPause() {
        Log.i(TAG, "onPause: workoutActive=$isWorkoutActive videoJobActive=${videoJob?.isActive}")
        refreshJob?.cancel()
        if (isWorkoutActive) stopWorkout()
        super.onPause()
    }

    override fun onStop() {
        Log.i(TAG, "onStop: releasing camera")
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraReady = false
        super.onStop()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: cancelling video and closing pose detector")
        videoJob?.cancel()
        videoProcessor.cancelProcessing()
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.execute { poseDetector.close() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun logWorkoutControlBounds() {
        binding.startWorkoutButton.post {
            if (isDestroyed) return@post
            val visibleBounds = android.graphics.Rect()
            val visible = binding.startWorkoutButton.getGlobalVisibleRect(visibleBounds)
            Log.d(TAG, "Workout control: liveMode=$isLiveMode shown=${binding.startWorkoutButton.isShown} enabled=${binding.startWorkoutButton.isEnabled} size=${binding.startWorkoutButton.width}x${binding.startWorkoutButton.height} visible=$visible bounds=$visibleBounds rootHeight=${binding.root.height}")
        }
    }
}
