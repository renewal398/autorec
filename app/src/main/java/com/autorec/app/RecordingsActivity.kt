package com.autorec.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autorec.app.databinding.ActivityRecordingsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ─── Data model ───────────────────────────────────────────────────────────────

data class Recording(val file: File) {
    val name: String          get() = file.nameWithoutExtension
    val path: String          get() = file.absolutePath
    val sizeKb: Long          get() = file.length() / 1024
    val dateFormatted: String get() =
        SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: RecordingAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var playingPath: String?      = null
    private val handler                   = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable?   = null

    /** Receives ACTION_RECORDING_DONE → refresh list in real time */
    private val newRecordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadRecordings()
            // Also flash the banner briefly if a recording just finished
            binding.bannerRecording.visibility = View.GONE
        }
    }

    /** Receives STATUS_UPDATE → show/hide the "recording" banner */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRec = intent?.getBooleanExtra(KeywordListenerService.EXTRA_IS_RECORDING, false) ?: false
            binding.bannerRecording.visibility = if (isRec) View.VISIBLE else View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager(this)

        supportActionBar?.apply { title = "Recordings"; setDisplayHomeAsUpEnabled(true) }

        adapter = RecordingAdapter(
            onPlay   = { rec -> togglePlay(rec) },
            onDelete = { rec -> confirmDelete(rec) }
        )
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter       = adapter

        // Set initial banner state
        binding.bannerRecording.visibility =
            if (prefs.isCurrentlyRecording) View.VISIBLE else View.GONE

        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        val filter1 = IntentFilter(KeywordListenerService.ACTION_RECORDING_DONE)
        val filter2 = IntentFilter(KeywordListenerService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newRecordingReceiver, filter1, RECEIVER_NOT_EXPORTED)
            registerReceiver(statusReceiver,       filter2, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(newRecordingReceiver, filter1)
            @Suppress("DEPRECATION") registerReceiver(statusReceiver,       filter2)
        }
        loadRecordings()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(newRecordingReceiver)
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    // ─── Data ─────────────────────────────────────────────────────────────────

    private fun loadRecordings() {
        val dir = File(getExternalFilesDir(null), "ViveRecordings")
        val files = if (dir.exists())
            dir.listFiles { f -> f.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { Recording(it) }
                ?: emptyList()
        else emptyList()

        adapter.setData(files)
        binding.tvEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.tvCount.text = "${files.size} recording${if (files.size != 1) "s" else ""}"
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    private fun togglePlay(rec: Recording) {
        when {
            // Tap again on currently playing → pause/resume
            playingPath == rec.path && mediaPlayer?.isPlaying == true -> {
                mediaPlayer?.pause()
                adapter.setPlaying(rec.path, false)
                stopSeekUpdates()
            }
            playingPath == rec.path && mediaPlayer?.isPlaying == false -> {
                mediaPlayer?.start()
                adapter.setPlaying(rec.path, true)
                startSeekUpdates()
            }
            // New track
            else -> {
                stopPlayback()
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(rec.path)
                        prepare()
                        start()
                        setOnCompletionListener {
                            stopPlayback()
                            binding.playerBar.visibility = View.GONE
                        }
                    }
                    playingPath = rec.path
                    adapter.setPlaying(rec.path, true)
                    showPlayerBar(rec)
                    startSeekUpdates()
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot play: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPlayerBar(rec: Recording) {
        binding.playerBar.visibility = View.VISIBLE
        binding.tvNowPlaying.text    = rec.name
        binding.seekBar.max          = mediaPlayer?.duration ?: 0
        binding.seekBar.progress     = 0
        binding.tvTotalTime.text     = formatMs((mediaPlayer?.duration ?: 0).toLong())
        binding.tvCurrentTime.text   = "0:00"

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { mediaPlayer?.seekTo(progress); binding.tvCurrentTime.text = formatMs(progress.toLong()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = stopSeekUpdates()
            override fun onStopTrackingTouch(sb: SeekBar)  = startSeekUpdates()
        })

        binding.btnPlayerPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                binding.btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_play)
                adapter.setPlaying(playingPath, false)
                stopSeekUpdates()
            } else {
                mp.start()
                binding.btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                adapter.setPlaying(playingPath, true)
                startSeekUpdates()
            }
        }

        binding.btnPlayerClose.setOnClickListener {
            stopPlayback()
            binding.playerBar.visibility = View.GONE
        }
    }

    private fun startSeekUpdates() {
        binding.btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        seekRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                try {
                    binding.seekBar.progress  = mp.currentPosition
                    binding.tvCurrentTime.text = formatMs(mp.currentPosition.toLong())
                } catch (_: Exception) {}
                handler.postDelayed(this, 300)
            }
        }
        handler.post(seekRunnable!!)
    }

    private fun stopSeekUpdates() {
        seekRunnable?.let { handler.removeCallbacks(it) }
        binding.btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun stopPlayback() {
        stopSeekUpdates()
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        val prev = playingPath
        playingPath = null
        adapter.setPlaying(prev, false)
    }

    private fun formatMs(ms: Long): String {
        val min = TimeUnit.MILLISECONDS.toMinutes(ms)
        val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%d:%02d".format(min, sec)
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    private fun confirmDelete(rec: Recording) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(rec.name)
            .setPositiveButton("Delete") { _, _ ->
                if (playingPath == rec.path) { stopPlayback(); binding.playerBar.visibility = View.GONE }
                rec.file.delete()
                loadRecordings()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class RecordingAdapter(
    private val onPlay:   (Recording) -> Unit,
    private val onDelete: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private val items       = mutableListOf<Recording>()
    private var playingPath: String?  = null
    private var isPlaying:   Boolean  = false

    fun setData(data: List<Recording>) {
        items.clear(); items.addAll(data); notifyDataSetChanged()
    }

    fun setPlaying(path: String?, playing: Boolean) {
        playingPath = path; isPlaying = playing; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec      = items[position]
        val active   = playingPath == rec.path
        val playing  = active && isPlaying

        holder.tvName.text = rec.name
        holder.tvDate.text = rec.dateFormatted
        holder.tvSize.text = "${rec.sizeKb} KB"

        holder.btnPlay.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else         android.R.drawable.ic_media_play
        )
        holder.pbPlayback.visibility = if (active) View.VISIBLE else View.GONE

        holder.itemView.setBackgroundColor(
            if (active) 0xFF1A3A2A.toInt() else 0xFF16213E.toInt()
        )
        holder.btnPlay.setOnClickListener   { onPlay(rec) }
        holder.btnDelete.setOnClickListener { onDelete(rec) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:    TextView    = v.findViewById(R.id.tvRecName)
        val tvDate:    TextView    = v.findViewById(R.id.tvRecDate)
        val tvSize:    TextView    = v.findViewById(R.id.tvRecSize)
        val btnPlay:   ImageButton = v.findViewById(R.id.btnPlay)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDelete)
        val pbPlayback: ProgressBar = v.findViewById(R.id.pbPlayback)
    }
}
