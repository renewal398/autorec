package com.autorec.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String = ""

    var isRecording = false
        private set

    fun startRecording(): String? {
        return try {
            val recordingsDir = File(context.getExternalFilesDir(null), "ViveRecordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "REC_$timestamp.mp4"
            currentFilePath = File(recordingsDir, fileName).absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d("AudioRecorder", "Recording started: $currentFilePath")
            currentFilePath

        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            null
        }
    }

    fun stopRecording(): String? {
        return try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            Log.d("AudioRecorder", "Recording stopped: $currentFilePath")
            currentFilePath
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to stop recording: ${e.message}")
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            null
        }
    }

    fun release() {
        if (isRecording) stopRecording()
        mediaRecorder?.release()
        mediaRecorder = null
    }
}