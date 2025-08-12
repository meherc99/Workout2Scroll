package com.workout2scroll.utils

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import java.util.*

class InstagramTimeManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "workout2scroll_prefs"
        private const val KEY_AVAILABLE_TIME = "available_time_minutes"
        private const val KEY_LAST_RESET = "last_reset_date"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    
    fun addTime(minutes: Int) {
        val currentTime = getAvailableTime()
        val newTime = currentTime + minutes
        prefs.edit()
            .putInt(KEY_AVAILABLE_TIME, newTime)
            .apply()
    }
    
    fun spendTime(minutes: Int): Boolean {
        val currentTime = getAvailableTime()
        if (currentTime >= minutes) {
            val newTime = currentTime - minutes
            prefs.edit()
                .putInt(KEY_AVAILABLE_TIME, newTime)
                .apply()
            return true
        }
        return false
    }
    
    fun getAvailableTime(): Int {
        resetDailyTimeIfNeeded()
        return prefs.getInt(KEY_AVAILABLE_TIME, 0)
    }
    
    private fun resetDailyTimeIfNeeded() {
        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_YEAR)
        val lastReset = prefs.getInt(KEY_LAST_RESET, -1)
        
        if (lastReset != today) {
            // Reset available time at the start of each day
            prefs.edit()
                .putInt(KEY_AVAILABLE_TIME, 0)
                .putInt(KEY_LAST_RESET, today)
                .apply()
        }
    }
    
    fun hasUsageStatsPermission(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
    
    fun getInstagramUsageToday(): Long {
        if (!hasUsageStatsPermission() || usageStatsManager == null) {
            return 0L
        }
        
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        
        val instagramStats = usageStats.find { it.packageName == INSTAGRAM_PACKAGE }
        return instagramStats?.totalTimeInForeground ?: 0L
    }
    
    fun getInstagramUsageInMinutes(): Long {
        return getInstagramUsageToday() / (1000 * 60) // Convert milliseconds to minutes
    }
    
    fun shouldBlockInstagram(): Boolean {
        return getAvailableTime() <= 0
    }
    
    fun getTotalEarnedTime(): Int {
        // This could track total time earned across all sessions
        return prefs.getInt("total_earned_time", 0)
    }
    
    fun addToTotalEarnedTime(minutes: Int) {
        val currentTotal = getTotalEarnedTime()
        prefs.edit()
            .putInt("total_earned_time", currentTotal + minutes)
            .apply()
    }
}
