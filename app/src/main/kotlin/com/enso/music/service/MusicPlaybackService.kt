package com.enso.music.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.db.AppDatabase
import com.enso.music.data.entity.SongEntity
import com.enso.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground playback service.
 *
 * Threading: every method runs on the main thread. MediaPlayer callbacks are
 * delivered on the main looper because the players are created there, so no
 * locking is required.
 *
 * State model:
 * - [queue] owns the ordering (see [PlayQueue]).
 * - [playWhenReady] is the user's intent. The UI shows "playing" whenever the
 *   user asked to play, even while the decoder is still preparing, so the
 *   play button responds instantly.
 * - Each track gets a fresh MediaPlayer, so callbacks from an earlier async
 *   prepare can be identified (`mp !== mediaPlayer`) and ignored.
 */
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
        private const val KEY_QUEUE_INDEX = "last_queue_index"
        private const val KEY_SEAMLESS = "last_queue_seamless"
        private const val QUEUE_FILE = "playback_queue.txt"

        private const val FADE_DURATION_MS = 220L
        private const val FADE_STEP_MS = 20L
        private const val DUCK_FACTOR = 0.2f
        private const val POSITION_SAVE_INTERVAL_MS = 15_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val SQLITE_VAR_CHUNK = 500
        private val DEFERRABLE_ACTIONS = setOf(ACTION_PLAY, ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV)
    }

    inner class MusicBinder : Binder() {
        val service: MusicPlaybackService get() = this@MusicPlaybackService
    }

    private val binder = MusicBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private lateinit var coverManager: CoverManager
    private var mediaSession: MediaSessionCompat? = null

    // ---- Queue & player state ------------------------------------------------
    private val queue = PlayQueue<SongEntity>()
    private var playMode = PlayMode.LOOP_ALL

    private var mediaPlayer: MediaPlayer? = null
    private var isPlayerPrepared = false
    private var isPreparingSong = false
    private var preparingSongPath: String? = null
    private var playWhenReady = false
    /** Position to seek to once the current track is prepared (restore / seek before prepare). */
    private var pendingSeekMs = 0L
    private var prepareGeneration = 0
    private var consecutiveFailures = 0
    private var lastError: String? = null
    /** Set once the user picks a queue, so the async restore never clobbers it. */
    private var queueSetByUser = false
    /** False until the persisted queue has been loaded. */
    private var restoreDone = false
    /** Transport command received (e.g. from the notification) before restore finished. */
    private var pendingCommand: String? = null
    private val queueWriteMutex = Mutex()

    // ---- Album gapless playback (setNextMediaPlayer) --------------------------
    private var seamlessPlaybackEnabled = false
    private var nextMediaPlayer: MediaPlayer? = null
    private var nextPlayerIndex = -1
    private var nextPreparingSongPath: String? = null
    private var isNextPlayerPrepared = false
    private var nextPlayerAttached = false

    // ---- Volume --------------------------------------------------------------
    private var fineVolumePercent = 75
    private var duckFactor = 1f
    private var fadeFactor = 1f
    private var fadeRunnable: Runnable? = null

    // ---- Audio focus ---------------------------------------------------------
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false

    // ---- Output-device guards ------------------------------------------------
    private var isNoisyReceiverRegistered = false
    private var isBluetoothReceiverRegistered = false
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // ---- Cover for notification / session ------------------------------------
    private var currentCover: Bitmap? = null
    private var currentCoverKey: String? = null
    private var coverJob: Job? = null

    // ---- Public state --------------------------------------------------------
    private val _playbackInfo = MutableStateFlow(PlaybackInfo())
    val playbackInfo: StateFlow<PlaybackInfo> = _playbackInfo.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<SongEntity>>(emptyList())
    val currentQueue: StateFlow<List<SongEntity>> = _currentQueue.asStateFlow()

    private val _fineVolumeFlow = MutableStateFlow(75)
    val fineVolumeFlow: StateFlow<Int> = _fineVolumeFlow.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        coverManager = CoverManager.getInstance(this)

        fineVolumePercent = prefs.getInt(KEY_FINE_VOLUME, 75).coerceIn(0, 100)
        _fineVolumeFlow.value = fineVolumePercent
        playMode = runCatching {
            PlayMode.valueOf(prefs.getString(KEY_PLAY_MODE, null) ?: PlayMode.LOOP_ALL.name)
        }.getOrDefault(PlayMode.LOOP_ALL)

        initMediaSession()
        createNotificationChannel()
        registerOutputGuards()
        startForeground(NOTIFICATION_ID, buildNotification())
        publishPlaybackInfo()
        restoreQueueAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required by startForegroundService(): promote first, then settle.
        startForeground(NOTIFICATION_ID, buildNotification())
        val action = intent?.action
        if (!restoreDone && queue.isEmpty && action in DEFERRABLE_ACTIONS) {
            // The process was cold-started from the notification; run the
            // command once the saved queue is back.
            pendingCommand = action
            return START_STICKY
        }
        dispatchCommand(action, startId)?.let { return it }
        updateForegroundState()
        return START_STICKY
    }

    private fun dispatchCommand(action: String?, startId: Int): Int? {
        when (action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> skipNext()
            ACTION_PREV -> skipPrevious()
            ACTION_STOP -> {
                stop()
                if (startId >= 0) stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        return null
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackState()
        if (!isPlaying()) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        savePlaybackState()
        unregisterOutputGuards()
        cancelSleepTimer()
        cancelFade()
        stopPositionSaver()
        discardNextMediaPlayer()
        releasePlayer()
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        serviceScope.cancel()
        super.onDestroy()
    }

    // =========================================================================
    // Public playback API (called by UI via the binder)
    // =========================================================================

    fun setQueue(
        newQueue: List<SongEntity>,
        startIndex: Int = 0,
        startPlay: Boolean = true,
        enableSeamlessPlayback: Boolean = false
    ) {
        queueSetByUser = true
        discardNextMediaPlayer()
        seamlessPlaybackEnabled = enableSeamlessPlayback && newQueue.size > 1
        queue.setItems(newQueue, startIndex)
        consecutiveFailures = 0
        publishQueue(persist = true)

        if (queue.currentIndex >= 0) {
            prepareSong(queue.currentIndex, startPlay)
        } else {
            stop()
        }
    }

    fun playQueueItem(index: Int) {
        if (!queue.moveTo(index)) return
        // A queue jump is an explicit user choice, not album "play all".
        seamlessPlaybackEnabled = false
        consecutiveFailures = 0
        prepareSong(index, autoStart = true)
    }

    /** Inserts [song] right after the current track. */
    fun playNext(song: SongEntity) {
        queueSetByUser = true
        if (queue.isEmpty) {
            setQueue(listOf(song), 0, startPlay = true)
            return
        }
        queue.insertNext(song)
        discardNextMediaPlayer()
        publishQueue(persist = true)
        prepareNextMediaPlayerIfNeeded()
        publishPlaybackInfo()
    }

    fun removeFromQueue(index: Int) {
        if (queue[index] == null) return
        val wasCurrent = queue.removeAt(index)
        discardNextMediaPlayer()
        publishQueue(persist = true)
        when {
            queue.isEmpty -> stop()
            wasCurrent -> prepareSong(queue.currentIndex, playWhenReady)
            else -> {
                prepareNextMediaPlayerIfNeeded()
                publishPlaybackInfo()
                savePlaybackState()
            }
        }
    }

    fun resume() {
        if (queue.isEmpty) return
        resumeOnFocusGain = false
        playWhenReady = true
        when {
            isPreparingSong -> publishPlaybackInfo() // starts in onPrepared
            queue.currentIndex < 0 -> {
                queue.moveTo(0)
                prepareSong(0, autoStart = true)
            }
            mediaPlayer == null || !isPlayerPrepared ->
                prepareSong(queue.currentIndex, autoStart = true, startPositionMs = pendingSeekMs)
            else -> {
                val player = mediaPlayer!!
                if (fadeRunnable != null || !player.isPlayingSafe()) {
                    startPlayback(fadeIn = true)
                }
                publishPlaybackInfo()
            }
        }
    }

    fun pause() = pauseInternal(fade = true, releaseFocus = true)

    fun togglePlayPause() {
        if (playWhenReady) pause() else resume()
    }

    fun skipNext() {
        if (queue.isEmpty) return
        consecutiveFailures = 0
        val next = queue.advance(playMode, userInitiated = true)
        if (next >= 0) prepareSong(next, autoStart = true)
    }

    fun skipPrevious() {
        if (queue.isEmpty) return
        if (getPosition() > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        consecutiveFailures = 0
        val prev = queue.retreat(playMode)
        if (prev >= 0) prepareSong(prev, autoStart = true)
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        val player = mediaPlayer
        if (player != null && isPlayerPrepared) {
            try {
                player.seekTo(target.toInt())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "seekTo in illegal state", e)
            }
        } else {
            pendingSeekMs = target
        }
        updateSessionState()
        publishPlaybackInfo()
    }

    /** Fully stops playback, keeping the queue so playback can be resumed later. */
    fun stop() {
        playWhenReady = false
        resumeOnFocusGain = false
        cancelFade()
        stopPositionSaver()
        discardNextMediaPlayer()
        releasePlayer()
        pendingSeekMs = 0L
        abandonAudioFocus()
        updateSessionState(PlaybackStateCompat.STATE_STOPPED)
        publishPlaybackInfo()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    fun togglePlayMode(): PlayMode = setPlayMode(playMode.next())

    fun cyclePlayModeForButton(): PlayMode = setPlayMode(playMode.nextForButton())

    fun setPlayMode(mode: PlayMode): PlayMode {
        if (mode == playMode) return playMode
        playMode = mode
        if (mode == PlayMode.SHUFFLE) queue.reshuffle()
        try {
            if (isPlayerPrepared) mediaPlayer?.isLooping = (mode == PlayMode.LOOP_ONE)
        } catch (ignored: IllegalStateException) {
        }
        discardNextMediaPlayer()
        prepareNextMediaPlayerIfNeeded()
        prefs.edit().putString(KEY_PLAY_MODE, mode.name).apply()
        publishPlaybackInfo()
        return playMode
    }

    /** Intent to play (true while preparing a track the user asked to play). */
    fun isPlaying(): Boolean = playWhenReady && (isPlayerPrepared || isPreparingSong)

    fun getPosition(): Long {
        val player = mediaPlayer
        if (player == null || !isPlayerPrepared) return pendingSeekMs
        return try {
            player.currentPosition.toLong()
        } catch (e: IllegalStateException) {
            0L
        }
    }

    fun getDuration(): Long {
        val player = mediaPlayer
        if (player != null && isPlayerPrepared) {
            try {
                val d = player.duration.toLong()
                if (d > 0) return d
            } catch (ignored: IllegalStateException) {
            }
        }
        return queue.current?.duration ?: 0L
    }

    fun getCurrentSong(): SongEntity? = queue.current

    // ---- Sleep timer ---------------------------------------------------------

    private val sleepRunnable = Runnable {
        Log.i(TAG, "Sleep timer fired")
        _sleepTimer.value = SleepTimerState()
        pause()
    }

    /** Pauses after [minutes]. 0 cancels. */
    fun setSleepTimer(minutes: Int) {
        mainHandler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            _sleepTimer.value = SleepTimerState()
            return
        }
        val durationMs = minutes * 60_000L
        mainHandler.postDelayed(sleepRunnable, durationMs)
        _sleepTimer.value = SleepTimerState(endAtElapsedMs = SystemClock.elapsedRealtime() + durationMs)
    }

    /** Pauses when the current track finishes. */
    fun setStopAfterCurrent() {
        mainHandler.removeCallbacks(sleepRunnable)
        _sleepTimer.value = SleepTimerState(stopAfterCurrent = true)
        // The pre-attached next player would start automatically; detach it.
        discardNextMediaPlayer()
        try {
            if (isPlayerPrepared) mediaPlayer?.isLooping = false
        } catch (ignored: IllegalStateException) {
        }
    }

    fun cancelSleepTimer() {
        val wasStopAfterCurrent = _sleepTimer.value.stopAfterCurrent
        mainHandler.removeCallbacks(sleepRunnable)
        _sleepTimer.value = SleepTimerState()
        if (wasStopAfterCurrent) {
            try {
                if (isPlayerPrepared) mediaPlayer?.isLooping = (playMode == PlayMode.LOOP_ONE)
            } catch (ignored: IllegalStateException) {
            }
            prepareNextMediaPlayerIfNeeded()
        }
    }

    // ---- Volume --------------------------------------------------------------

    fun setFineVolume(percent: Int) {
        val coerced = percent.coerceIn(0, 100)
        if (coerced == fineVolumePercent && _fineVolumeFlow.value == coerced) return
        fineVolumePercent = coerced
        _fineVolumeFlow.value = coerced
        prefs.edit().putInt(KEY_FINE_VOLUME, coerced).apply()
        applyVolume()
    }

    fun adjustFineVolume(delta: Int): Int {
        setFineVolume(fineVolumePercent + delta)
        return fineVolumePercent
    }

    fun getFineVolume(): Int = fineVolumePercent

    fun applyFineVolume() = applyVolume()

    fun getSystemVolume(): Int =
        runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)

    fun getMaxSystemVolume(): Int =
        runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)

    fun adjustSystemVolume(direction: Int): Int {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (e: Exception) {
            Log.e(TAG, "adjustSystemVolume failed", e)
        }
        return getSystemVolume()
    }

    // =========================================================================
    // Player management
    // =========================================================================

    private fun createMediaPlayer(): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        // The platform holds a partial wake lock only while audio is actually
        // playing, and drops it on pause/stop/release.
        setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        setOnPreparedListener(this@MusicPlaybackService)
        setOnCompletionListener(this@MusicPlaybackService)
        setOnErrorListener(this@MusicPlaybackService)
    }

    private fun releasePlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        isPlayerPrepared = false
        isPreparingSong = false
        preparingSongPath = null
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
    }

    private fun prepareSong(index: Int, autoStart: Boolean, startPositionMs: Long = 0L) {
        val song = queue[index] ?: return
        queue.moveTo(index)
        val generation = ++prepareGeneration

        discardNextMediaPlayer()
        cancelFade()
        fadeFactor = 1f
        stopPositionSaver()
        releasePlayer()

        playWhenReady = autoStart
        pendingSeekMs = startPositionMs.coerceAtLeast(0L)
        lastError = null
        isPreparingSong = true
        preparingSongPath = song.path

        onTrackChanged(song)

        if (!File(song.path).exists()) {
            Log.w(TAG, "File missing: ${song.path}")
            handleTrackFailure(generation, "文件不存在：${song.title}")
            return
        }

        try {
            val player = createMediaPlayer()
            mediaPlayer = player
            player.setDataSource(song.path)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed: ${song.path}", e)
            handleTrackFailure(generation, "无法打开：${song.title}")
            return
        }

        updateSessionState(PlaybackStateCompat.STATE_BUFFERING)
        publishPlaybackInfo()
    }

    /**
     * Skips past a track that could not be played. Gives up after every song
     * in the queue failed once in a row, so a queue of missing files can't
     * spin forever.
     */
    private fun handleTrackFailure(generation: Int, message: String) {
        if (generation != prepareGeneration) return
        releasePlayer()
        lastError = message
        consecutiveFailures++
        val wantPlay = playWhenReady

        if (queue.size > 1 && consecutiveFailures < queue.size) {
            val modeForSkip = if (playMode == PlayMode.LOOP_ONE) PlayMode.LOOP_ALL else playMode
            val next = queue.advance(modeForSkip, userInitiated = true)
            publishPlaybackInfo()
            // Post to break recursion and let the UI show the error briefly.
            mainHandler.post {
                if (generation == prepareGeneration && next >= 0) {
                    prepareSong(next, wantPlay)
                    lastError = message
                    publishPlaybackInfo()
                }
            }
        } else {
            consecutiveFailures = 0
            playWhenReady = false
            abandonAudioFocus()
            updateSessionState(PlaybackStateCompat.STATE_ERROR)
            publishPlaybackInfo()
            updateForegroundState()
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        if (mp == null) return
        if (mp === nextMediaPlayer) {
            onNextPlayerPrepared(mp)
            return
        }
        val current = queue.current
        if (mp !== mediaPlayer || current == null || current.path != preparingSongPath) {
            Log.w(TAG, "Ignoring stale onPrepared")
            return
        }

        isPreparingSong = false
        isPlayerPrepared = true
        consecutiveFailures = 0
        try {
            mp.isLooping = playMode == PlayMode.LOOP_ONE && !_sleepTimer.value.stopAfterCurrent
            val seek = pendingSeekMs
            if (seek > 0 && seek < mp.duration - 1000) mp.seekTo(seek.toInt())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "post-prepare setup failed", e)
        }
        pendingSeekMs = 0L
        applyVolume()

        if (playWhenReady) {
            startPlayback(fadeIn = false)
        } else {
            updateSessionState(PlaybackStateCompat.STATE_PAUSED)
            updateForegroundState()
        }
        publishPlaybackInfo()
        savePlaybackState()
    }

    private fun startPlayback(fadeIn: Boolean) {
        val player = mediaPlayer ?: return
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied")
            playWhenReady = false
            publishPlaybackInfo()
            return
        }
        playWhenReady = true
        val alreadyRunning = player.isPlayingSafe()
        cancelFade()
        // Resuming during a fade-out continues from the current level.
        fadeFactor = if (fadeIn && !alreadyRunning) 0f else if (fadeIn) fadeFactor else 1f
        applyVolume()
        try {
            player.start()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "start() in illegal state", e)
            return
        }
        if (fadeIn) fadeTo(1f, null)
        updateSessionState(PlaybackStateCompat.STATE_PLAYING)
        updateForegroundState()
        startPositionSaver()
        prepareNextMediaPlayerIfNeeded()
        attachNextMediaPlayerIfReady()
    }

    private fun pauseInternal(fade: Boolean, releaseFocus: Boolean) {
        playWhenReady = false
        if (releaseFocus) resumeOnFocusGain = false
        val player = mediaPlayer
        if (player != null && isPlayerPrepared && player.isPlayingSafe() && fade) {
            // Flip the UI right away; the actual pause happens after the ramp.
            publishPlaybackInfo()
            fadeTo(0f) { finishPause(releaseFocus) }
        } else {
            cancelFade()
            finishPause(releaseFocus)
        }
    }

    private fun finishPause(releaseFocus: Boolean) {
        try {
            if (mediaPlayer?.isPlayingSafe() == true) mediaPlayer?.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause() in illegal state", e)
        }
        fadeFactor = 1f
        applyVolume()
        stopPositionSaver()
        if (releaseFocus) abandonAudioFocus()
        updateSessionState(PlaybackStateCompat.STATE_PAUSED)
        updateForegroundState()
        publishPlaybackInfo()
        savePlaybackState()
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (mp == null || mp !== mediaPlayer || isPreparingSong || !isPlayerPrepared) return
        Log.i(TAG, "Track completed")

        if (_sleepTimer.value.stopAfterCurrent) {
            _sleepTimer.value = SleepTimerState()
            val next = queue.advance(playMode, userInitiated = false)
            if (next >= 0) {
                prepareSong(next, autoStart = false)
            } else {
                endOfQueue()
            }
            return
        }

        if (seamlessPlaybackEnabled && promoteSeamlessNextPlayer()) return

        val next = queue.advance(playMode, userInitiated = false)
        if (next >= 0) prepareSong(next, autoStart = true) else endOfQueue()
    }

    /** SEQUENTIAL mode reached the end: rewind the last track and pause. */
    private fun endOfQueue() {
        playWhenReady = false
        try {
            mediaPlayer?.seekTo(0)
        } catch (ignored: IllegalStateException) {
        }
        stopPositionSaver()
        abandonAudioFocus()
        updateSessionState(PlaybackStateCompat.STATE_PAUSED)
        updateForegroundState()
        publishPlaybackInfo()
        savePlaybackState()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
        if (mp != null && mp === nextMediaPlayer) {
            discardNextMediaPlayer()
            return true
        }
        if (mp == null || mp !== mediaPlayer) return true
        val title = queue.current?.title ?: ""
        handleTrackFailure(prepareGeneration, "无法播放：$title")
        return true
    }

    private fun MediaPlayer.isPlayingSafe(): Boolean = try {
        isPlaying
    } catch (e: IllegalStateException) {
        false
    }

    // =========================================================================
    // Album gapless playback
    // =========================================================================

    private fun prepareNextMediaPlayerIfNeeded() {
        if (!seamlessPlaybackEnabled || !isPlayerPrepared || nextMediaPlayer != null) return
        if (_sleepTimer.value.stopAfterCurrent || playMode == PlayMode.LOOP_ONE) return
        val index = queue.peekNext(playMode, userInitiated = false)
        if (index < 0 || index == queue.currentIndex) return
        val song = queue[index] ?: return
        try {
            nextPlayerIndex = index
            nextPreparingSongPath = song.path
            isNextPlayerPrepared = false
            nextPlayerAttached = false
            val player = createMediaPlayer()
            nextMediaPlayer = player
            player.setDataSource(song.path)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to prepare gapless next track: ${song.path}", e)
            discardNextMediaPlayer()
        }
    }

    private fun onNextPlayerPrepared(mp: MediaPlayer) {
        if (nextPreparingSongPath != queue[nextPlayerIndex]?.path) {
            discardNextMediaPlayer()
            return
        }
        isNextPlayerPrepared = true
        applyVolume(mp)
        attachNextMediaPlayerIfReady()
    }

    private fun attachNextMediaPlayerIfReady() {
        val current = mediaPlayer ?: return
        val next = nextMediaPlayer ?: return
        if (!isPlayerPrepared || !isNextPlayerPrepared || nextPlayerAttached) return
        try {
            current.setNextMediaPlayer(next)
            nextPlayerAttached = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setNextMediaPlayer failed", e)
        }
    }

    private fun promoteSeamlessNextPlayer(): Boolean {
        val next = nextMediaPlayer ?: return false
        val nextSong = queue[nextPlayerIndex] ?: return false
        if (!isNextPlayerPrepared || !nextPlayerAttached) return false

        val completed = mediaPlayer
        mediaPlayer = next
        queue.moveTo(nextPlayerIndex)
        preparingSongPath = nextSong.path
        isPreparingSong = false
        isPlayerPrepared = true
        nextMediaPlayer = null
        nextPlayerIndex = -1
        nextPreparingSongPath = null
        isNextPlayerPrepared = false
        nextPlayerAttached = false
        try {
            completed?.release()
        } catch (ignored: Exception) {
        }

        applyVolume()
        onTrackChanged(nextSong)
        updateSessionState(PlaybackStateCompat.STATE_PLAYING)
        updateForegroundState()
        publishPlaybackInfo()
        savePlaybackState()
        prepareNextMediaPlayerIfNeeded()
        return true
    }

    private fun discardNextMediaPlayer() {
        val next = nextMediaPlayer
        val wasAttached = nextPlayerAttached
        nextMediaPlayer = null
        nextPlayerIndex = -1
        nextPreparingSongPath = null
        isNextPlayerPrepared = false
        nextPlayerAttached = false
        if (wasAttached) {
            try {
                mediaPlayer?.setNextMediaPlayer(null)
            } catch (ignored: Exception) {
            }
        }
        try {
            next?.release()
        } catch (ignored: Exception) {
        }
    }

    // =========================================================================
    // Volume / fades
    // =========================================================================

    private fun applyVolume(player: MediaPlayer? = mediaPlayer) {
        val gain = FineVolume.gain(fineVolumePercent) * duckFactor * fadeFactor
        try {
            player?.setVolume(gain, gain)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setVolume failed", e)
        }
    }

    private fun fadeTo(target: Float, onEnd: (() -> Unit)?) {
        cancelFade()
        val start = fadeFactor
        val steps = (FADE_DURATION_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
        val runnable = object : Runnable {
            var step = 0
            override fun run() {
                step++
                fadeFactor = start + (target - start) * (step.toFloat() / steps)
                applyVolume()
                if (step >= steps) {
                    fadeRunnable = null
                    onEnd?.invoke()
                } else {
                    mainHandler.postDelayed(this, FADE_STEP_MS)
                }
            }
        }
        fadeRunnable = runnable
        mainHandler.postDelayed(runnable, FADE_STEP_MS)
    }

    private fun cancelFade() {
        fadeRunnable?.let { mainHandler.removeCallbacks(it) }
        fadeRunnable = null
    }

    // =========================================================================
    // Audio focus
    // =========================================================================

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this, mainHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
                Log.i(TAG, "Focus lost")
                hasAudioFocus = false
                resumeOnFocusGain = false
                pauseInternal(fade = true, releaseFocus = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Focus lost transiently")
                // Set before pausing so updateForegroundState() keeps us foreground.
                resumeOnFocusGain = playWhenReady
                pauseInternal(fade = false, releaseFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckFactor = DUCK_FACTOR
                applyVolume()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckFactor = 1f
                applyVolume()
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resume()
                }
            }
        }
    }

    // =========================================================================
    // Output-device guards: pause instantly when headphones go away so audio
    // never leaks to the watch speaker.
    // =========================================================================

    private fun registerOutputGuards() {
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(becomingNoisyReceiver, noisyFilter)
        isNoisyReceiverRegistered = true

        val btFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(bluetoothReceiver, btFilter)
        isBluetoothReceiverRegistered = true

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val removedHeadphones = removedDevices?.any { it.isSink && isPersonalOutput(it.type) } == true
                if (removedHeadphones) haltForOutputLoss("AudioDeviceCallback")
            }
        }
        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
    }

    private fun isPersonalOutput(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun unregisterOutputGuards() {
        if (isNoisyReceiverRegistered) {
            runCatching { unregisterReceiver(becomingNoisyReceiver) }
            isNoisyReceiverRegistered = false
        }
        if (isBluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothReceiver) }
            isBluetoothReceiverRegistered = false
        }
        audioDeviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        audioDeviceCallback = null
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                haltForOutputLoss("BECOMING_NOISY")
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED)
                    if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING) {
                        haltForOutputLoss("A2DP_DISCONNECTED")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // A watch is constantly linked to its phone over BT. Only an
                    // audio device dropping should pause playback.
                    @Suppress("DEPRECATION")
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val major = try {
                        device?.bluetoothClass?.majorDeviceClass
                    } catch (e: SecurityException) {
                        null
                    }
                    if (major == null || major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                        haltForOutputLoss("ACL_DISCONNECTED")
                    }
                }
            }
        }
    }

    /** Immediate, un-faded pause. */
    private fun haltForOutputLoss(reason: String) {
        if (!playWhenReady && mediaPlayer?.isPlayingSafe() != true) return
        Log.w(TAG, "Output lost ($reason): pausing immediately")
        resumeOnFocusGain = false
        cancelFade()
        try {
            if (mediaPlayer?.isPlayingSafe() == true) mediaPlayer?.pause()
        } catch (ignored: IllegalStateException) {
        }
        pauseInternal(fade = false, releaseFocus = true)
    }

    // =========================================================================
    // Media session, notification, foreground
    // =========================================================================

    private fun initMediaSession() {
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSessionCompat(this, "EnsoMusicSession").apply {
            setSessionActivity(sessionActivity)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
            isActive = true
        }
    }

    private fun updateSessionState(explicitState: Int? = null) {
        val state = explicitState ?: when {
            isPreparingSong && playWhenReady -> PlaybackStateCompat.STATE_BUFFERING
            playWhenReady -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
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
                .setState(state, getPosition(), if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    /** Session metadata + cover, loaded off the main thread. */
    private fun onTrackChanged(song: SongEntity) {
        val coverKey = song.coverPath
        if (coverKey != currentCoverKey) {
            currentCover = null
            currentCoverKey = coverKey
            coverJob?.cancel()
            if (!coverKey.isNullOrBlank()) {
                coverJob = serviceScope.launch {
                    val bmp = withContext(Dispatchers.IO) { coverManager.getBitmap(coverKey) }
                    if (currentCoverKey == coverKey && bmp != null && !bmp.isRecycled) {
                        currentCover = bmp
                        queue.current?.let { setSessionMetadata(it) }
                        refreshNotification()
                    }
                }
            }
        }
        setSessionMetadata(song)
    }

    private fun setSessionMetadata(song: SongEntity) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
        currentCover?.takeIf { !it.isRecycled }?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Enso Music 播放控制"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun serviceActionIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun buildNotification(): Notification {
        val song = queue.current
        val playing = isPlaying()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song?.title ?: getString(R.string.app_name))
            .setContentText(song?.let { "${it.artist} · ${it.album}" } ?: "未在播放")
            .setSmallIcon(R.drawable.ic_enso_logo)
            .setContentIntent(openApp)
            .setDeleteIntent(serviceActionIntent(4, ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_skip_previous_24, "上一首", serviceActionIntent(1, ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24,
                if (playing) "暂停" else "播放",
                serviceActionIntent(2, ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_skip_next_24, "下一首", serviceActionIntent(3, ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        currentCover?.takeIf { !it.isRecycled }?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun refreshNotification() {
        if (queue.isEmpty) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Foreground only while playing (or about to). When paused, the service is
     * demoted but the notification stays so the user can resume from it, and
     * the system is free to reclaim the process if memory is tight. State is
     * persisted, so nothing is lost.
     */
    private fun updateForegroundState() {
        if (pendingCommand != null) return
        // Stay foreground through a transient focus loss (e.g. a call) so the
        // process survives until playback resumes.
        if (isPlaying() || resumeOnFocusGain) {
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            if (queue.isEmpty) {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            } else {
                refreshNotification()
            }
        }
    }

    // =========================================================================
    // State publishing & persistence
    // =========================================================================

    private fun publishPlaybackInfo() {
        _playbackInfo.value = PlaybackInfo(
            currentSong = queue.current,
            isPlaying = isPlaying(),
            isPreparing = isPreparingSong,
            positionMs = getPosition(),
            durationMs = getDuration(),
            playMode = playMode,
            queueSize = queue.size,
            currentIndex = queue.currentIndex,
            errorMessage = lastError
        )
    }

    private fun publishQueue(persist: Boolean) {
        val snapshot = queue.snapshot()
        _currentQueue.value = snapshot
        if (persist) {
            val paths = snapshot.map { it.path }
            val appContext = applicationContext
            serviceScope.launch(Dispatchers.IO) {
                queueWriteMutex.withLock { writeQueueFile(appContext, paths) }
            }
        }
    }

    private fun savePlaybackState() {
        prefs.edit()
            .putString(KEY_LAST_PATH, queue.current?.path)
            .putLong(KEY_LAST_POS, getPosition())
            .putInt(KEY_QUEUE_INDEX, queue.currentIndex)
            .putBoolean(KEY_SEAMLESS, seamlessPlaybackEnabled)
            .putString(KEY_PLAY_MODE, playMode.name)
            .apply()
    }

    private val positionSaver = object : Runnable {
        override fun run() {
            if (!playWhenReady) return
            prefs.edit().putLong(KEY_LAST_POS, getPosition()).apply()
            mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    private fun startPositionSaver() {
        mainHandler.removeCallbacks(positionSaver)
        mainHandler.postDelayed(positionSaver, POSITION_SAVE_INTERVAL_MS)
    }

    private fun stopPositionSaver() {
        mainHandler.removeCallbacks(positionSaver)
    }

    private fun writeQueueFile(context: Context, paths: List<String>) {
        try {
            val target = File(context.filesDir, QUEUE_FILE)
            val tmp = File(context.filesDir, "$QUEUE_FILE.tmp")
            tmp.writeText(paths.joinToString("\n"), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(paths.joinToString("\n"), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist queue", e)
        }
    }

    /**
     * Restores the last queue paused at the saved position. Falls back to the
     * single last-played song for installs that predate the queue file.
     */
    private fun restoreQueueAsync() {
        val lastPath = prefs.getString(KEY_LAST_PATH, null)
        val lastIndex = prefs.getInt(KEY_QUEUE_INDEX, -1)
        val lastPos = prefs.getLong(KEY_LAST_POS, 0L)
        val seamless = prefs.getBoolean(KEY_SEAMLESS, false)
        val appContext = applicationContext

        serviceScope.launch {
            val restored = withContext(Dispatchers.IO) {
                try {
                    val file = File(appContext.filesDir, QUEUE_FILE)
                    val paths = if (file.exists()) {
                        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                    } else {
                        listOfNotNull(lastPath)
                    }
                    if (paths.isEmpty()) return@withContext emptyList<SongEntity>()
                    val dao = AppDatabase.getInstance(appContext).songDao()
                    val byPath = HashMap<String, SongEntity>(paths.size)
                    for (chunk in paths.distinct().chunked(SQLITE_VAR_CHUNK)) {
                        for (song in dao.getSongsByPaths(chunk)) byPath[song.path] = song
                    }
                    paths.mapNotNull { byPath[it] }
                } catch (e: Exception) {
                    Log.w(TAG, "Queue restore failed", e)
                    emptyList()
                }
            }
            if (!queueSetByUser && restored.isNotEmpty()) applyRestoredQueue(restored, lastPath, lastIndex, lastPos, seamless)
            restoreDone = true
            val command = pendingCommand
            pendingCommand = null
            if (command != null) dispatchCommand(command, -1)
            updateForegroundState()
        }
    }

    private fun applyRestoredQueue(
        restored: List<SongEntity>,
        lastPath: String?,
        lastIndex: Int,
        lastPos: Long,
        seamless: Boolean
    ) {
        run {
            var index = restored.indexOfFirst { it.path == lastPath }
            if (index < 0) index = lastIndex.coerceIn(0, restored.lastIndex)
            seamlessPlaybackEnabled = seamless && restored.size > 1
            queue.setItems(restored, index)
            pendingSeekMs = if (restored[index].path == lastPath) lastPos else 0L
            publishQueue(persist = false)
            onTrackChanged(restored[index])
            updateSessionState(PlaybackStateCompat.STATE_PAUSED)
            publishPlaybackInfo()
            Log.i(TAG, "Restored queue of ${restored.size}, index=$index, pos=$pendingSeekMs")
        }
    }
}
