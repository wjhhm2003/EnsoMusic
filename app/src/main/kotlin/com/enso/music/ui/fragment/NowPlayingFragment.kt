package com.enso.music.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.enso.music.R
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.SongEntity
import com.enso.music.databinding.FragmentNowPlayingBinding
import com.enso.music.service.MusicPlaybackService
import com.enso.music.service.PlayMode
import com.enso.music.ui.MainActivity
import com.enso.music.ui.adapter.FormatUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NowPlayingFragment : Fragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private var isUserTrackingSeekBar = false
    private var progressJob: Job? = null
    private var isScreenOn = true

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
        // Crucial marquee fix: enable marquee continuous scrolling
        binding.tvTitle.isSelected = true
        setupControls()
        observePlaybackState()
    }

    private fun getService(): MusicPlaybackService? {
        return (activity as? MainActivity)?.playbackService
    }

    private fun formatSubtitle(song: SongEntity?): String {
        return if (song != null) {
            "${song.artist} · ${song.album}"
        } else {
            "请在曲库选择歌曲"
        }
    }

    private fun setupControls() {
        binding.btnPlayPause.setOnClickListener {
            getService()?.togglePlayPause()
        }

        binding.btnPrevious.setOnClickListener {
            getService()?.skipPrevious()
        }

        binding.btnNext.setOnClickListener {
            getService()?.skipNext()
        }

        binding.btnPlayMode.setOnClickListener {
            val service = getService() ?: return@setOnClickListener
            // Cycle sequence: LOOP_ALL (列表循环 🔁) -> LOOP_ONE (单曲循环 🔂) -> SHUFFLE (随机播放 🔀) -> LOOP_ALL
            val targetMode = when (service.playbackInfo.value.playMode) {
                PlayMode.LOOP_ALL -> PlayMode.LOOP_ONE
                PlayMode.LOOP_ONE -> PlayMode.SHUFFLE
                PlayMode.SHUFFLE -> PlayMode.LOOP_ALL
                PlayMode.SEQUENTIAL -> PlayMode.LOOP_ALL
            }
            while (service.playbackInfo.value.playMode != targetMode) {
                service.togglePlayMode()
            }
        }

        binding.btnQueue.setOnClickListener {
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                mainActivity.navigateToPage(2)
            } else {
                activity?.findViewById<ViewPager2>(R.id.viewPager)?.currentItem = 2
            }
        }

        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = getService()?.getDuration() ?: 0L
                    if (duration > 0) {
                        val targetMs = (progress / 1000f * duration).toLong()
                        binding.tvArtistAlbum.text = "${FormatUtils.formatDuration(targetMs)} / ${FormatUtils.formatDuration(duration)}"
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
                val duration = getService()?.getDuration() ?: 0L
                val progress = seekBar?.progress ?: 0
                if (duration > 0) {
                    val targetMs = (progress / 1000f * duration).toLong()
                    binding.tvArtistAlbum.text = "${FormatUtils.formatDuration(targetMs)} / ${FormatUtils.formatDuration(duration)}"
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val service = getService()
                val duration = service?.getDuration() ?: 0L
                val progress = seekBar?.progress ?: 0
                if (duration > 0 && service != null) {
                    val targetMs = (progress / 1000f * duration).toLong()
                    service.seekTo(targetMs)
                }
                isUserTrackingSeekBar = false
                val currentSong = service?.playbackInfo?.value?.currentSong
                binding.tvArtistAlbum.text = formatSubtitle(currentSong)
            }
        })
    }

    private fun observePlaybackState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (activity as? MainActivity)?.serviceConnectedFlow?.collect { connected ->
                    if (connected) {
                        getService()?.playbackInfo?.collect { info ->
                            updateUi(info)
                        }
                    }
                }
            }
        }
    }

    private fun updateUi(info: com.enso.music.service.PlaybackInfo) {
        val song = info.currentSong
        if (song != null) {
            binding.tvTitle.text = song.title
            binding.tvTitle.isSelected = true
            if (!isUserTrackingSeekBar) {
                binding.tvArtistAlbum.text = formatSubtitle(song)
            }

            CoverManager.getInstance(requireContext()).loadInto(
                binding.ivCover,
                song.coverPath,
                R.drawable.ic_music_note_24,
                viewLifecycleOwner.lifecycleScope
            )
        } else {
            binding.tvTitle.text = "暂无播放"
            binding.tvTitle.isSelected = true
            if (!isUserTrackingSeekBar) {
                binding.tvArtistAlbum.text = "请在曲库选择歌曲"
            }
            binding.ivCover.setImageResource(R.drawable.ic_music_note_24)
        }

        // Play / Pause button
        if (info.isPlaying) {
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause_24)
            startProgressPolling()
        } else {
            binding.btnPlayPause.setImageResource(R.drawable.ic_play_24)
            stopProgressPolling()
        }

        // Mode icon
        when (info.playMode) {
            PlayMode.LOOP_ALL -> {
                binding.btnPlayMode.setImageResource(R.drawable.ic_repeat_all_24)
                binding.btnPlayMode.contentDescription = "列表循环"
            }
            PlayMode.LOOP_ONE -> {
                binding.btnPlayMode.setImageResource(R.drawable.ic_repeat_one_24)
                binding.btnPlayMode.contentDescription = "单曲循环"
            }
            PlayMode.SHUFFLE -> {
                binding.btnPlayMode.setImageResource(R.drawable.ic_shuffle_24)
                binding.btnPlayMode.contentDescription = "随机播放"
            }
            PlayMode.SEQUENTIAL -> {
                binding.btnPlayMode.setImageResource(R.drawable.ic_repeat_off_24)
                binding.btnPlayMode.contentDescription = "顺序播放"
            }
        }

        // Single instant update of progress if not tracking
        if (!isUserTrackingSeekBar) {
            updateProgressBar(info.positionMs, info.durationMs)
        }
    }

    private fun updateProgressBar(posMs: Long, durMs: Long) {
        if (durMs > 0) {
            val progress = ((posMs.toFloat() / durMs) * 1000).toInt().coerceIn(0, 1000)
            binding.sbProgress.progress = progress
        } else {
            binding.sbProgress.progress = 0
        }
    }

    // Zero UI overhead when screen is off or paused
    fun onScreenStateChanged(screenOn: Boolean) {
        isScreenOn = screenOn
        if (isScreenOn && isResumed && getService()?.isPlaying() == true) {
            startProgressPolling()
        } else {
            stopProgressPolling()
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true || !isScreenOn || !isResumed) return
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val service = getService()
                if (service != null && service.isPlaying() && !isUserTrackingSeekBar) {
                    val pos = service.getPosition()
                    val dur = service.getDuration()
                    updateProgressBar(pos, dur)
                }
                delay(1000L) // 1 Hz tick for ultra-low battery consumption
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
        if (isScreenOn && getService()?.isPlaying() == true) {
            startProgressPolling()
        }
    }

    override fun onPause() {
        stopProgressPolling()
        super.onPause()
    }

    override fun onDestroyView() {
        stopProgressPolling()
        _binding = null
        super.onDestroyView()
    }
}
