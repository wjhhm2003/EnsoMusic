package com.enso.music.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.SongEntity
import com.enso.music.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Collections
import kotlin.math.pow

class MusicPlaybackService : Service(),
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener,
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "MusicPlaybackService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "enso_music_playback_channel"

        const val ACTION_PLAY = "com.enso.music.ACTION_PLAY"
        const val ACTION_PAUSE = "com.enso.music.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.enso.music.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.enso.music.ACTION_NEXT"
        const val ACTION_PREV = "com.enso.music.ACTION_PREV"
        const val ACTION_STOP = "com.enso.music.ACTION_STOP"

        private const val PREF_NAME = "enso_playback_prefs"
        private const val KEY_LAST_PATH = "last_song_path"
        private const val KEY_LAST_POS = "last_position"
        private const val KEY_PLAY_MODE = "last_play_mode"
        private const val KEY_FINE_VOLUME = "fine_volume_percent"
    }

    inner class MusicBinder : Binder() {
        val service: MusicPlaybackService get() = this@MusicPlaybackService
    }

    private val binder = MusicBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var coverManager: CoverManager

    // Playback state
    private val playlist = ArrayList<SongEntity>()
    private var currentIndex = -1
    private var playMode = PlayMode.LOOP_ALL
    private val shuffleIndices = ArrayList<Int>()
    private var currentShufflePointer = -1
    private var pendingAutoStart = false
    private var preparingSongPath: String? = null
    private var isPreparingSong = false
    private var isPlayerPrepared = false

    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<SongEntity>>(emptyList())
    val currentQueue: StateFlow<List<SongEntity>> = _currentQueue.asStateFlow()

    private var fineVolumePercent = 75
    private val _fineVolumeFlow = MutableStateFlow(75)
    val fineVolumeFlow: StateFlow<Int> = _fineVolumeFlow.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false

    // Receivers for anti-leakage guards
    private var isNoisyReceiverRegistered = false
    private var isBluetoothReceiverRegistered = false
    private var audioDeviceCallback: AudioDeviceCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MusicPlaybackService onCreate")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        coverManager = CoverManager.getInstance(this)

        fineVolumePercent = prefs.getInt(KEY_FINE_VOLUME, 75)
        _fineVolumeFlow.value = fineVolumePercent

        initWakeLock()
        initMediaPlayer()
        initMediaSession()
        createNotificationChannel()
        restorePreferences()

        registerTripleBluetoothGuards()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun initWakeLock() {
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EnsoMusic:PlaybackWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // Safety timeout
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = createMediaPlayer()
        isPlayerPrepared = false
        applyFineVolume()
    }

    private fun createMediaPlayer(): MediaPlayer {
        return MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnPreparedListener(this@MusicPlaybackService)
            setOnCompletionListener(this@MusicPlaybackService)
            setOnErrorListener(this@MusicPlaybackService)
        }
    }

    @Suppress("DEPRECATION")
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "EnsoMusicSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    skipNext()
                }

                override fun onSkipToPrevious() {
                    skipPrevious()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                }

                override fun onStop() {
                    stop()
                }
            })
            isActive = true
        }
    }

    /**
     * TRIPLE GUARD: Immediate synchronous blocking pause upon Bluetooth / Audio output disconnect.
     * Guaranteed zero audio leakage through watch speakers!
     */
    private fun registerTripleBluetoothGuards() {
        // Guard 1: ACTION_AUDIO_BECOMING_NOISY (High priority system broadcast)
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(becomingNoisyReceiver, noisyFilter)
        isNoisyReceiverRegistered = true

        // Guard 2: Bluetooth connection status changes
        val btFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(bluetoothReceiver, btFilter)
        isBluetoothReceiverRegistered = true

        // Guard 3: AudioDeviceCallback (API 23+)
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (removedDevices == null) return
                for (device in removedDevices) {
                    val type = device.type
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    ) {
                        Log.w(TAG, "AudioDeviceCallback: output device removed: $type. Halting audio immediately!")
                        pauseSynchronouslyForAntiLeakage("AudioDeviceCallback ($type removed)")
                        break
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.w(TAG, "BECOMING_NOISY received! Halting audio immediately!")
                pauseSynchronouslyForAntiLeakage("ACTION_AUDIO_BECOMING_NOISY")
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
                    if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                        Log.w(TAG, "BluetoothA2dp disconnecting/disconnected! Halting audio immediately!")
                        pauseSynchronouslyForAntiLeakage("A2DP_DISCONNECTED")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.w(TAG, "Bluetooth ACL disconnected! Halting audio immediately!")
                    pauseSynchronouslyForAntiLeakage("ACL_DISCONNECTED")
                }
            }
        }
    }

    /**
     * Ultra-fast synchronous halt for anti-leakage.
     */
    @Synchronized
    private fun pauseSynchronouslyForAntiLeakage(reason: String) {
        Log.i(TAG, "pauseSynchronouslyForAntiLeakage invoked: $reason")
        pendingAutoStart = false
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing mediaPlayer", e)
        }
        releaseWakeLock()
        abandonAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification()
        publishPlaybackInfo()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this, mainHandler)
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        hasAudioFocus = false
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "AudioFocus permanent loss")
                resumeOnFocusGain = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "AudioFocus transient loss")
                val wasPlaying = isPlaying()
                pausePlayback(releaseAudioFocus = false)
                resumeOnFocusGain = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "AudioFocus ducking")
                try {
                    val normalized = (fineVolumePercent.coerceIn(0, 100)) / 100f
                    val gain = if (fineVolumePercent == 0) 0f else normalized.toDouble().pow(1.8).toFloat()
                    mediaPlayer?.setVolume(gain * 0.2f, gain * 0.2f)
                } catch (ignored: Exception) {
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioFocus gained")
                applyFineVolume()
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    // Playback control
    fun setQueue(newQueue: List<SongEntity>, startIndex: Int = 0, startPlay: Boolean = true) {
        playlist.clear()
        playlist.addAll(newQueue)
        _currentQueue.value = playlist.toList()
        regenerateShuffleIndices()

        if (startIndex in playlist.indices) {
            currentIndex = startIndex
            currentShufflePointer = shuffleIndices.indexOf(currentIndex)
            prepareSong(playlist[currentIndex], startPlay)
        } else {
            currentIndex = -1
            stop()
        }
    }

    fun playQueueItem(index: Int) {
        if (index in playlist.indices) {
            currentIndex = index
            currentShufflePointer = shuffleIndices.indexOf(currentIndex)
            prepareSong(playlist[currentIndex], true)
        }
    }

    private fun prepareSong(song: SongEntity, autoStart: Boolean) {
        val file = File(song.path)
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: ${song.path}")
            skipNext()
            return
        }

        try {
            // Give every request its own player so callbacks from a previous
            // asynchronous prepare cannot advance the new queue.
            pendingAutoStart = autoStart
            preparingSongPath = song.path
            isPreparingSong = true
            isPlayerPrepared = false
            releaseWakeLock()
            val oldPlayer = mediaPlayer
            val newPlayer = createMediaPlayer()
            mediaPlayer = newPlayer
            try {
                oldPlayer?.release()
            } catch (ignored: Exception) {
            }
            newPlayer.setDataSource(song.path)
            newPlayer.prepareAsync()

            // Update session metadata immediately
            updateSessionMetadata(song)
            publishPlaybackInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting data source: ${song.path}", e)
            skipNext()
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        Log.i(TAG, "MediaPlayer prepared")
        val currentSong = getCurrentSong()
        if (mp == null || mp !== mediaPlayer || currentSong == null || currentSong.path != preparingSongPath) {
            Log.w(TAG, "Ignoring stale MediaPlayer prepared callback")
            return
        }

        isPreparingSong = false
        isPlayerPrepared = true
        val shouldStart = pendingAutoStart
        pendingAutoStart = false
        applyFineVolume()
        if (shouldStart && requestAudioFocus()) {
            mp?.start()
            acquireWakeLock()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            if (!shouldStart) {
                abandonAudioFocus()
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification()
        }
        publishPlaybackInfo()
        savePreferences()
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (mp == null || mp !== mediaPlayer || isPreparingSong || !isPlayerPrepared) return
        Log.i(TAG, "MediaPlayer playback completed")
        when (playMode) {
            PlayMode.LOOP_ONE -> {
                mediaPlayer?.seekTo(0)
                mediaPlayer?.start()
                acquireWakeLock()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                publishPlaybackInfo()
            }
            PlayMode.LOOP_ALL, PlayMode.SHUFFLE -> {
                skipNext()
            }
            PlayMode.SEQUENTIAL -> {
                if (currentIndex < playlist.size - 1) {
                    skipNext()
                } else {
                    stop()
                }
            }
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
        if (mp != null && mp !== mediaPlayer) return true
        if (isPreparingSong && preparingSongPath != getCurrentSong()?.path) return true
        isPreparingSong = false
        isPlayerPrepared = false
        pendingAutoStart = false
        preparingSongPath = null
        releaseWakeLock()
        abandonAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_ERROR)
        publishPlaybackInfo()
        // A corrupt or unsupported file should not strand playback. For a
        // single-item queue, stop instead of looping forever on the same path.
        if (playlist.size > 1) {
            skipNext()
        } else {
            stop()
        }
        return true
    }

    fun resume() {
        if (playlist.isEmpty()) return
        if (isPreparingSong) {
            pendingAutoStart = true
            return
        }
        if (currentIndex !in playlist.indices) {
            currentIndex = 0
            prepareSong(playlist[0], true)
            return
        }

        if (mediaPlayer == null) {
            initMediaPlayer()
            prepareSong(playlist[currentIndex], true)
            return
        }
        if (!isPlayerPrepared) {
            prepareSong(playlist[currentIndex], true)
            return
        }

        if (requestAudioFocus()) {
            mediaPlayer?.start()
            acquireWakeLock()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            publishPlaybackInfo()
        }
    }

    private fun pausePlayback(releaseAudioFocus: Boolean) {
        pendingAutoStart = false
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pause()", e)
        }
        releaseWakeLock()
        if (releaseAudioFocus) {
            abandonAudioFocus()
        }
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification()
        publishPlaybackInfo()
        savePreferences()
    }

    fun pause() {
        resumeOnFocusGain = false
        pausePlayback(releaseAudioFocus = true)
    }

    fun togglePlayPause() {
        if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }

    fun skipNext() {
        if (playlist.isEmpty()) return

        if (playMode == PlayMode.SHUFFLE && shuffleIndices.isNotEmpty()) {
            currentShufflePointer = (currentShufflePointer + 1) % shuffleIndices.size
            currentIndex = shuffleIndices[currentShufflePointer]
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
        prepareSong(playlist[currentIndex], true)
    }

    fun skipPrevious() {
        if (playlist.isEmpty()) return

        // If played more than 3 seconds, replay current song
        val currentPos = getPosition()
        if (currentPos > 3000L) {
            seekTo(0)
            return
        }

        if (playMode == PlayMode.SHUFFLE && shuffleIndices.isNotEmpty()) {
            currentShufflePointer = if (currentShufflePointer - 1 < 0) shuffleIndices.size - 1 else currentShufflePointer - 1
            currentIndex = shuffleIndices[currentShufflePointer]
        } else {
            currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        }
        prepareSong(playlist[currentIndex], true)
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            publishPlaybackInfo()
        } catch (ignored: Exception) {
        }
    }

    fun stop() {
        pendingAutoStart = false
        preparingSongPath = null
        isPreparingSong = false
        isPlayerPrepared = false
        try {
            mediaPlayer?.stop()
        } catch (ignored: IllegalStateException) {
            // The player may still be idle or preparing.
        } finally {
            try {
                mediaPlayer?.reset()
            } catch (ignored: Exception) {
            }
        }
        releaseWakeLock()
        abandonAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        publishPlaybackInfo()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun togglePlayMode(): PlayMode {
        playMode = playMode.next()
        if (playMode == PlayMode.SHUFFLE) {
            regenerateShuffleIndices()
        }
        publishPlaybackInfo()
        savePreferences()
        return playMode
    }

    fun isPlaying(): Boolean {
        if (!isPlayerPrepared) return false
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    fun getPosition(): Long {
        if (!isPlayerPrepared) return 0L
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getDuration(): Long {
        if (!isPlayerPrepared) return 0L
        return try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getCurrentSong(): SongEntity? {
        return if (currentIndex in playlist.indices) playlist[currentIndex] else null
    }

    // Phase 2: Fine-grained Software Volume (0..100) & System Volume Control
    fun setFineVolume(percent: Int) {
        val coerced = percent.coerceIn(0, 100)
        fineVolumePercent = coerced
        _fineVolumeFlow.value = coerced
        prefs.edit().putInt(KEY_FINE_VOLUME, coerced).apply()
        applyFineVolume()
    }

    fun adjustFineVolume(delta: Int): Int {
        val target = (fineVolumePercent + delta).coerceIn(0, 100)
        setFineVolume(target)
        return target
    }

    fun getFineVolume(): Int = fineVolumePercent

    fun applyFineVolume() {
        try {
            val normalized = (fineVolumePercent.coerceIn(0, 100)) / 100f
            val gain = if (fineVolumePercent == 0) 0f else normalized.toDouble().pow(1.8).toFloat()
            mediaPlayer?.setVolume(gain, gain)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying fine volume", e)
        }
    }

    fun getSystemVolume(): Int {
        return try {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            0
        }
    }

    fun getMaxSystemVolume(): Int {
        return try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            15
        }
    }

    fun adjustSystemVolume(direction: Int): Int {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting system volume", e)
        }
        return getSystemVolume()
    }

    private fun regenerateShuffleIndices() {
        shuffleIndices.clear()
        for (i in playlist.indices) {
            shuffleIndices.add(i)
        }
        Collections.shuffle(shuffleIndices)
        currentShufflePointer = shuffleIndices.indexOf(currentIndex)
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, getPosition(), 1.0f)
                .build()
        )
    }

    private fun updateSessionMetadata(song: SongEntity) {
        val coverBmp = coverManager.getBitmap(song.coverPath)
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)

        if (coverBmp != null && !coverBmp.isRecycled) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBmp)
        }

        mediaSession?.setMetadata(metaBuilder.build())
    }

    private fun publishPlaybackInfo() {
        _playbackInfo.value = PlaybackInfo(
            currentSong = getCurrentSong(),
            isPlaying = isPlaying(),
            positionMs = getPosition(),
            durationMs = getDuration(),
            playMode = playMode,
            queueSize = playlist.size,
            currentIndex = currentIndex
        )
    }

    // Notifications
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Enso Music Playback"
            val descriptionText = "Watch Local Music Player Playback Controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = getCurrentSong()
        val title = song?.title ?: "Enso Music"
        val subtitle = if (song != null) "${song.artist} • ${song.album}" else "已暂停"

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MusicPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isCurrentlyPlaying = isPlaying()
        val playPauseIcon = if (isCurrentlyPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_enso_logo)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isCurrentlyPlaying)
            .setShowWhen(false)
            .addAction(R.drawable.ic_skip_previous_24, "上一首", prevIntent)
            .addAction(playPauseIcon, if (isCurrentlyPlaying) "暂停" else "播放", toggleIntent)
            .addAction(R.drawable.ic_skip_next_24, "下一首", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        val coverBmp = coverManager.getBitmap(song?.coverPath)
        if (coverBmp != null && !coverBmp.isRecycled) {
            builder.setLargeIcon(coverBmp)
        }

        return builder.build()
    }

    private fun updateNotification() {
        if (playlist.isEmpty()) return
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun savePreferences() {
        val song = getCurrentSong()
        prefs.edit().apply {
            putString(KEY_LAST_PATH, song?.path)
            putLong(KEY_LAST_POS, getPosition())
            putString(KEY_PLAY_MODE, playMode.name)
            apply()
        }
    }

    private fun restorePreferences() {
        val savedModeStr = prefs.getString(KEY_PLAY_MODE, PlayMode.LOOP_ALL.name)
        playMode = try {
            PlayMode.valueOf(savedModeStr ?: PlayMode.LOOP_ALL.name)
        } catch (e: Exception) {
            PlayMode.LOOP_ALL
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> skipNext()
            ACTION_PREV -> skipPrevious()
            ACTION_STOP -> {
                stop()
                stopSelfResult(startId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "MusicPlaybackService onDestroy")
        savePreferences()

        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (ignored: Exception) {
            }
            isNoisyReceiverRegistered = false
        }

        if (isBluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
            } catch (ignored: Exception) {
            }
            isBluetoothReceiverRegistered = false
        }

        if (audioDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (ignored: Exception) {
            }
            audioDeviceCallback = null
        }

        stop()
        mediaSession?.release()
        mediaPlayer?.release()
        mediaPlayer = null
        releaseWakeLock()
        super.onDestroy()
    }
}
