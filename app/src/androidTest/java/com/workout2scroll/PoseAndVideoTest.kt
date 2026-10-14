package com.workout2scroll

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.workout2scroll.ml.PoseDetector
import com.workout2scroll.ml.SquatCounter
import com.workout2scroll.ml.VideoProcessor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PoseAndVideoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var detector: PoseDetector

    @Before fun setUp() {
        detector = PoseDetector(context)
        assertTrue(detector.initializationError, detector.isReady)
    }

    @After fun tearDown() { detector.close() }

    @Test fun bundledModelInfersPortraitAndLandscapeFrames() {
        for ((width, height) in listOf(240 to 320, 320 to 240)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLACK)
                val pose = detector.detectPoseFromBitmap(bitmap)
                assertEquals(17, pose.keypoints.size)
                assertEquals(width, pose.imageWidth)
                assertEquals(height, pose.imageHeight)
                assertTrue(pose.keypoints.all { it.x.isFinite() && it.y.isFinite() && it.confidence in 0f..1f })
                var rewards = 0
                val counter = SquatCounter { rewards++ }
                repeat(5) { counter.analyzePose(pose) }
                assertEquals(0, rewards)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun fixture(): Uri {
        val file = File(context.cacheDir, "no_person.mp4")
        instrumentation.context.assets.open("no_person.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    @Test fun decodesFullVideoAndCompletesExactlyOnce() = runBlocking {
        val progressValues = mutableListOf<Int>()
        var completions = 0
        var error: String? = null
        val processor = VideoProcessor(context, detector, SquatCounter {},
            onProgress = { progress, _ -> progressValues.add(progress) },
            onComplete = { count -> assertEquals(0, count); completions++ },
            onError = { error = it })
        processor.processVideo(fixture())
        assertNull(error)
        assertEquals(1, completions)
        assertEquals(100, progressValues.last())
        assertTrue(progressValues.any { it in 40..70 })
        assertFalse(processor.isCurrentlyProcessing())
    }

    @Test fun cancellationNeverCompletesOrReportsFailure() = runBlocking {
        var completions = 0
        var failures = 0
        lateinit var processing: Job
        val processor = VideoProcessor(context, detector, SquatCounter {},
            onProgress = { _, _ -> processing.cancel() },
            onComplete = { completions++ }, onError = { failures++ })
        val uri = fixture()
        processing = launch(Dispatchers.Main, start = CoroutineStart.LAZY) { processor.processVideo(uri) }
        processing.start()
        processing.join()
        assertEquals(0, completions)
        assertEquals(0, failures)
        assertFalse(processor.isCurrentlyProcessing())
    }

    @Test fun invalidVideoReportsErrorWithoutReward() = runBlocking {
        var completions = 0
        var failures = 0
        val processor = VideoProcessor(context, detector, SquatCounter {},
            onProgress = { _, _ -> }, onComplete = { completions++ }, onError = { failures++ })
        processor.processVideo(Uri.fromFile(File(context.cacheDir, "missing-video.mp4")))
        assertEquals(0, completions)
        assertEquals(1, failures)
        assertFalse(processor.isCurrentlyProcessing())
    }
}