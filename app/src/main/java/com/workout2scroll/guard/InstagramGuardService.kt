package com.workout2scroll.guard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.workout2scroll.utils.InstagramTimeManager

class InstagramGuardService : AccessibilityService() {
    companion object {
        @Volatile var isConnected = false
            private set
    }

    private lateinit var timeManager: InstagramTimeManager
    private val handler = Handler(Looper.getMainLooper())
    private var instagramVisible = false
    private lateinit var session: ForegroundSession
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            settleUsage()
        }
    }
    private var lastBlocked = 0L
    private val ticker = object : Runnable {
        override fun run() {
            settleUsage()
            if (instagramVisible && screenAvailable() && timeManager.shouldBlockInstagram()) blockInstagram()
            handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        timeManager = InstagramTimeManager(this)
        session = ForegroundSession(SystemClock.elapsedRealtime())
        isConnected = true
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isConnected || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString() ?: return
        settleUsage()
        if (foregroundPackage != "com.android.systemui" && !isKeyboardPackage(foregroundPackage)) {
            instagramVisible = foregroundPackage == "com.instagram.android"
        }
        settleUsage()
        if (instagramVisible && screenAvailable() && timeManager.shouldBlockInstagram()) blockInstagram()
    }

    private fun isKeyboardPackage(packageName: String): Boolean {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        return manager.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun screenAvailable(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive &&
            !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun settleUsage() {
        val elapsed = session.update(SystemClock.elapsedRealtime(), instagramVisible, screenAvailable())
        if (elapsed > 0) timeManager.consumeTime(elapsed)
    }

    private fun blockInstagram() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlocked < 1_000L) return
        lastBlocked = now
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            instagramVisible = false
            settleUsage()
            Toast.makeText(this, "Instagram time is up. Complete a workout to earn more.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInterrupt() {
        if (isConnected) settleUsage()
        instagramVisible = false
        if (isConnected) settleUsage()
    }

    override fun onDestroy() {
        if (isConnected) settleUsage()
        instagramVisible = false
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        super.onDestroy()
    }
}