package com.autorec.app

import android.content.Context

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("vive_prefs", Context.MODE_PRIVATE)

    var startWord: String
        get() = prefs.getString("start_word", "start") ?: "start"
        set(value) = prefs.edit().putString("start_word", value.trim().lowercase()).apply()

    var stopWord: String
        get() = prefs.getString("stop_word", "stop") ?: "stop"
        set(value) = prefs.edit().putString("stop_word", value.trim().lowercase()).apply()

    var isServiceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(value) = prefs.edit().putBoolean("service_running", value).apply()
}