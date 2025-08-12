package com.workout2scroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.workout2scroll.databinding.ActivityMainBinding
import com.workout2scroll.ml.PoseDetector
import com.workout2scroll.ml.SquatCounter
import com.workout2scroll.ml.VideoProcessor
import com.workout2scroll.utils.InstagramTimeManager
import kotlinx.coroutines.launch
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
    private var isWorkoutActive = false
    private var isLiveMode = true
    
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
        }
    }
    
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            selectVideo()
        } else {
            Toast.makeText(this, "Storage permission required to select videos", Toast.LENGTH_LONG).show()
        }
    }
    
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processSelectedVideo(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        initializeComponents()
        setupUI()
        checkPermissionsAndStartCamera()
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
                    binding.processingStatusText.text = "Processing... $progress%"
                }
            },
            onComplete = { totalSquats ->
                runOnUiThread {
                    binding.processingStatusText.text = "Processing complete!"
                    binding.squatCountText.text = getString(R.string.squat_count_format, totalSquats)
                    
                    // Award minutes for video squats
                    instagramTimeManager.addTime(totalSquats)
                    updateInstagramTime()
                    
                    Toast.makeText(this@MainActivity, 
                        "Found $totalSquats squats! Earned $totalSquats minutes!", 
                        Toast.LENGTH_LONG).show()
                    
                    // Hide processing layout after a delay
                    binding.videoUploadLayout.postDelayed({
                        binding.videoProcessingLayout.visibility = View.GONE
                        binding.videoUploadLayout.visibility = View.VISIBLE
                    }, 2000)
                }
            },
            onError = { error ->
                runOnUiThread {
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
        
        binding.openInstagramButton.setOnClickListener {
            openInstagram()
        }
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
        isLiveMode = true
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
        binding.cameraPreviewView.visibility = View.GONE
        binding.poseOverlayView.visibility = View.GONE
        binding.startWorkoutButton.visibility = View.GONE
        binding.videoUploadLayout.visibility = View.VISIBLE
        binding.videoProcessingLayout.visibility = View.GONE
        
        // Stop camera when in video mode
        stopWorkout()
    }
    
    private fun checkStoragePermissionAndSelectVideo() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                    == PackageManager.PERMISSION_GRANTED) {
                    selectVideo()
                } else {
                    requestStoragePermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                    selectVideo()
                } else {
                    requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
    
    private fun selectVideo() {
        videoPickerLauncher.launch("video/*")
    }
    
    private fun processSelectedVideo(videoUri: Uri) {
        binding.videoUploadLayout.visibility = View.GONE
        binding.videoProcessingLayout.visibility = View.VISIBLE
        binding.processingProgressBar.progress = 0
        binding.processingStatusText.text = "Processing video..."
        
        lifecycleScope.launch {
            videoProcessor.processVideo(videoUri)
        }
    }
    
    private fun checkPermissionsAndStartCamera() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        // Check usage stats permission for Instagram monitoring
        if (!instagramTimeManager.hasUsageStatsPermission()) {
            showUsageStatsPermissionDialog()
        }
    }
    
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    
    private fun showUsageStatsPermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(getString(R.string.usage_stats_permission_required))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startCamera() {
        if (!isLiveMode) return
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
                }
            
            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyzer())
                }
            
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun startWorkout() {
        isWorkoutActive = true
        binding.startWorkoutButton.text = getString(R.string.stop_workout)
        squatCounter.reset()
        binding.squatCountText.text = getString(R.string.squat_count_format, 0)
    }
    
    private fun stopWorkout() {
        isWorkoutActive = false
        binding.startWorkoutButton.text = getString(R.string.start_workout)
    }
    
    private fun updateInstagramTime() {
        val availableTime = instagramTimeManager.getAvailableTime()
        binding.instagramTimeText.text = getString(R.string.instagram_time_format, availableTime)
        binding.openInstagramButton.isEnabled = availableTime > 0
    }
    
    private fun openInstagram() {
        val availableTime = instagramTimeManager.getAvailableTime()
        if (availableTime > 0) {
            // Deduct 1 minute and open Instagram
            instagramTimeManager.spendTime(1)
            updateInstagramTime()
            
            // Try to open Instagram app
            val intent = packageManager.getLaunchIntentForPackage("com.instagram.android")
            if (intent != null) {
                startActivity(intent)
            } else {
                // Open Instagram in browser if app not installed
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
                startActivity(browserIntent)
            }
        } else {
            Toast.makeText(this, getString(R.string.no_instagram_time), Toast.LENGTH_SHORT).show()
        }
    }
    
    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (isWorkoutActive && isLiveMode) {
                lifecycleScope.launch {
                    val poses = poseDetector.detectPose(imageProxy)
                    poses?.let { poseData ->
                        squatCounter.analyzePose(poseData)
                        
                        // Update pose overlay
                        runOnUiThread {
                            binding.poseOverlayView.updatePose(poseData)
                        }
                    }
                }
            }
            imageProxy.close()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
