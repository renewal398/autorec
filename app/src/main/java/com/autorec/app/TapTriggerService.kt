package com.autorec.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TapTriggerService : AccessibilityService() {

    private var tapCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tapResetRunnable = Runnable {
        Log.d(TAG, "Taps reset. Total: $tapCount")
        tapCount = 0
    }

    companion object {
        private const val TAG = "TapTriggerService"
        private const val TAP_TIMEOUT = 10000L // 10 seconds
        private const val START_TAP_COUNT = 12
        private const val STOP_TAP_COUNT = 13
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            tapCount++
            Log.d(TAG, "Tap detected. Current count: $tapCount")

            handler.removeCallbacks(tapResetRunnable)
            handler.postDelayed(tapResetRunnable, TAP_TIMEOUT)

            when (tapCount) {
                START_TAP_COUNT -> {
                    Log.d(TAG, "Start trigger reached!")
                    sendIntent(KeywordListenerService.ACTION_START_RECORDING)
                }
                STOP_TAP_COUNT -> {
                    Log.d(TAG, "Stop trigger reached!")
                    sendIntent(KeywordListenerService.ACTION_STOP_RECORDING)
                    tapCount = 0
                    handler.removeCallbacks(tapResetRunnable)
                }
            }
        }
    }

    private fun sendIntent(action: String) {
        val intent = Intent(this, KeywordListenerService::class.java).apply {
            this.action = action
        }
        startForegroundService(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
    }
}
