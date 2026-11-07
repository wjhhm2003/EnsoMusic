package com.enso.music.ui.fragment

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.lyric.LyricManager
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.databinding.FragmentNowPlayingBinding
import com.enso.music.service.MusicPlaybackService
import com.enso.music.service.PlayMode
import com.enso.music.service.PlaybackInfo
import com.enso.music.service.SleepTimerState
import com.enso.music.ui.MainActivity
import com.enso.music.ui.adapter.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var isUserTrackingSeekBar = false
    private var progressJob: Job? = null
    private var volumeAutoHideJob: Job? = null
    private var sleepLabelJob: Job? = null
    private var isScreenOn = true

    /** Path of the song whose cover is currently bound. */
    private var boundSongPath: String? = null
    private var boundCoverPath: String? = null
    private var isCoverBound = false
    private var lastShownError: String? = null
    private var lastPlayMode: PlayMode? = null
    private var lastIsPlaying: Boolean? = null

    // Lyric state
    var isLyricShowing: Boolean = false
        private set
    private var currentSongPath: String? = null
    private var currentLyricData: LyricData = LyricData.EMPTY
    private var lyricJob: Job? = null
    private var lyricSyncJob: Job? = null
    private var lyricLoadToken = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.isSelected = true
        setupControls()
        setupLyricControls()
        setupVolumeControls()
        setupSleepTimer()
        observePlaybackState()
    }

    private fun getService(): MusicPlaybackService? = (activity as? MainActivity)?.playbackService

    private fun formatSubtitle(song: SongEntity?): String =
        if (song != null) "${song.artist} · ${song.album}" else getString(R.string.now_playing_empty_hint)

    // =========================================================================
    // Controls
    // =========================================================================

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            val service = getService() ?: return@setOnClickListener
            if (service.playbackInfo.value.currentSong == null) {
                (activity as? MainActivity)?.navigateToPage(0)
                return@setOnClickListener
            }
            service.togglePlayPause()
        }
        binding.btnPrevious.setOnClickListener { getService()?.skipPrevious() }
        binding.btnNext.setOnClickListener { getService()?.skipNext() }

        binding.btnPlayMode.setOnClickListener {
            val mode = getService()?.cyclePlayModeForButton() ?: return@setOnClickListener
            Toast.makeText(requireContext(), mode.label, Toast.LENGTH_SHORT).show()
        }

        binding.btnQueue.setOnClickListener { (activity as? MainActivity)?.navigateToPage(2) }
        binding.btnLyricCapsule.setOnClickListener { showLyricView() }
        binding.cardCover.setOnClickListener { showLyricView() }

        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) showSeekPreview(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
                binding.tvElapsed.setTextColor(resources.getColor(R.color.white, null))
                showSeekPreview(seekBar?.progress ?: 0)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val service = getService()
                val duration = service?.getDuration() ?: 0L
                if (service != null && duration > 0) {
                    service.seekTo((seekBar!!.progress / 1000f * duration).toLong())
                }
                isUserTrackingSeekBar = false
                binding.tvElapsed.setTextColor(resources.getColor(R.color.text_secondary, null))
            }
        })
    }

    private fun showSeekPreview(progress: Int) {
        val duration = getService()?.getDuration() ?: 0L
        if (duration > 0) {
            binding.tvElapsed.text = FormatUtils.formatDuration((progress / 1000f * duration).toLong())
        }
    }

    private fun observePlaybackState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (activity as? MainActivity)?.serviceConnectedFlow?.collectLatest { connected ->
                    if (!connected) return@collectLatest
                    val service = getService() ?: return@collectLatest
                    coroutineScope {
                        launch { service.playbackInfo.collect { updateUi(it) } }
                        launch {
                            service.fineVolumeFlow.collect { fineVol ->
                                binding.btnVolumeCapsule.text = getString(R.string.volume_capsule, fineVol)
                                binding.tvVolumeTitle.text = getString(R.string.volume_title, fineVol)
                                if (binding.sbFineVolume.progress != fineVol) binding.sbFineVolume.progress = fineVol
                            }
                        }
                        launch { service.sleepTimer.collect { renderSleepTimer(it) } }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Volume overlay
    // =========================================================================

    private fun setupVolumeControls() {
        binding.btnVolumeCapsule.setOnClickListener { showVolumeOverlay() }
        binding.layoutVolumeOverlay.setOnClickListener { hideVolumeOverlay() }
        binding.cardVolumePanel.setOnClickListener { resetVolumeAutoHide() }

        binding.btnVolMinus.setOnClickListener {
            getService()?.adjustFineVolume(-1)
            resetVolumeAutoHide()
        }
        binding.btnVolPlus.setOnClickListener {
            getService()?.adjustFineVolume(1)
            resetVolumeAutoHide()
        }

        binding.sbFineVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    getService()?.setFineVolume(progress)
                    resetVolumeAutoHide()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                volumeAutoHideJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) = resetVolumeAutoHide()
        })

        binding.btnSysVolMinus.setOnClickListener {
            val service = getService() ?: return@setOnClickListener
            service.adjustSystemVolume(AudioManager.ADJUST_LOWER)
            updateSystemVolumeUi(service)
            resetVolumeAutoHide()
        }
        binding.btnSysVolPlus.setOnClickListener {
            val service = getService() ?: return@setOnClickListener
            service.adjustSystemVolume(AudioManager.ADJUST_RAISE)
            updateSystemVolumeUi(service)
            resetVolumeAutoHide()
        }
    }

    fun showVolumeOverlay() {
        if (_binding == null) return
        val service = getService() ?: return
        updateSystemVolumeUi(service)
        val currentVol = service.getFineVolume()
        binding.btnVolumeCapsule.text = getString(R.string.volume_capsule, currentVol)
        binding.tvVolumeTitle.text = getString(R.string.volume_title, currentVol)
        binding.sbFineVolume.progress = currentVol

        val overlay = binding.layoutVolumeOverlay
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
        }
        overlay.animate().alpha(1f).setDuration(120).start()
        resetVolumeAutoHide()
    }

    fun hideVolumeOverlay() {
        if (_binding == null) return
        volumeAutoHideJob?.cancel()
        volumeAutoHideJob = null
        val overlay = binding.layoutVolumeOverlay
        overlay.animate().cancel()
        overlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { _binding?.layoutVolumeOverlay?.visibility = View.GONE }
            .start()
    }

    private fun resetVolumeAutoHide() {
        volumeAutoHideJob?.cancel()
        volumeAutoHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(2500L)
            hideVolumeOverlay()
        }
    }

    private fun updateSystemVolumeUi(service: MusicPlaybackService) {
        binding.tvSystemVolumeText.text =
            getString(R.string.system_volume_steps, service.getSystemVolume(), service.getMaxSystemVolume())
    }

    // =========================================================================
    // Sleep timer
    // =========================================================================

    private fun setupSleepTimer() {
        binding.btnSleepTimer.setOnClickListener { showSleepOverlay() }
        binding.layoutSleepOverlay.setOnClickListener { hideSleepOverlay() }
        binding.cardSleepPanel.setOnClickListener { /* swallow */ }

        val grid = binding.gridSleepOptions
        for (i in 0 until grid.childCount) {
            val chip = grid.getChildAt(i) as? TextView ?: continue
            chip.setOnClickListener {
                val service = getService() ?: return@setOnClickListener
                val tag = chip.tag as? String
                if (tag == "end") {
                    service.setStopAfterCurrent()
                    Toast.makeText(requireContext(), R.string.sleep_after_current_set, Toast.LENGTH_SHORT).show()
                } else {
                    val minutes = tag?.toIntOrNull() ?: return@setOnClickListener
                    service.setSleepTimer(minutes)
                    Toast.makeText(requireContext(), getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show()
                }
                hideSleepOverlay()
            }
        }
        binding.btnSleepCancel.setOnClickListener {
            getService()?.cancelSleepTimer()
            hideSleepOverlay()
        }
    }

    private fun showSleepOverlay() {
        binding.layoutSleepOverlay.alpha = 0f
        binding.layoutSleepOverlay.visibility = View.VISIBLE
        binding.layoutSleepOverlay.animate().alpha(1f).setDuration(150).start()
        binding.btnSleepCancel.visibility =
            if (getService()?.sleepTimer?.value?.isActive == true) View.VISIBLE else View.GONE
    }

    private fun hideSleepOverlay() {
        binding.layoutSleepOverlay.animate().cancel()
        binding.layoutSleepOverlay.visibility = View.GONE
    }

    private fun renderSleepTimer(state: SleepTimerState) {
        sleepLabelJob?.cancel()
        val badge = binding.btnSleepTimer
        when {
            state.stopAfterCurrent -> {
                badge.text = getString(R.string.sleep_badge_after_current)
                badge.setTextColor(resources.getColor(R.color.accent_green_highlight, null))
            }
            state.endAtElapsedMs > 0 -> {
                badge.setTextColor(resources.getColor(R.color.accent_green_highlight, null))
                sleepLabelJob = viewLifecycleOwner.lifecycleScope.launch {
                    while (isActive) {
                        val remainingMs = state.endAtElapsedMs - SystemClock.elapsedRealtime()
                        val minutes = ((remainingMs + 59_999) / 60_000).coerceAtLeast(0)
                        badge.text = getString(R.string.sleep_badge_minutes, minutes)
                        if (remainingMs <= 0) break
                        // Update at the next minute boundary only.
                        delay(((remainingMs - 1) % 60_000) + 1)
                    }
                }
            }
            else -> {
                badge.text = getString(R.string.sleep_badge_off)
                badge.setTextColor(resources.getColor(R.color.white, null))
            }
        }
    }

    // =========================================================================
    // Lyrics
    // =========================================================================

    private fun setupLyricControls() {
        binding.btnLyricBack.setOnClickListener { hideLyricView() }
        binding.btnLyricRefresh.setOnClickListener {
            val song = getService()?.playbackInfo?.value?.currentSong ?: return@setOnClickListener
            Toast.makeText(requireContext(), R.string.lyric_searching, Toast.LENGTH_SHORT).show()
            loadLyricsForSong(song, forceRefresh = true)
        }
        binding.btnLyricDisplayMode.setOnClickListener {
            binding.btnLyricDisplayMode.text = binding.lyricView.cycleDisplayMode()
        }
        binding.lyricView.onLineClickListener = { targetMs -> getService()?.seekTo(targetMs) }
    }

    fun showLyricView() {
        if (_binding == null) return
        isLyricShowing = true
        val overlay = binding.layoutLyricOverlay
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(160).start()
        binding.tvLyricSongTitle.isSelected = true
        val song = getService()?.playbackInfo?.value?.currentSong
        if (song != null) {
            binding.tvLyricSongTitle.text = song.title
            if (currentSongPath != song.path || currentLyricData.isEmpty()) {
                loadLyricsForSong(song, forceRefresh = false)
            } else {
                binding.lyricView.updatePlaybackProgress(getService()?.getPosition() ?: 0L)
            }
        } else {
            binding.tvLyricSongTitle.text = getString(R.string.nothing_playing)
            showLyricEmptyState(true)
        }
        startLyricSyncIfNeeded()
    }

    fun handleBackPressed(): Boolean {
        if (_binding == null) return false
        if (binding.layoutSleepOverlay.visibility == View.VISIBLE) {
            hideSleepOverlay()
            return true
        }
        if (binding.layoutVolumeOverlay.visibility == View.VISIBLE) {
            hideVolumeOverlay()
            return true
        }
        if (isLyricShowing) {
            hideLyricView()
            return true
        }
        return false
    }

    fun hideLyricView(): Boolean {
        if (!isLyricShowing) return false
        isLyricShowing = false
        stopLyricSync()
        binding.layoutLyricOverlay.animate().cancel()
        binding.layoutLyricOverlay.visibility = View.GONE
        return true
    }

    private fun loadLyricsForSong(song: SongEntity, forceRefresh: Boolean) {
        lyricJob?.cancel()
        val loadToken = ++lyricLoadToken
        val keepCurrent = forceRefresh && currentSongPath == song.path && !currentLyricData.isEmpty()
        currentSongPath = song.path
        if (!keepCurrent) {
            currentLyricData = LyricData.EMPTY
            showLyricLoadingState()
        }
        binding.tvLyricSongTitle.text = song.title
        binding.tvLyricSongTitle.isSelected = true

        lyricJob = LyricManager.getInstance(requireContext()).loadLyric(
            song = song,
            forceOnlineRefresh = forceRefresh
        ) { lyricData ->
            // A cancelled request can still complete on Main; drop stale results.
            if (!isAdded || _binding == null || loadToken != lyricLoadToken || currentSongPath != song.path) {
                return@loadLyric
            }
            if (lyricData.isEmpty() && !currentLyricData.isEmpty()) return@loadLyric
            currentLyricData = lyricData
            if (lyricData.isEmpty()) {
                showLyricEmptyState(true)
            } else {
                showLyricEmptyState(false)
                binding.lyricView.setLyricData(lyricData)
                binding.lyricView.updatePlaybackProgress(getService()?.getPosition() ?: 0L)
            }
            if (isLyricShowing) startLyricSyncIfNeeded()
        }
    }

    private fun showLyricLoadingState() {
        binding.tvLyricEmpty.text = getString(R.string.lyric_loading)
        binding.tvLyricEmpty.visibility = View.VISIBLE
        binding.lyricView.visibility = View.GONE
    }

    private fun showLyricEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.tvLyricEmpty.text = getString(R.string.lyric_empty)
            binding.tvLyricEmpty.visibility = View.VISIBLE
            binding.lyricView.visibility = View.GONE
        } else {
            binding.tvLyricEmpty.visibility = View.GONE
            binding.lyricView.visibility = View.VISIBLE
        }
    }

    private fun startLyricSyncIfNeeded() {
        stopLyricSync()
        if (!isLyricShowing || !isScreenOn || !isResumed || getService()?.isPlaying() != true) return
        if (currentLyricData.isEmpty()) return
        // 30 fps for word-level karaoke, ~3 Hz for line-level sync.
        val syncDelayMs = if (currentLyricData.hasWordTiming) 33L else 300L
        lyricSyncJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val service = getService()
                if (service != null && service.isPlaying()) {
                    binding.lyricView.updatePlaybackProgress(service.getPosition())
                }
                delay(syncDelayMs)
            }
        }
    }

    private fun stopLyricSync() {
        lyricSyncJob?.cancel()
        lyricSyncJob = null
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    private fun updateUi(info: PlaybackInfo) {
        val song = info.currentSong
        if (song != null) {
            if (binding.tvTitle.text != song.title) {
                binding.tvTitle.text = song.title
                binding.tvTitle.isSelected = true
            }
            binding.tvArtistAlbum.text = formatSubtitle(song)

            if (boundSongPath != song.path) {
                boundSongPath = song.path
                if (isLyricShowing) {
                    loadLyricsForSong(song, forceRefresh = false)
                } else {
                    // Lyrics load lazily when the lyric page opens.
                    lyricJob?.cancel()
                    lyricLoadToken++
                    currentSongPath = null
                    currentLyricData = LyricData.EMPTY
                }
                binding.tvLyricSongTitle.text = song.title
            }
            if (!isCoverBound || boundCoverPath != song.coverPath) {
                bindCover(song.coverPath)
            }
        } else {
            boundSongPath = null
            binding.tvTitle.text = getString(R.string.nothing_playing)
            binding.tvLyricSongTitle.text = getString(R.string.nothing_playing)
            binding.tvArtistAlbum.text = getString(R.string.now_playing_empty_hint)
            currentSongPath = null
            currentLyricData = LyricData.EMPTY
            showLyricEmptyState(true)
            if (!isCoverBound || boundCoverPath != null) bindCover(null)
        }

        val playing = info.isPlaying
        binding.pbBuffering.visibility = if (info.isPreparing && playing) View.VISIBLE else View.GONE
        if (lastIsPlaying != playing) {
            lastIsPlaying = playing
            binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            binding.btnPlayPause.contentDescription = getString(if (playing) R.string.action_pause else R.string.action_play)
            binding.cardCover.animate().cancel()
            binding.cardCover.animate()
                .scaleX(if (playing) 1f else 0.94f)
                .scaleY(if (playing) 1f else 0.94f)
                .setDuration(220)
                .start()
        }
        if (playing) {
            startProgressPolling()
            if (isLyricShowing) startLyricSyncIfNeeded()
        } else {
            stopProgressPolling()
            stopLyricSync()
        }

        if (lastPlayMode != info.playMode) {
            lastPlayMode = info.playMode
            val icon = when (info.playMode) {
                PlayMode.LOOP_ALL -> R.drawable.ic_repeat_all_24
                PlayMode.LOOP_ONE -> R.drawable.ic_repeat_one_24
                PlayMode.SHUFFLE -> R.drawable.ic_shuffle_24
                PlayMode.SEQUENTIAL -> R.drawable.ic_repeat_off_24
            }
            binding.btnPlayMode.setImageResource(icon)
            binding.btnPlayMode.contentDescription = info.playMode.label
        }

        val error = info.errorMessage
        if (error != null && error != lastShownError) {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
        lastShownError = error

        if (!isUserTrackingSeekBar) updateProgressBar(info.positionMs, info.durationMs)
    }

    private fun bindCover(coverPath: String?) {
        boundCoverPath = coverPath
        isCoverBound = true
        val context = context ?: return
        CoverManager.getInstance(context).loadInto(
            binding.ivCover,
            coverPath,
            R.drawable.ic_music_note_24,
            viewLifecycleOwner.lifecycleScope
        )
    }

    private fun updateProgressBar(posMs: Long, durMs: Long) {
        binding.sbProgress.progress =
            if (durMs > 0) ((posMs.toFloat() / durMs) * 1000).toInt().coerceIn(0, 1000) else 0
        binding.tvElapsed.text = FormatUtils.formatDuration(posMs)
        binding.tvDuration.text = FormatUtils.formatDuration(durMs)
    }

    // =========================================================================
    // Power: tick only while visible, playing and the screen is on.
    // =========================================================================

    fun onScreenStateChanged(screenOn: Boolean) {
        isScreenOn = screenOn
        if (_binding == null) return
        if (isScreenOn && isResumed && getService()?.isPlaying() == true) {
            startProgressPolling()
            if (isLyricShowing) startLyricSyncIfNeeded()
        } else {
            stopProgressPolling()
            stopLyricSync()
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true || !isScreenOn || !isResumed) return
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val service = getService()
                if (service != null && !isUserTrackingSeekBar) {
                    val pos = service.getPosition()
                    updateProgressBar(pos, service.getDuration())
                    // Align ticks with second boundaries so the clock never skips.
                    delay(1000L - (pos % 1000L).coerceIn(0L, 999L) + 15L)
                } else {
                    delay(1000L)
                }
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    override fun onResume() {
        super.onResume()
        binding.tvTitle.isSelected = true
        binding.tvLyricSongTitle.isSelected = true
        val service = getService()
        if (service != null) {
            // Render the latest snapshot immediately as well as through the flow.
            // This closes the small startup race where the service connects before
            // the fragment starts collecting, leaving the primary screen blank.
            updateUi(service.playbackInfo.value)
            updateProgressBar(service.getPosition(), service.getDuration())
            if (isScreenOn && service.isPlaying()) {
                startProgressPolling()
                if (isLyricShowing) startLyricSyncIfNeeded()
            }
        }
    }

    override fun onPause() {
        stopProgressPolling()
        stopLyricSync()
        super.onPause()
    }

    override fun onDestroyView() {
        stopProgressPolling()
        stopLyricSync()
        lyricJob?.cancel()
        lyricJob = null
        lyricLoadToken++
        volumeAutoHideJob?.cancel()
        volumeAutoHideJob = null
        sleepLabelJob?.cancel()
        boundSongPath = null
        boundCoverPath = null
        isCoverBound = false
        lastIsPlaying = null
        lastPlayMode = null
        _binding = null
        super.onDestroyView()
    }
}
