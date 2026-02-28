package com.autorec.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||   // HTC/OnePlus fast boot
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val prefs = PreferencesManager(context)
            // Only auto-start if user had the service running before the phone turned off
            if (prefs.isServiceRunning) {
                Log.d("BootReceiver", "Auto-starting service after boot")
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeywordListenerService::class.java)
                )
            }
        }
    }
}
