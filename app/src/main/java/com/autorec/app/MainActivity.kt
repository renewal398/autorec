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

    companion object { private const val REQUEST_PERMISSIONS = 100 }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message     = intent?.getStringExtra(KeywordListenerService.EXTRA_MESSAGE) ?: return
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
            if (isServiceRunning) stopListenerService()
            else if (hasAllPermissions()) startListenerService()
            else checkAndRequestPermissions()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
    }

    fun updateTriggerWordDisplay() {
        binding.tvStartWord.text = "Start word: \"${prefs.startWord}\""
        binding.tvStopWord.text  = "Stop word:  \"${prefs.stopWord}\""
    }

    private fun startListenerService() {
        ContextCompat.startForegroundService(this, Intent(this, KeywordListenerService::class.java))
        prefs.isServiceRunning = true
        isServiceRunning = true
        updateServiceUI(running = true, recording = false)
        updateStatusUI("👂 Listening for \"${prefs.startWord}\"...", false)
    }

    private fun stopListenerService() {
        stopService(Intent(this, KeywordListenerService::class.java))
        prefs.isServiceRunning     = false
        prefs.isCurrentlyRecording = false
        isServiceRunning = false
        updateServiceUI(running = false, recording = false)
        updateStatusUI("Service stopped. Press Start to begin.", false)
    }

    private fun updateServiceUI(running: Boolean, recording: Boolean) {
        binding.btnToggleService.text = if (running) "⏹ Stop Service" else "▶ Start Listening"
        binding.btnToggleService.setBackgroundColor(
            ContextCompat.getColor(this,
                if (running) android.R.color.holo_red_dark else android.R.color.holo_green_dark)
        )
        // Green dot = service alive, animated red = actively recording
        binding.serviceDot.setBackgroundColor(
            when {
                recording -> 0xFFE94560.toInt()
                running   -> 0xFF2ECC71.toInt()
                else      -> 0xFF555555.toInt()
            }
        )
    }

    private fun updateStatusUI(message: String, isRecording: Boolean) {
        runOnUiThread {
            binding.tvStatus.text = message
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this,
                    if (isRecording) android.R.color.holo_red_light else android.R.color.white)
            )
            binding.recordingIndicator.setBackgroundResource(
                if (isRecording) R.drawable.circle_recording else R.drawable.circle_indicator
            )
        }
    }

    private fun hasAllPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        else true
        return audio && notif
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                Toast.makeText(this, "✅ Permissions granted!", Toast.LENGTH_SHORT).show()
            else
                Toast.makeText(this, "❌ Microphone permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register for live updates from the service
        val filter = IntentFilter(KeywordListenerService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)

        // Sync UI to real service state — covers the case where user locked phone
        // while recording started via trigger word, then opens the app later
        isServiceRunning = checkServiceRunning()
        val isRecording  = prefs.isCurrentlyRecording && isServiceRunning

        updateServiceUI(running = isServiceRunning, recording = isRecording)
        updateTriggerWordDisplay()

        when {
            isRecording     -> updateStatusUI("🔴 Recording... say \"${prefs.stopWord}\" to stop", true)
            isServiceRunning -> updateStatusUI("👂 Listening for \"${prefs.startWord}\"...", false)
            else            -> updateStatusUI("Press ▶ Start to begin listening", false)
        }
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
