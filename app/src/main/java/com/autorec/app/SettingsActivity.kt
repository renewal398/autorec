package com.autorec.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autorec.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager(this)

        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        binding.etStartWord.setText(prefs.startWord)
        binding.etStopWord.setText(prefs.stopWord)

        binding.btnSave.setOnClickListener {
            val newStart = binding.etStartWord.text.toString().trim()
            val newStop = binding.etStopWord.text.toString().trim()
            when {
                newStart.isEmpty() -> binding.etStartWord.error = "Cannot be empty"
                newStop.isEmpty() -> binding.etStopWord.error = "Cannot be empty"
                newStart.equals(newStop, ignoreCase = true) ->
                    Toast.makeText(this, "⚠️ Start and stop words must be different!", Toast.LENGTH_LONG).show()
                newStart.contains(" ") || newStop.contains(" ") ->
                    Toast.makeText(this, "⚠️ Use single words only!", Toast.LENGTH_LONG).show()
                else -> {
                    prefs.startWord = newStart
                    prefs.stopWord = newStop
                    Toast.makeText(this, "✅ Saved! Restart the service to apply.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}