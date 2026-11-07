package com.enso.music.service

import com.enso.music.data.entity.SongEntity
import kotlin.math.pow

enum class PlayMode {
    LOOP_ALL,   // 列表循环
    LOOP_ONE,   // 单曲循环
    SHUFFLE,    // 随机播放
    SEQUENTIAL; // 顺序播放

    /** Full cycle including SEQUENTIAL (used by persisted settings / tests). */
    fun next(): PlayMode {
        return when (this) {
            LOOP_ALL -> SHUFFLE
            SHUFFLE -> LOOP_ONE
            LOOP_ONE -> SEQUENTIAL
            SEQUENTIAL -> LOOP_ALL
        }
    }

    /** Cycle offered by the now-playing button: 列表循环 → 单曲循环 → 随机 → 列表循环. */
    fun nextForButton(): PlayMode {
        return when (this) {
            LOOP_ALL -> LOOP_ONE
            LOOP_ONE -> SHUFFLE
            SHUFFLE -> LOOP_ALL
            SEQUENTIAL -> LOOP_ALL
        }
    }

    val label: String
        get() = when (this) {
            LOOP_ALL -> "列表循环"
            LOOP_ONE -> "单曲循环"
            SHUFFLE -> "随机播放"
            SEQUENTIAL -> "顺序播放"
        }
}

data class PlaybackInfo(
    val currentSong: SongEntity? = null,
    val isPlaying: Boolean = false,
    /** True while the decoder for [currentSong] is still preparing. */
    val isPreparing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playMode: PlayMode = PlayMode.LOOP_ALL,
    val queueSize: Int = 0,
    val currentIndex: Int = -1,
    /** Short user-facing message for the most recent playback failure, if any. */
    val errorMessage: String? = null
)

/** Sleep timer state. [endAtElapsedMs] is SystemClock.elapsedRealtime based; 0 = off. */
data class SleepTimerState(
    val endAtElapsedMs: Long = 0L,
    val stopAfterCurrent: Boolean = false
) {
    val isActive: Boolean get() = endAtElapsedMs > 0L || stopAfterCurrent
}

object FineVolume {
    /** Perceptual volume curve: 0..100 % → linear gain. */
    fun gain(percent: Int): Float {
        val coerced = percent.coerceIn(0, 100)
        if (coerced == 0) return 0f
        return (coerced / 100.0).pow(1.8).toFloat()
    }
}
