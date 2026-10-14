package com.workout2scroll

import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiveCameraTest {
    @Test fun liveFramesReachMoveNetAndWorkoutStopsCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} android.permission.CAMERA"
        )).use { it.readBytes() }
        val streaming = CountDownLatch(1)
        val inferred = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<PreviewView>(R.id.cameraPreviewView).previewStreamState.observe(activity) {
                    if (it == PreviewView.StreamState.STREAMING) streaming.countDown()
                }
                activity.findViewById<TextView>(R.id.tvStatus).addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                        if (text.toString() in listOf("Full body not detected", "Standing", "Squat position detected")) inferred.countDown()
                    }
                    override fun afterTextChanged(text: Editable?) {}
                })
            }
            assertTrue("Camera did not stream", streaming.await(30, TimeUnit.SECONDS))
            onView(withId(R.id.startWorkoutButton)).perform(scrollTo(), click())
            assertTrue("Live camera frames did not reach inference", inferred.await(30, TimeUnit.SECONDS))
            onView(withId(R.id.startWorkoutButton)).check(matches(withText(R.string.stop_workout)))
            onView(withId(R.id.startWorkoutButton)).perform(click())
            onView(withId(R.id.startWorkoutButton)).check(matches(withText(R.string.start_workout)))
        }
    }
}