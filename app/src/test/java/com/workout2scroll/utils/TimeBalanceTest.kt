package com.workout2scroll.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeBalanceTest {
    @Test fun earnsOneMinutePerSquat() {
        val balance = TimeBalance("2026-10-14").credit(3)
        assertEquals(180_000L, balance.availableMs)
        assertEquals(180_000L, balance.lifetimeEarnedMs)
        assertEquals(180_000L, balance.earnedTodayMs)
    }

    @Test fun chargesActualElapsedTimeWithoutRounding() {
        val balance = TimeBalance("today").credit(1).debit(250).debit(1_250)
        assertEquals(58_500L, balance.availableMs)
        assertEquals(1_500L, balance.usedTodayMs)
    }

    @Test fun neverOverspends() {
        val balance = TimeBalance("today").credit(1).debit(Long.MAX_VALUE)
        assertEquals(0L, balance.availableMs)
        assertEquals(60_000L, balance.usedTodayMs)
        assertEquals(balance, balance.debit(1_000))
    }

    @Test fun newDayResetsDailyFieldsButNotLifetimeRewards() {
        val balance = TimeBalance("2026-12-31").credit(5).debit(1_000).forDay("2027-1-1")
        assertEquals(0L, balance.availableMs)
        assertEquals(0L, balance.usedTodayMs)
        assertEquals(0L, balance.earnedTodayMs)
        assertEquals(300_000L, balance.lifetimeEarnedMs)
        assertEquals(balance, balance.forDay("2027-1-1"))
    }

    @Test fun sameDayDifferentYearStillResets() {
        assertEquals(0L, TimeBalance("2025-10-14").credit(1).forDay("2026-10-14").availableMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeRewards() { TimeBalance("today").credit(-1) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeUsage() { TimeBalance("today").debit(-1) }
}