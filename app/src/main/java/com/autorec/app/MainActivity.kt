package com.autorec.app

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autorec.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private var isServiceRunning = false

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(KeywordListenerService.EXTRA_MESSAGE) ?: return
            val isRecording = intent.getBooleanExtra(KeywordListenerService.EXTRA_IS_RECORDING, false)
            updateStatusUI(message, isRecording)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager(this)
        setupUI()
        checkAndRequestPermissions()
    }

    private fun setupUI() {
        updateTriggerWordDisplay()

        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopListenerService()
            } else {
                if (hasAllPermissions()) startListenerService()
                else checkAndRequestPermissions()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    fun updateTriggerWordDisplay() {
        binding.tvStartWord.text = "▶ Start word: \"${prefs.startWord}\""
        binding.tvStopWord.text = "⏹ Stop word:  \"${prefs.stopWord}\""
    }

    private fun startListenerService() {
        ContextCompat.startForegroundService(this, Intent(this, KeywordListenerService::class.java))
        prefs.isServiceRunning = true
        isServiceRunning = true
        updateServiceUI(true)
        updateStatusUI("👂 Listening for \"${prefs.startWord}\"...", false)
    }

    private fun stopListenerService() {
        stopService(Intent(this, KeywordListenerService::class.java))
        prefs.isServiceRunning = false
        isServiceRunning = false
        updateServiceUI(false)
        updateStatusUI("Service stopped. Press Start to begin.", false)
    }

    private fun updateServiceUI(running: Boolean) {
        if (running) {
            binding.btnToggleService.text = "⏹ Stop Service"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            binding.btnToggleService.text = "▶ Start Service"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    private fun updateStatusUI(message: String, isRecording: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = message
            val color = if (isRecording) android.R.color.holo_red_light else android.R.color.white
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, color))
            val indicatorColor = if (isRecording) android.R.color.holo_red_light else android.R.color.transparent
            binding.recordingIndicator.setBackgroundColor(
                ContextCompat.getColor(this, indicatorColor))
        }
    }

    private fun hasAllPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return audio && notif
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "✅ Permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ Microphone permission is required!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(KeywordListenerService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        isServiceRunning = checkServiceRunning()
        updateServiceUI(isServiceRunning)
        updateTriggerWordDisplay()
        if (isServiceRunning) updateStatusUI("👂 Listening for \"${prefs.startWord}\"...", false)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    @Suppress("DEPRECATION")
    private fun checkServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KeywordListenerService::class.java.name }
    }
}