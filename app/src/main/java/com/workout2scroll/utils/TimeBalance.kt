package com.workout2scroll.utils

data class TimeBalance(
    val day: String,
    val availableMs: Long = 0,
    val earnedTodayMs: Long = 0,
    val usedTodayMs: Long = 0,
    val lifetimeEarnedMs: Long = 0
) {
    fun forDay(today: String): TimeBalance =
        if (day == today) this else TimeBalance(today, lifetimeEarnedMs = lifetimeEarnedMs)

    fun credit(minutes: Int): TimeBalance {
        require(minutes >= 0) { "Reward cannot be negative" }
        val amount = minutes * 60_000L
        return copy(availableMs = availableMs + amount, earnedTodayMs = earnedTodayMs + amount,
            lifetimeEarnedMs = lifetimeEarnedMs + amount)
    }

    fun debit(durationMs: Long): TimeBalance {
        require(durationMs >= 0) { "Usage cannot be negative" }
        val spent = minOf(durationMs, availableMs)
        return copy(availableMs = availableMs - spent, usedTodayMs = usedTodayMs + spent)
    }
}