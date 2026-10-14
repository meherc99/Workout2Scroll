package com.workout2scroll.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.*

class InstagramTimeManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "workout2scroll_prefs"
        private const val KEY_AVAILABLE_TIME = "available_time_minutes"
        private const val KEY_LAST_RESET = "last_reset_date"
        private val lock = Any()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addTime(minutes: Int) {
        synchronized(lock) { save(load().credit(minutes)) }
    }

    fun spendTime(minutes: Int): Boolean {
        require(minutes >= 0)
        synchronized(lock) {
            val balance = load()
            if (balance.availableMs < minutes * 60_000L) return false
            save(balance.debit(minutes * 60_000L))
            return true
        }
    }

    fun getAvailableTime(): Int {
        return ((getAvailableMillis() + 59_999L) / 60_000L).toInt()
    }

    fun getAvailableMillis(): Long = getBalance().availableMs

    fun getBalance(): TimeBalance = synchronized(lock) { load() }

    fun consumeTime(durationMs: Long) = synchronized(lock) { save(load().debit(durationMs)) }

    private fun today(): String = Calendar.getInstance().let {
        "${it.get(Calendar.YEAR)}-${it.get(Calendar.MONTH) + 1}-${it.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun load(): TimeBalance {
        val day = today()
        if (!prefs.contains("balance_day")) {
            val legacyMinutes = if (prefs.getInt(KEY_LAST_RESET, -1) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR))
                prefs.getInt(KEY_AVAILABLE_TIME, 0).coerceAtLeast(0) else 0
            save(TimeBalance(day, legacyMinutes * 60_000L,
                lifetimeEarnedMs = prefs.getInt("total_earned_time", 0).coerceAtLeast(0) * 60_000L))
        }
        val stored = TimeBalance(
            prefs.getString("balance_day", day) ?: day,
            prefs.getLong("balance_ms", 0).coerceAtLeast(0),
            prefs.getLong("earned_today_ms", 0).coerceAtLeast(0),
            prefs.getLong("used_today_ms", 0).coerceAtLeast(0),
            prefs.getLong("lifetime_earned_ms", 0).coerceAtLeast(0)
        )
        return stored.forDay(day).also { if (it != stored) save(it) }
    }

    private fun save(balance: TimeBalance, videoFingerprint: String? = null) {
        val editor = prefs.edit()
            .putString("balance_day", balance.day)
            .putLong("balance_ms", balance.availableMs)
            .putLong("earned_today_ms", balance.earnedTodayMs)
            .putLong("used_today_ms", balance.usedTodayMs)
            .putLong("lifetime_earned_ms", balance.lifetimeEarnedMs)
        if (videoFingerprint != null) {
            editor.putStringSet("rewarded_videos", prefs.getStringSet("rewarded_videos", emptySet())!!.toSet() + videoFingerprint)
        }
        editor.apply()
    }

    fun hasRewardedVideo(fingerprint: String): Boolean = synchronized(lock) {
        prefs.getStringSet("rewarded_videos", emptySet())!!.contains(fingerprint)
    }

    fun rewardVideoOnce(fingerprint: String, squats: Int): Boolean = synchronized(lock) {
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        require(squats >= 0)
        if (hasRewardedVideo(fingerprint)) return false
        save(load().credit(squats), fingerprint)
        true
    }


    fun getInstagramUsageToday(): Long = getBalance().usedTodayMs

    fun getInstagramUsageInMinutes(): Long {
        return getInstagramUsageToday() / (1000 * 60) // Convert milliseconds to minutes
    }

    fun shouldBlockInstagram(): Boolean {
        return getAvailableMillis() <= 0
    }

    fun getTotalEarnedTime(): Int {
        // This could track total time earned across all sessions
        return (getBalance().lifetimeEarnedMs / 60_000L).toInt()
    }

    fun addToTotalEarnedTime(minutes: Int) {
        require(minutes >= 0)
        synchronized(lock) {
            val balance = load()
            save(balance.copy(lifetimeEarnedMs = balance.lifetimeEarnedMs + minutes * 60_000L))
        }
    }
}
