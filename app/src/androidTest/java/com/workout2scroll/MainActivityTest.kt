package com.workout2scroll

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.workout2scroll.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test fun startsWithoutPermissionsAndCanSwitchModes() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Upload Video")).perform(click())
            onView(withId(R.id.selectVideoButton)).check(matches(isDisplayed()))
            onView(withText("Live Camera")).perform(click())
            onView(withId(R.id.startWorkoutButton)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test fun processingPanelIsNotHiddenByUploadPanel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Upload Video")).perform(click())
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val binding = ActivityMainBinding.bind(content.getChildAt(0))
                binding.videoUploadLayout.visibility = View.GONE
                binding.videoProcessingLayout.visibility = View.VISIBLE
                assertTrue(binding.cancelProcessingButton.isShown)
                assertFalse(binding.videoUploadLayout.isShown)
            }
        }
    }

    @Test fun previewAndPoseOverlayShareBounds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Live Camera")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val preview = activity.findViewById<View>(R.id.cameraPreviewView)
                val overlay = activity.findViewById<View>(R.id.poseOverlayView)
                assertEquals(preview.parent, overlay.parent)
                assertTrue(preview.height > 0)
                assertEquals(preview.width, overlay.width)
                assertEquals(preview.height, overlay.height)
                assertEquals(preview.top, overlay.top)
            }
        }
    }

    @Test fun guardRequiresExplicitConsent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.guardSettingsButton)).perform(click())
            onView(withText(R.string.guard_description)).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
        }
    }
}