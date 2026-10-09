package com.example.asic

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var visualizerLayout: LinearLayout
    private lateinit var playlistContainer: ScrollView
    private lateinit var playlistLayout: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvDuration: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPlay: Button

    private var isPlaying = false
    private var isPlaylistVisible = false
    private var currentIndex = 0
    private var visualizerJob: Job? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var mediaPlayer: MediaPlayer? = null
    private var audioVisualizer: Visualizer? = null

    // Daftar lagu — otomatis dari res/raw
    private val tracks = mutableListOf<Track>()

    data class Track(val title: String, val resId: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadTracksFromRaw()
        startForegroundNotification()
        setupFloatingView()
    }

    /**
     * Memuat lagu dari res/raw secara otomatis.
     * Setiap file .mp3 di folder raw akan jadi lagu di playlist.
     */
    private fun loadTracksFromRaw() {
        // Ambil semua resource di R.raw secara otomatis via reflection
        val rawFields = R.raw::class.java.fields
        for (field in rawFields) {
            try {
                val resId = field.getInt(null)
                val name = field.name
                    .replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                tracks.add(Track(name, resId))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Kalau kosong, kasih placeholder biar app tetap jalan
        if (tracks.isEmpty()) {
            tracks.add(Track("(Tidak ada lagu di res/raw)", 0))
        }
    }

    private fun startForegroundNotification() {
        val channelId = "asic_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Asic Music Player",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Asic aktif")
            .setContentText("Musik sedang melayang...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    private fun setupFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_player, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        windowManager.addView(floatingView, params)

        // Referensi view
        visualizerLayout = floatingView.findViewById(R.id.visualizer)
        playlistContainer = floatingView.findViewById(R.id.playlistContainer)
        playlistLayout = floatingView.findViewById(R.id.playlistLayout)
        tvTitle = floatingView.findViewById(R.id.tvTitle)
        tvCurrent = floatingView.findViewById(R.id.tvCurrent)
        tvDuration = floatingView.findViewById(R.id.tvDuration)
        progressBar = floatingView.findViewById(R.id.progressBar)
        btnPlay = floatingView.findViewById(R.id.btnPlay)

        // Bikin bar visualizer (20 bar)
        for (i in 0 until 20) {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 5, 1f).apply { marginEnd = 2 }
                setBackgroundColor(0xFF00FF00.toInt())
            }
            visualizerLayout.addView(bar)
        }

        buildPlaylist()
        tvTitle.text = tracks[currentIndex].title

        // Tombol Play/Pause
        btnPlay.setOnClickListener {
            if (tracks[currentIndex].resId == 0) {
                Toast.makeText(this, "Tidak ada lagu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isPlaying) pauseMusic() else playMusic()
        }

        // Tombol Close
        floatingView.findViewById<Button>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }

        // Tombol Next
        floatingView.findViewById<Button>(R.id.btnNext).setOnClickListener {
            currentIndex = (currentIndex + 1) % tracks.size
            updateTrackAndPlay()
        }

        // Tombol Prev
        floatingView.findViewById<Button>(R.id.btnPrev).setOnClickListener {
            currentIndex = (currentIndex - 1 + tracks.size) % tracks.size
            updateTrackAndPlay()
        }

        // Tombol Toggle Playlist
        floatingView.findViewById<Button>(R.id.btnTogglePlaylist).setOnClickListener {
            isPlaylistVisible = !isPlaylistVisible
            playlistContainer.visibility = if (isPlaylistVisible) View.VISIBLE else View.GONE
        }

        // Progress bar bisa diklik untuk seek
        progressBar.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && mediaPlayer != null) {
                val percent = event.x / progressBar.width
                val seekTo = (mediaPlayer!!.duration * percent).toInt()
                mediaPlayer?.seekTo(seekTo)
            }
            true
        }

        setupDrag(params)
    }

    // ================== MUSIC ==================

    private fun playMusic() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, tracks[currentIndex].resId)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnCompletionListener {
                    // Auto next saat lagu habis
                    currentIndex = (currentIndex + 1) % tracks.size
                    updateTrackAndPlay()
                }
                start()
            }
            isPlaying = true
            btnPlay.text = "⏸"
            tvDuration.text = formatTime(mediaPlayer?.duration ?: 0)
            startVisualizer()
            startProgressUpdate()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memutar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pauseMusic() {
        mediaPlayer?.pause()
        isPlaying = false
        btnPlay.text = "▶"
        stopVisualizer()
        stopProgressUpdate()
    }

    private fun updateTrackAndPlay() {
        val wasPlaying = isPlaying
        mediaPlayer?.release()
        mediaPlayer = null
        stopVisualizer()
        stopProgressUpdate()
        isPlaying = false
        btnPlay.text = "▶"
        tvTitle.text = tracks[currentIndex].title
        tvCurrent.text = "0:00"
        tvDuration.text = "0:00"
        progressBar.progress = 0
        buildPlaylist()
        if (wasPlaying) playMusic()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    // ================== VISUALIZER ==================

    private fun startVisualizer() {
        val mp = mediaPlayer ?: return
        try {
            audioVisualizer = Visualizer(mp.audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {
                        updateBars(waveform)
                    }
                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) = Unit
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) {
            // Kalau gagal, fallback ke animasi random
            startRandomVisualizer()
        }
    }

    private fun updateBars(waveform: ByteArray?) {
        if (waveform == null) return
        scope.launch {
            val chunkSize = waveform.size / 20
            for (i in 0 until 20) {
                val bar = visualizerLayout.getChildAt(i) ?: continue
                var sum = 0
                for (j in 0 until chunkSize) {
                    val idx = i * chunkSize + j
                    if (idx < waveform.size) {
                        sum += kotlin.math.abs(waveform[idx].toInt())
                    }
                }
                val avg = sum / chunkSize
                val height = ((avg / 128f) * 80).toInt().coerceIn(5, 80)
                val lp = bar.layoutParams
                lp.height = height
                bar.layoutParams = lp
            }
        }
    }

    private fun startRandomVisualizer() {
        visualizerJob = scope.launch {
            while (isActive) {
                for (i in 0 until visualizerLayout.childCount) {
                    val bar = visualizerLayout.getChildAt(i)
                    val lp = bar.layoutParams
                    lp.height = (10..80).random()
                    bar.layoutParams = lp
                }
                delay(100)
            }
        }
    }

    private fun stopVisualizer() {
        visualizerJob?.cancel()
        try {
            audioVisualizer?.enabled = false
            audioVisualizer?.release()
        } catch (_: Exception) {}
        audioVisualizer = null
        for (i in 0 until visualizerLayout.childCount) {
            val bar = visualizerLayout.getChildAt(i)
            val lp = bar.layoutParams
            lp.height = 5
            bar.layoutParams = lp
        }
    }

    // ================== PROGRESS ==================

    private fun startProgressUpdate() {
        progressJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    try {
                        val cur = it.currentPosition
                        val dur = it.duration
                        if (dur > 0) {
                            progressBar.progress = (cur * 100 / dur)
                            tvCurrent.text = formatTime(cur)
                        }
                    } catch (_: Exception) {}
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    // ================== PLAYLIST ==================

    private fun buildPlaylist() {
        playlistLayout.removeAllViews()
        tracks.forEachIndexed { index, track ->
            val item = TextView(this).apply {
                text = "${index + 1}. ${track.title}"
                setTextColor(if (index == currentIndex) Color.BLACK else 0xFF00FF00.toInt())
                setBackgroundColor(if (index == currentIndex) 0xFF00FF00.toInt() else Color.TRANSPARENT)
                textSize = 11f
                setPadding(8, 8, 8, 8)
                isClickable = true
                setOnClickListener {
                    currentIndex = index
                    updateTrackAndPlay()
                }
            }
            playlistLayout.addView(item)
        }
    }

    // ================== DRAG ==================

    private fun setupDrag(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        val dragArea = floatingView.findViewById<TextView>(R.id.tvTitle)

        dragArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopVisualizer()
        mediaPlayer?.release()
        mediaPlayer = null
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
    }
}