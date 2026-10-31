package com.enso.music.service

import com.enso.music.data.entity.SongEntity

enum class PlayMode {
    LOOP_ALL,   // 列表循环
    LOOP_ONE,   // 单曲循环
    SHUFFLE,    // 随机播放
    SEQUENTIAL; // 顺序播放

    fun next(): PlayMode {
        return when (this) {
            LOOP_ALL -> SHUFFLE
            SHUFFLE -> LOOP_ONE
            LOOP_ONE -> SEQUENTIAL
            SEQUENTIAL -> LOOP_ALL
        }
    }
}

data class PlaybackInfo(
    val currentSong: SongEntity? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playMode: PlayMode = PlayMode.LOOP_ALL,
    val queueSize: Int = 0,
    val currentIndex: Int = -1
)
