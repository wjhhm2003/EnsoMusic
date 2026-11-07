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
        /**
         * How long to wait after delivering the local result before going online. If the
         * user skips to another song within this window the Job is cancelled and no
         * network request is made.
         */
        private const val ONLINE_DEBOUNCE_MS = 700L

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
     * Loads lyric for the given song with two-stage callback (always on Dispatchers.Main):
     * 1. Returns local/cached lyric immediately.
     * 2. If local was not a TTML file, waits a short debounce, then fetches online AMLL
     *    TTML and upgrades if found.
     *
     * Cancelling the returned Job (e.g. on song change) stops the work, including the
     * debounce delay and any in-flight network request.
     */
    fun loadLyric(
        song: SongEntity,
        forceOnlineRefresh: Boolean = false,
        onLyricLoaded: (LyricData) -> Unit
    ): Job {
        val cacheKey = song.path

        return scope.launch {
            val hasPhysicalTtml = localExtractor.hasLocalTtmlFile(song.path)

            // Stage 1: memory cache, then local disk / embedded (zero network)
            var localResult: LyricData? = null
            var localDelivered = false
            if (!forceOnlineRefresh) {
                localResult = memoryCache.get(cacheKey)
                    ?: localExtractor.loadLocalLyric(song)?.also { memoryCache.put(cacheKey, it) }
                if (localResult != null) {
                    deliver(localResult, onLyricLoaded)
                    localDelivered = true
                    // A physical .ttml (sibling or cached download) is the best we can get.
                    if (hasPhysicalTtml) return@launch
                }
            }

            // Debounce: don't hit the network for songs the user is just skipping through.
            if (!forceOnlineRefresh) {
                delay(ONLINE_DEBOUNCE_MS)
            }

            // Stage 2: online search from AMLL TTML library (cancellable, runs on IO)
            val onlineTtml = amllRepository.fetchAndCacheTtml(song, forceRefresh = forceOnlineRefresh)
            ensureActive()
            if (onlineTtml != null && onlineTtml.isNotEmpty()) {
                memoryCache.put(cacheKey, onlineTtml)
                deliver(onlineTtml, onLyricLoaded)
                return@launch
            }

            // Online search failed / found nothing: fall back to local embedded/LRC lyrics.
            if (localDelivered) return@launch // already showing the local lyric; keep it

            val fallbackLocal = localResult
                ?: localExtractor.loadLocalLyric(song)
                // On a forced refresh that came back empty, keep whatever we had before.
                ?: memoryCache.get(cacheKey)
            if (fallbackLocal != null && fallbackLocal.isNotEmpty()) {
                memoryCache.put(cacheKey, fallbackLocal)
                deliver(fallbackLocal, onLyricLoaded)
            } else {
                deliver(LyricData.EMPTY, onLyricLoaded)
            }
        }
    }

    private suspend fun deliver(data: LyricData, onLyricLoaded: (LyricData) -> Unit) {
        withContext(Dispatchers.Main) {
            ensureActive()
            onLyricLoaded(data)
        }
    }

    fun clearCacheForSong(songPath: String) {
        memoryCache.remove(songPath)
    }
}
