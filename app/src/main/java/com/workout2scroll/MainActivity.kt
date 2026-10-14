package com.workout2scroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.provider.Settings
import android.view.View
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
import com.workout2scroll.ml.SquatCounter
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseDetector: PoseDetector
    private lateinit var squatCounter: SquatCounter
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeComponents()
        setupUI()
        if (!poseDetector.isReady) {
            binding.tvStatus.text = "Pose model unavailable: ${poseDetector.initializationError}"
            binding.startWorkoutButton.isEnabled = false
            binding.selectVideoButton.isEnabled = false
        }
    }

    private fun initializeComponents() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        poseDetector = PoseDetector(this)
        squatCounter = SquatCounter {
            onSquatDetected()
        }
        instagramTimeManager = InstagramTimeManager(this)
        videoProcessor = VideoProcessor(
            context = this,
            poseDetector = poseDetector,
            squatCounter = SquatCounter { /* video squat callback */ },
            onProgress = { progress, squatCount ->
                runOnUiThread {
                    binding.processingProgressBar.progress = progress
                    binding.processingStatusText.text = "Processing: $progress% | $squatCount squats"
                }
            },
            onComplete = { totalSquats ->
                runOnUiThread {
                    binding.processingStatusText.text = "Processing complete!"
                    binding.squatCountText.text = getString(R.string.squat_count_format, totalSquats)

                    // Award minutes for video squats
                    val fingerprint = checkNotNull(videoFingerprint)
                    if (!instagramTimeManager.rewardVideoOnce(fingerprint, totalSquats)) {
                        binding.tvStatus.text = "This video has already earned its reward"
                        return@runOnUiThread
                    }
                    updateInstagramTime()

                    Toast.makeText(this@MainActivity,
                        "Found $totalSquats squats! Earned $totalSquats minutes!",
                        Toast.LENGTH_LONG).show()

                    binding.tvStatus.text = "Video complete: $totalSquats squats, $totalSquats minutes earned"
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

        binding.startWorkoutButton.setOnClickListener {
            if (isWorkoutActive) {
                stopWorkout()
            } else {
                startWorkout()
            }
        }

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

    private fun onSquatDetected() {
        runOnUiThread {
            binding.squatCountText.text = getString(R.string.squat_count_format, squatCounter.getCount())
            // Award 1 minute per squat
            instagramTimeManager.addTime(1)
            updateInstagramTime()
        }
    }

    private fun switchToLiveMode() {
        if (videoJob?.isActive == true) cancelVideoProcessing()
        isLiveMode = true
        binding.cameraView.visibility = View.VISIBLE
        binding.cameraPreviewView.visibility = View.VISIBLE
        binding.poseOverlayView.visibility = View.VISIBLE
        binding.startWorkoutButton.visibility = View.VISIBLE
        binding.videoUploadLayout.visibility = View.GONE
        binding.videoProcessingLayout.visibility = View.GONE

        if (allPermissionsGranted()) {
            startCamera()
        }
    }

    private fun switchToVideoMode() {
        isLiveMode = false
        binding.cameraView.visibility = View.GONE
        binding.cameraPreviewView.visibility = View.GONE
        binding.poseOverlayView.visibility = View.GONE
        binding.startWorkoutButton.visibility = View.GONE
        binding.videoUploadLayout.visibility = View.VISIBLE
        binding.videoProcessingLayout.visibility = View.GONE

        // Stop camera when in video mode
        stopWorkout()
        cameraProvider?.unbindAll()
        cameraReady = false
        binding.tvStatus.text = "Ready to select a video"
    }

    private fun checkStoragePermissionAndSelectVideo() {
        selectVideo()
    }

    private fun selectVideo() {
        try {
            videoPickerLauncher.launch("video/*")
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening video picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processSelectedVideo(videoUri: Uri) {
        if (videoJob?.isActive == true || !poseDetector.isReady) return
        // Validate URI first
        try {
            requireNotNull(contentResolver.openInputStream(videoUri)).close()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot access selected video file. Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        binding.videoUploadLayout.visibility = View.GONE
        binding.videoProcessingLayout.visibility = View.VISIBLE
        binding.processingProgressBar.progress = 0
        binding.processingStatusText.text = "Validating video..."
        binding.tvStatus.text = "Processing video"
        videoJob = lifecycleScope.launch {
          try {
            videoFingerprint = withContext(Dispatchers.IO) {
                requireNotNull(contentResolver.openInputStream(videoUri)).use {
                    VideoFingerprint.calculate(it) { ensureActive() }
                }
            }
            if (instagramTimeManager.hasRewardedVideo(checkNotNull(videoFingerprint))) {
                binding.tvStatus.text = "This video has already earned its reward"
                return@launch
            }
            val validation = withContext(Dispatchers.IO) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    com.workout2scroll.utils.VideoDebugHelper.logVideoInfo(this@MainActivity, videoUri)
                com.workout2scroll.utils.VideoDebugHelper.validateVideoForProcessing(this@MainActivity, videoUri)
            }
                if (!validation.isValid) {
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
              throw cancelled
          } catch (error: Exception) {
              binding.tvStatus.text = "Video failed: ${error.message}"
          } finally {
              videoFingerprint = null
              binding.videoProcessingLayout.visibility = View.GONE
              binding.videoUploadLayout.visibility = if (isLiveMode) View.GONE else View.VISIBLE
            }
        }
    }

    private fun cancelVideoProcessing() {
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
        if (!isLiveMode || !allPermissionsGranted() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            if (!isLiveMode || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
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
                if (!isWorkoutActive) binding.tvStatus.text = getString(R.string.stand_in_front_camera)
            } catch (exc: Exception) {
                cameraReady = false
                stopWorkout()
                binding.tvStatus.text = "Camera unavailable. Upload a video instead."
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startWorkout() {
        if (!poseDetector.isReady) return
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.startWorkoutButton.text = getString(R.string.stop_workout)
        squatCounter.reset()
        binding.squatCountText.text = getString(R.string.squat_count_format, 0)
        binding.tvStatus.text = getString(R.string.detecting_pose)
    }

    private fun stopWorkout() {
        isWorkoutActive = false
        workoutSession++
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.poseOverlayView.clearPose()
        if (::squatCounter.isInitialized) squatCounter.resetMovement()
        binding.startWorkoutButton.text = getString(R.string.start_workout)
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
                    val poseData = poseDetector.detectPose(imageProxy, usingFrontCamera)
                    runOnUiThread {
                        if (isWorkoutActive && isLiveMode && session == workoutSession && !isDestroyed) {
                            squatCounter.analyzePose(poseData)
                            binding.poseOverlayView.updatePose(poseData)
                            binding.tvStatus.text = when {
                                poseData.confidence < 0.3f -> "Full body not detected"
                                squatCounter.isCurrentlySquatting() -> "Squat position detected"
                                else -> "Standing"
                            }
                        }
                    }
                }
            } catch (error: Exception) {
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
        refreshJob?.cancel()
        if (isWorkoutActive) stopWorkout()
        super.onPause()
    }

    override fun onStop() {
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraReady = false
        super.onStop()
    }

    override fun onDestroy() {
        videoJob?.cancel()
        videoProcessor.cancelProcessing()
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraExecutor.execute { poseDetector.close() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
