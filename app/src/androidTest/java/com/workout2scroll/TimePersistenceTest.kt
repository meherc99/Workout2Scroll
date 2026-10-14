package com.workout2scroll

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.workout2scroll.utils.InstagramTimeManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TimePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("workout2scroll_prefs", Context.MODE_PRIVATE)

    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun balanceSurvivesManagerRecreation() {
        InstagramTimeManager(context).apply { addTime(3); consumeTime(1_250) }
        val recreated = InstagramTimeManager(context)
        assertEquals(178_750L, recreated.getAvailableMillis())
        assertEquals(3, recreated.getTotalEarnedTime())
        assertEquals(1_250L, recreated.getBalance().usedTodayMs)
    }

    @Test fun oldBalancesMigrateOnce() {
        prefs.edit().putInt("available_time_minutes", 5)
            .putInt("last_reset_date", Calendar.getInstance().get(Calendar.DAY_OF_YEAR)).commit()
        assertEquals(300_000L, InstagramTimeManager(context).getAvailableMillis())
        InstagramTimeManager(context).consumeTime(1_000)
        assertEquals(299_000L, InstagramTimeManager(context).getAvailableMillis())
    }

    @Test fun dailyRolloverPersistsWithoutLosingLifetimeTotal() {
        val manager = InstagramTimeManager(context)
        manager.addTime(5)
        prefs.edit().putString("balance_day", "2000-1-1").commit()
        assertEquals(0L, manager.getAvailableMillis())
        assertEquals(5, manager.getTotalEarnedTime())
        assertEquals(0L, InstagramTimeManager(context).getAvailableMillis())
    }

    @Test fun concurrentManagersDoNotLoseRewards() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..40).map { executor.submit { InstagramTimeManager(context).addTime(1) } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(2_400_000L, InstagramTimeManager(context).getAvailableMillis())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun sameVideoCanOnlyEarnOnceAcrossManagerRecreation() {
        val fingerprint = "a".repeat(64)
        assertTrue(InstagramTimeManager(context).rewardVideoOnce(fingerprint, 4))
        val recreated = InstagramTimeManager(context)
        assertFalse(recreated.rewardVideoOnce(fingerprint, 4))
        assertTrue(recreated.hasRewardedVideo(fingerprint))
        assertEquals(240_000L, recreated.getAvailableMillis())
    }
}