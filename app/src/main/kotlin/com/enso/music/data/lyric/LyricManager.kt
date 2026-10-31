package com.enso.music.data.lyric

import android.content.Context
import androidx.collection.LruCache
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.lyric.extractor.LocalLyricExtractor
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.online.AmllLyricRepository
import kotlinx.coroutines.*

class LyricManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: LyricManager? = null

        fun getInstance(context: Context): LyricManager {
            return instance ?: synchronized(this) {
                instance ?: LyricManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val localExtractor = LocalLyricExtractor(context)
    private val amllRepository = AmllLyricRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Memory LRU cache for 8 recently parsed songs
    private val memoryCache = LruCache<String, LyricData>(8)

    /**
     * Loads lyric for the given song with two-stage callback:
     * 1. Returns local/cached lyric immediately.
     * 2. If local was not a TTML file, fetches online AMLL TTML in background and upgrades if found.
     */
    fun loadLyric(
        song: SongEntity,
        forceOnlineRefresh: Boolean = false,
        onLyricLoaded: (LyricData) -> Unit
    ): Job {
        val cacheKey = song.path

        return scope.launch {
            val hasPhysicalTtml = localExtractor.hasLocalTtmlFile(song.path)

            if (!forceOnlineRefresh) {
                val cachedInMemory = memoryCache.get(cacheKey)
                if (cachedInMemory != null) {
                    withContext(Dispatchers.Main) {
                        onLyricLoaded(cachedInMemory)
                    }
                    if (hasPhysicalTtml) {
                        return@launch
                    }
                }
            }

            // Stage 1: Try local first (zero latency)
            val localResult = if (!forceOnlineRefresh) localExtractor.loadLocalLyric(song) else null
            if (localResult != null) {
                memoryCache.put(cacheKey, localResult)
                withContext(Dispatchers.Main) {
                    onLyricLoaded(localResult)
                }
                // If local already has a physical .ttml file, we are done
                if (hasPhysicalTtml) {
                    return@launch
                }
            }

            // Stage 2: Background online search from AMLL TTML library
            val onlineTtml = amllRepository.fetchAndCacheTtml(song, forceRefresh = forceOnlineRefresh)
            if (onlineTtml != null && onlineTtml.isNotEmpty()) {
                memoryCache.put(cacheKey, onlineTtml)
                withContext(Dispatchers.Main) {
                    onLyricLoaded(onlineTtml)
                }
            } else {
                // Online search failed or timed out: Fall back to local embedded/LRC lyrics
                val fallbackLocal = localResult ?: localExtractor.loadLocalLyric(song)
                if (fallbackLocal != null && fallbackLocal.isNotEmpty()) {
                    memoryCache.put(cacheKey, fallbackLocal)
                    withContext(Dispatchers.Main) {
                        onLyricLoaded(fallbackLocal)
                    }
                } else if (localResult == null) {
                    withContext(Dispatchers.Main) {
                        onLyricLoaded(LyricData.EMPTY)
                    }
                }
            }
        }
    }

    fun clearCacheForSong(songPath: String) {
        memoryCache.remove(songPath)
    }
}
