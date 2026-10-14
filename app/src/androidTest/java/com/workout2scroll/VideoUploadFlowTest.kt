package com.workout2scroll

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.view.View
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.workout2scroll.utils.InstagramTimeManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoUploadFlowTest {
    private fun selectVideoWhenReady(scenario: ActivityScenario<MainActivity>) {
        val ready = CountDownLatch(1)
        scenario.onActivity { activity ->
            val button = activity.findViewById<View>(R.id.selectVideoButton)
            if (button.isShown && button.width > 0 && !button.isLayoutRequested) {
                ready.countDown()
            } else {
                button.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (button.isShown && button.width > 0 && !button.isLayoutRequested) {
                            button.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            ready.countDown()
                        }
                    }
                })
            }
        }
        assertTrue("Video selection never became ready", ready.await(15, TimeUnit.SECONDS))
        onView(withId(R.id.selectVideoButton)).perform(scrollTo(), click())
    }

    @Test fun pickerResultProcessesWithoutFalseRewardsAndPreventsReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("workout2scroll_prefs", Activity.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val fixture = File(context.cacheDir, "upload-test.mp4")
        instrumentation.context.assets.open("no_person.mp4").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val completed = CountDownLatch(1)
        val replayRejected = CountDownLatch(1)
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_GET_CONTENT)).respondWith(
                ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(fixture))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<TextView>(R.id.tvStatus).addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                            if (text.toString().startsWith("Video complete:")) completed.countDown()
                            if (text.toString().contains("already earned")) replayRejected.countDown()
                        }
                        override fun afterTextChanged(text: Editable?) {}
                    })
                }
                onView(withText("Upload Video")).perform(click())
                selectVideoWhenReady(scenario)
                assertTrue("Video upload never completed", completed.await(40, TimeUnit.SECONDS))
                assertEquals(0L, InstagramTimeManager(context).getAvailableMillis())
                selectVideoWhenReady(scenario)
                assertTrue("Replay was not rejected", replayRejected.await(15, TimeUnit.SECONDS))
            }
        } finally {
            Intents.release()
            preferences.edit().clear().commit()
        }
    }
}