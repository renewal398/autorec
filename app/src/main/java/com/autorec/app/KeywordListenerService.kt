package com.autorec.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class KeywordListenerService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STATUS_UPDATE  = "com.autorec.app.STATUS_UPDATE"
        const val ACTION_RECORDING_DONE = "com.autorec.app.RECORDING_DONE"
        const val EXTRA_MESSAGE         = "message"
        const val EXTRA_IS_RECORDING    = "is_recording"
        const val EXTRA_FILE_PATH       = "file_path"
        const val CHANNEL_ID            = "vive_channel"
        const val NOTIF_ID              = 1
        private const val TAG           = "ViveService"
    }

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var prefs: PreferencesManager
    private lateinit var recorder: AudioRecorderManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private val handler             = Handler(Looper.getMainLooper())
    private var isListeningActive   = false
    private var restartAttempts     = 0
    // Guard: ignore partials for 1.5s right after we acted on a keyword
    // This prevents the same utterance being picked up twice (partial → onResults)
    private var keywordCooldownUntil = 0L

    override fun onCreate() {
        super.onCreate()
        prefs    = PreferencesManager(this)
        recorder = AudioRecorderManager(this)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Autorec::ListenerWakeLock")
        @Suppress("WakelockTimeout")
        wakeLock.acquire()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listening for triggers..."))
        prefs.isServiceRunning = true
        prefs.isCurrentlyRecording = false   // always reset on clean start

        initRecognizer()
        startListening()
        Log.d(TAG, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (recorder.isRecording) {
            recorder.stopRecording()
            prefs.isCurrentlyRecording = false
        }
        recorder.release()
        if (::speechRecognizer.isInitialized) try { speechRecognizer.destroy() } catch (_: Exception) {}
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        prefs.isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Recognizer lifecycle ────────────────────────────────────────────────

    private fun initRecognizer() {
        if (::speechRecognizer.isInitialized) try { speechRecognizer.destroy() } catch (_: Exception) {}
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastStatus("Speech recognition not available", recorder.isRecording); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,   RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,  packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,      10)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,  true)
            // Long silence window so the recognizer doesn't cut off mid-sentence
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,          4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,                   150L)
        }
        try {
            isListeningActive = true
            restartAttempts   = 0
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening error: ${e.message}")
            scheduleRestart(800)
        }
    }

    private fun scheduleRestart(base: Long = 200) {
        isListeningActive = false
        restartAttempts++
        val delay = minOf(base * restartAttempts, 3000L)
        handler.postDelayed({
            if (!isListeningActive) { initRecognizer(); startListening() }
        }, delay)
    }

    // ─── RecognitionListener ─────────────────────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?)  {}
    override fun onBeginningOfSpeech()              {}
    override fun onRmsChanged(rmsdB: Float)         {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech()                    {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onPartialResults(partialResults: Bundle?) {
        // Only act on partials during cooldown if we're recording and need the stop word ASAP
        val isRecording = recorder.isRecording
        if (!isRecording && System.currentTimeMillis() < keywordCooldownUntil) return

        val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!p.isNullOrEmpty()) {
            Log.d(TAG, "partial: ${p[0]}")
            checkKeywords(p, fromPartial = true)
        }
    }

    override fun onResults(results: Bundle?) {
        isListeningActive = false
        val m = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        Log.d(TAG, "onResults: $m")

        val acted = if (!m.isNullOrEmpty() && System.currentTimeMillis() >= keywordCooldownUntil) {
            checkKeywords(m, fromPartial = false)
        } else false

        // Always restart listening immediately after results — this is the key loop
        // UNLESS we just acted (handleStart/Stop handle their own restarts after a delay)
        if (!acted) {
            handler.post { startListening() }
        }
    }

    override fun onError(error: Int) {
        isListeningActive = false
        Log.d(TAG, "onError: $error")
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> 1200L
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> 100L   // silence timeout — restart fast
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> 2000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                broadcastStatus("Microphone permission denied", false); return
            }
            SpeechRecognizer.ERROR_CLIENT                   -> 500L
            else                                            -> 300L
        }
        scheduleRestart(delay)
    }

    // ─── Keyword detection ───────────────────────────────────────────────────

    /**
     * @param fromPartial  If true, only act on STOP (so we stop recording fast).
     *                     START from partials can cause false triggers mid-sentence.
     */
    private fun checkKeywords(matches: List<String>, fromPartial: Boolean): Boolean {
        val startKws = prefs.startWordsList
        val stopKws  = prefs.stopWordsList

        for (match in matches) {
            val text  = match.lowercase().trim()
            val words = text.split("\\s+".toRegex())

            // --- STOP takes absolute priority while recording ---
            if (recorder.isRecording) {
                for (stopKw in stopKws) {
                    if (anyWordMatches(words, stopKw) || phraseContains(text, stopKw)) {
                        Log.d(TAG, "STOP keyword '$stopKw' matched in: '$match'")
                        keywordCooldownUntil = System.currentTimeMillis() + 1500L
                        handleStop()
                        return true
                    }
                }
            }

            // --- START only from final results (or strong partial match) ---
            if (!recorder.isRecording && !fromPartial) {
                for (startKw in startKws) {
                    if (anyWordMatches(words, startKw) || phraseContains(text, startKw)) {
                        Log.d(TAG, "START keyword '$startKw' matched in: '$match'")
                        keywordCooldownUntil = System.currentTimeMillis() + 1500L
                        handleStart()
                        return true
                    }
                }
            }
        }
        return false
    }

    /** Checks every individual token against the keyword with fuzzy matching. */
    private fun anyWordMatches(words: List<String>, keyword: String) =
        words.any { wordMatches(it, keyword) }

    /**
     * Checks if the keyword appears anywhere in a phrase (handles multi-word results
     * where the keyword may be surrounded by filler words).
     */
    private fun phraseContains(phrase: String, keyword: String): Boolean {
        if (phrase.contains(keyword)) return true
        // Sliding window over consecutive word pairs/triples covering the keyword
        val parts = phrase.split("\\s+".toRegex())
        for (i in parts.indices) {
            val joined = parts.drop(i).take(keyword.split(" ").size + 1).joinToString(" ")
            if (levenshtein(joined, keyword) <= maxEdits(keyword)) return true
        }
        return false
    }

    private fun wordMatches(spoken: String, keyword: String): Boolean {
        if (spoken == keyword) return true                                               // exact
        if (spoken.contains(keyword)) return true                                        // substring
        val edits = maxEdits(keyword)
        if (edits > 0 && levenshtein(spoken, keyword) <= edits) return true             // fuzzy
        // Prefix: "star" matches "start", "sto" matches "stop"
        val prefixLen = (keyword.length * 0.75).toInt().coerceAtLeast(3)
        if (spoken.length >= prefixLen && keyword.startsWith(spoken.take(prefixLen))) return true
        return false
    }

    /** Allowed edit distance based on keyword length — shorter words need tighter matching. */
    private fun maxEdits(keyword: String) = when {
        keyword.length <= 3 -> 0
        keyword.length <= 5 -> 1
        else                -> 2
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
                       else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        return dp[a.length][b.length]
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private fun handleStart() {
        Log.d(TAG, "handleStart: Cycling recognizer to start recorder")
        isListeningActive = false
        try { speechRecognizer.cancel() } catch (_: Exception) {}

        val path = recorder.startRecording()
        if (path != null) {
            prefs.isCurrentlyRecording = true
            val msg = "🔴 Recording... say a stop word to stop"
            broadcastStatus(msg, true)
            updateNotification(msg)
        } else {
            broadcastStatus("❌ Could not start recording", false)
        }

        // Restart recognizer after a delay to let the audio session settle
        handler.postDelayed({
            if (prefs.isServiceRunning) {
                initRecognizer()
                startListening()
            }
        }, 1000)
    }

    private fun handleStop() {
        Log.d(TAG, "handleStop: Cycling recognizer to stop recorder")
        isListeningActive = false
        try { speechRecognizer.cancel() } catch (_: Exception) {}

        val savedPath = recorder.stopRecording()
        prefs.isCurrentlyRecording = false
        if (savedPath != null) {
            val name = savedPath.substringAfterLast("/")
            broadcastStatus("✅ Saved: $name  |  Listening...", false)
            updateNotification("👂 Listening for triggers...")
            sendBroadcast(Intent(ACTION_RECORDING_DONE).putExtra(EXTRA_FILE_PATH, savedPath))
        } else {
            broadcastStatus("❌ Failed to save recording", false)
            updateNotification("👂 Listening for triggers...")
        }

        handler.postDelayed({
            if (prefs.isServiceRunning) {
                initRecognizer()
                startListening()
            }
        }, 500)
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Vive Recording Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Vive listens for trigger words"
                setShowBadge(false)
            })
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Autorec")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun broadcastStatus(message: String, isRecording: Boolean) =
        sendBroadcast(Intent(ACTION_STATUS_UPDATE)
            .putExtra(EXTRA_MESSAGE,      message)
            .putExtra(EXTRA_IS_RECORDING, isRecording))
}
