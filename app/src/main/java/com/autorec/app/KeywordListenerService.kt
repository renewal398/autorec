package com.autorec.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class KeywordListenerService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STATUS_UPDATE = "com.autorec.app.STATUS_UPDATE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val CHANNEL_ID = "vive_channel"
        const val NOTIF_ID = 1
        private const val TAG = "ViveService"
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var prefs: PreferencesManager
    private lateinit var recorder: AudioRecorderManager
    private val handler = Handler(Looper.getMainLooper())

    private var isListeningActive = false
    private var restartAttempts = 0
    private val maxRestartAttempts = 5

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        recorder = AudioRecorderManager(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listening for \"${prefs.startWord}\"..."))

        initSpeechRecognizer()
        startListening()
    }

    private fun initSpeechRecognizer() {
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastStatus("❌ Speech recognition not available", false)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            isListeningActive = true
            speechRecognizer.startListening(intent)
            restartAttempts = 0
        } catch (e: Exception) {
            Log.e(TAG, "Error starting listening: ${e.message}")
            scheduleRestart(1000)
        }
    }

    private fun scheduleRestart(delayMs: Long = 500) {
        isListeningActive = false
        if (restartAttempts < maxRestartAttempts) {
            restartAttempts++
            handler.postDelayed({
                if (!isListeningActive) {
                    initSpeechRecognizer()
                    startListening()
                }
            }, delayMs)
        } else {
            restartAttempts = 0
            handler.postDelayed({
                initSpeechRecognizer()
                startListening()
            }, 3000)
        }
    }

    override fun onResults(results: Bundle?) {
        isListeningActive = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) processSpokenText(matches)
        handler.postDelayed({ startListening() }, 300)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!partial.isNullOrEmpty()) processSpokenText(partial)
    }

    private fun processSpokenText(matches: List<String>) {
        val startWord = prefs.startWord.lowercase()
        val stopWord = prefs.stopWord.lowercase()

        for (match in matches) {
            val spoken = match.lowercase()
            if (spoken.contains(startWord) && !recorder.isRecording) {
                val filePath = recorder.startRecording()
                if (filePath != null) {
                    broadcastStatus("🔴 Recording... Say \"${prefs.stopWord}\" to stop", true)
                    updateNotification("🔴 Recording... Say \"${prefs.stopWord}\" to stop")
                } else {
                    broadcastStatus("❌ Failed to start recording", false)
                }
                return
            }
            if (spoken.contains(stopWord) && recorder.isRecording) {
                val savedPath = recorder.stopRecording()
                if (savedPath != null) {
                    val fileName = savedPath.substringAfterLast("/")
                    broadcastStatus("✅ Saved: $fileName", false)
                    updateNotification("Listening for \"${prefs.startWord}\"...")
                } else {
                    broadcastStatus("❌ Failed to stop recording", false)
                }
                return
            }
        }
    }

    override fun onError(error: Int) {
        isListeningActive = false
        val delayMs = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                broadcastStatus("❌ Microphone permission denied", false)
                return
            }
            else -> 500L
        }
        scheduleRestart(delayMs)
    }

    private fun broadcastStatus(message: String, isRecording: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_IS_RECORDING, isRecording)
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Vive Recording Service",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when Vive is actively listening"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Autorec")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (recorder.isRecording) recorder.stopRecording()
        recorder.release()
        speechRecognizer.destroy()
        prefs.isServiceRunning = false
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}