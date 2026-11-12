package com.enso.music.data.lyric.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.util.ArtistParser
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.parser.TtmlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AmllLyricRepository(private val context: Context) {

    companion object {
        private const val TAG = "AmllLyricRepo"
        internal const val BASE_URL = "https://api.amll.dev"
        private const val TIMEOUT_MS = 5000
        /** Overall wall-clock budget for one song lookup (all strategies + detail GETs). */
        private const val LOOKUP_BUDGET_MS = 12_000L
        private const val NEGATIVE_CACHE_PREFS = "amll_negative_cache"
        private const val COOLDOWN_DURATION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        /** Negative-cache pruning runs at most once per process. */
        private val negativeCachePruned = AtomicBoolean(false)
    }

    private val ttmlCacheDir: File by lazy {
        File(context.filesDir, "ttml_cache_v2").apply {
            if (!exists()) mkdirs()
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(NEGATIVE_CACHE_PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Returns false when there is no active network or the active network has no
     * INTERNET capability. Only if the check itself fails do we optimistically return true.
     */
    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true // cannot check at all -> optimistic
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // Optimistically proceed with HTTP if the check itself fails
        }
    }

    fun getCacheFile(songPath: String): File {
        val hash = hashString(songPath)
        return File(ttmlCacheDir, "lyric_$hash.ttml")
    }

    /**
     * Searches for TTML lyrics on AMLL server and saves to persistent storage upon hit.
     *
     * Cancellable: checks for cancellation between requests and aborts an in-flight
     * connection when the calling coroutine is cancelled. Runs on [Dispatchers.IO].
     */
    suspend fun fetchAndCacheTtml(song: SongEntity, forceRefresh: Boolean = false): LyricData? =
        withContext(Dispatchers.IO) {
            fetchAndCacheTtmlInternal(song, forceRefresh)
        }

    private suspend fun fetchAndCacheTtmlInternal(song: SongEntity, forceRefresh: Boolean): LyricData? {
        val targetCacheFile = getCacheFile(song.path)
        Log.i(TAG, "fetchAndCacheTtml called for '${song.title}' by '${song.artist}', forceRefresh=$forceRefresh")

        // 1. If not forcing refresh and cache already exists, load cached directly
        if (!forceRefresh && targetCacheFile.exists() && targetCacheFile.length() > 0) {
            try {
                val text = targetCacheFile.readText(Charsets.UTF_8)
                val parsed = TtmlParser.parse(text)
                if (parsed.isNotEmpty()) {
                    Log.i(TAG, "Loaded lyric directly from local cache for '${song.title}'")
                    return parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading cached ttml: ${e.message}")
            }
        }

        val now = System.currentTimeMillis()
        pruneNegativeCacheOnce(now)

        // 2. Negative cache cooldown check (cheap, no radio)
        val negKey = generateSongKey(song)
        if (!forceRefresh) {
            val lastMissTime = try {
                prefs.getLong(negKey, 0L)
            } catch (e: ClassCastException) {
                0L
            }
            if (NegativeCachePolicy.isInCooldown(lastMissTime, now, COOLDOWN_DURATION_MS)) {
                Log.d(TAG, "Song '$negKey' is in negative cache cooldown. Skipping online fetch.")
                return null
            }
        }

        // 3. Check network connectivity
        if (!isNetworkConnected()) {
            Log.w(TAG, "No network connection detected. Skipping online fetch.")
            return null
        }

        // 4. Perform the budgeted, de-duplicated, cancellable lookup
        val outcome = try {
            AmllLookup(
                fetch = { url, timeoutMs -> httpGet(url, timeoutMs) },
                budget = LookupBudget(totalMs = LOOKUP_BUDGET_MS, maxRequestTimeoutMs = TIMEOUT_MS),
                log = { Log.d(TAG, it) }
            ).run(song.title, song.artist, song.album, song.duration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AMLL lookup crashed: ${e.message}", e)
            LookupOutcome.Failed("unexpected: ${e.message}")
        }

        when (outcome) {
            is LookupOutcome.Found -> {
                val ttmlContent = outcome.ttml
                Log.i(TAG, "Successfully acquired TTML lyric for '${song.title}' (${ttmlContent.length} chars)")
                return try {
                    targetCacheFile.parentFile?.mkdirs()
                    targetCacheFile.writeText(ttmlContent, Charsets.UTF_8)
                    prefs.edit().remove(negKey).apply()
                    TtmlParser.parse(ttmlContent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist/parse fetched TTML: ${e.message}", e)
                    null
                }
            }
            LookupOutcome.NotFound -> {
                // The server definitively answered "no match" for every strategy.
                Log.i(TAG, "No TTML lyric found on AMLL for '${song.title}', caching miss")
                prefs.edit().putLong(negKey, now).apply()
            }
            is LookupOutcome.Failed -> {
                // Transient failure: do NOT poison the negative cache.
                Log.w(TAG, "AMLL lookup for '${song.title}' failed (${outcome.reason}); not caching miss")
            }
        }
        return null
    }

    private fun pruneNegativeCacheOnce(now: Long) {
        if (!negativeCachePruned.compareAndSet(false, true)) return
        try {
            val stale = NegativeCachePolicy.staleKeys(prefs.all, now, COOLDOWN_DURATION_MS)
            if (stale.isNotEmpty()) {
                val editor = prefs.edit()
                stale.forEach { editor.remove(it) }
                editor.apply()
                Log.d(TAG, "Pruned ${stale.size} stale negative-cache entries")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Negative cache prune failed: ${e.message}")
        }
    }

    /**
     * Legacy synchronous parser kept for API compatibility. Picks the first matching
     * item (duration tolerance 25s) with inline TTML, otherwise fetches the item detail
     * by id with a blocking request. Must not be called on the main thread.
     */
    fun parseAmllSearchResponse(jsonResponse: String, queryTitle: String, songDurationMs: Long): String? {
        return try {
            val candidates = AmllResponseParsing.selectCandidates(jsonResponse, songDurationMs)
            Log.d(TAG, "Search response candidates: ${candidates.size} (query '$queryTitle')")
            for (candidate in candidates) {
                when (candidate) {
                    is AmllCandidate.Inline -> return candidate.ttml
                    is AmllCandidate.ById -> {
                        val result = blockingGet(AmllLookup.detailUrl(candidate.id), TIMEOUT_MS, null)
                        if (result is HttpResult.Success) {
                            // As before, a malformed detail body ends the parse (outer catch).
                            val fetched = AmllResponseParsing.extractDetailTtml(result.body)
                            if (fetched != null) {
                                Log.i(TAG, "Successfully fetched TTML by ID: ${candidate.id}")
                                return fetched
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing search response: ${e.message}")
            null
        }
    }

    /**
     * Cancellable GET: bounded by [timeoutMs] overall, and the underlying connection is
     * disconnected if the calling coroutine is cancelled (or the hard timeout fires).
     */
    private suspend fun httpGet(urlString: String, timeoutMs: Int): HttpResult {
        Log.d(TAG, "HTTP GET: $urlString (timeout ${timeoutMs}ms)")
        return withTimeoutOrNull(timeoutMs.toLong()) {
            coroutineScope {
                val canceller = ConnectionCanceller()
                // UNDISPATCHED so the finally-block is guaranteed to be armed before the
                // blocking request starts.
                val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        canceller.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) { blockingGet(urlString, timeoutMs, canceller) }
                } finally {
                    watchdog.cancel()
                }
            }
        } ?: HttpResult.Failure("hard timeout after ${timeoutMs}ms", networkLevel = true)
    }

    private fun blockingGet(urlString: String, timeoutMs: Int, canceller: ConnectionCanceller?): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "EnsoMusic-Watch/1.0")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            connection = conn
            if (canceller != null && !canceller.attach(conn)) {
                return HttpResult.Failure("cancelled", networkLevel = true)
            }
            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                HttpResult.Success(responseCode, body)
            } else {
                try {
                    conn.errorStream?.close()
                } catch (ignored: Exception) {
                }
                HttpClassifier.classifyStatus(responseCode)
            }
        } catch (e: Exception) {
            HttpClassifier.classifyException(e)
        } finally {
            canceller?.detach()
            connection?.disconnect()
        }
    }

    private fun generateSongKey(song: SongEntity): String {
        return "v2_${song.title}_${song.artist}_${song.duration / 1000}"
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Lets another thread abort a blocking [HttpURLConnection]. */
internal class ConnectionCanceller {
    private var connection: HttpURLConnection? = null
    private var cancelled = false

    /** Returns false if cancellation already happened (caller must abort). */
    @Synchronized
    fun attach(conn: HttpURLConnection): Boolean {
        if (cancelled) return false
        connection = conn
        return true
    }

    @Synchronized
    fun detach() {
        connection = null
    }

    fun cancel() {
        val conn = synchronized(this) {
            cancelled = true
            connection.also { connection = null }
        }
        try {
            conn?.disconnect()
        } catch (ignored: Exception) {
        }
    }
}

// ---------------------------------------------------------------------------------------
// Pure, Android-free logic below (unit-testable on the JVM).
// ---------------------------------------------------------------------------------------

/** Result of a single HTTP request. */
internal sealed class HttpResult {
    /** 2xx response with body. */
    data class Success(val code: Int, val body: String) : HttpResult()

    /** Server answered definitively that the resource doesn't exist (e.g. 404). */
    data class Miss(val code: Int) : HttpResult()

    /** Request failed (IO error, timeout, 5xx, rate-limit...). Must not be cached negatively. */
    data class Failure(val reason: String, val networkLevel: Boolean, val code: Int? = null) : HttpResult() {
        /**
         * Whether the remaining strategies should be skipped: network-level errors (the next
         * request won't fare better), rate limiting, and anything without an HTTP status
         * (e.g. budget exhaustion). A plain 5xx on one query is not fatal.
         */
        val abortsLookup: Boolean get() = networkLevel || code == null || code == 429
    }
}

internal object HttpClassifier {
    /** Classifies a non-exceptional HTTP status code (2xx bodies are handled by the caller). */
    fun classifyStatus(code: Int): HttpResult = when {
        code in 200..299 -> HttpResult.Success(code, "")
        // 408 Request Timeout / 429 Too Many Requests are transient, not a "no match".
        code == 408 || code == 429 -> HttpResult.Failure("HTTP $code", networkLevel = false, code = code)
        // 404, 410 and other client errors: the server answered; there's nothing for this query.
        code in 400..499 -> HttpResult.Miss(code)
        // 5xx, unexpected 1xx/3xx, or garbage (-1): treat as failure.
        else -> HttpResult.Failure("HTTP $code", networkLevel = false, code = code)
    }

    /**
     * Any exception while connecting/reading (IOException, SocketTimeoutException,
     * UnknownHostException, SecurityException...) is a network-level failure.
     */
    fun classifyException(e: Throwable): HttpResult.Failure = when (e) {
        is SocketTimeoutException -> HttpResult.Failure("timeout: ${e.message}", networkLevel = true)
        else -> HttpResult.Failure("${e.javaClass.simpleName}: ${e.message}", networkLevel = true)
    }
}

/** Outcome of a whole lookup across all strategies. */
internal sealed class LookupOutcome {
    data class Found(val ttml: String) : LookupOutcome()

    /** Every attempted request was answered definitively without a match. Safe to cache negatively. */
    object NotFound : LookupOutcome()

    /** Lookup was aborted (network failure, server error, budget exhausted). Do not cache negatively. */
    data class Failed(val reason: String) : LookupOutcome()
}

/**
 * Wall-clock budget and URL de-duplication for a single lookup.
 * Not thread-safe; one instance per lookup, used sequentially.
 */
internal class LookupBudget(
    totalMs: Long,
    private val maxRequestTimeoutMs: Int,
    private val minRequestTimeoutMs: Int = 1000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val deadline = clock() + totalMs
    private val triedUrls = HashSet<String>()

    fun remainingMs(): Long = max(0L, deadline - clock())

    /** True if there is not enough time left for a meaningful request. */
    fun isExhausted(): Boolean = remainingMs() < minRequestTimeoutMs

    /** Timeout to use for the next request, clipped to the remaining budget; 0 if exhausted. */
    fun nextRequestTimeoutMs(): Int {
        val remaining = remainingMs()
        if (remaining < minRequestTimeoutMs) return 0
        return min(maxRequestTimeoutMs.toLong(), remaining).toInt()
    }

    /** Returns true the first time [url] is seen in this lookup, false for duplicates. */
    fun markTried(url: String): Boolean = triedUrls.add(url)
}

internal object NegativeCachePolicy {
    fun isInCooldown(lastMissTime: Long, now: Long, cooldownMs: Long): Boolean =
        lastMissTime in 1..now && (now - lastMissTime) < cooldownMs

    /** Keys whose entries are expired, in the future (clock change) or not a timestamp at all. */
    fun staleKeys(entries: Map<String, *>, now: Long, cooldownMs: Long): List<String> =
        entries.mapNotNull { (key, value) ->
            val ts = value as? Long
            if (ts == null || !isInCooldown(ts, now, cooldownMs)) key else null
        }
}

internal sealed class AmllCandidate {
    data class Inline(val ttml: String) : AmllCandidate()
    data class ById(val id: String) : AmllCandidate()
}

internal object AmllResponseParsing {
    private const val DURATION_TOLERANCE_SEC = 25

    /**
     * Converts a search response into an ordered list of things to try. Mirrors the
     * original matching: skip items whose duration differs by > 25s (when both known);
     * take inline TTML if present (and stop there), else the item's id for a detail GET.
     *
     * @throws JSONException if the body isn't a JSON object (e.g. captive portal page).
     * A valid JSON object without a recognised items array yields an empty list.
     */
    fun selectCandidates(jsonResponse: String, songDurationMs: Long, title: String? = null, artist: String? = null): List<AmllCandidate> {
        val rootObj = JSONObject(jsonResponse)
        val dataArray: JSONArray = rootObj.optJSONArray("data")
            ?: rootObj.optJSONObject("data")?.optJSONArray("items")
            ?: rootObj.optJSONArray("items")
            ?: return emptyList()

        val localDurationSec = (songDurationMs / 1000).toInt()
        val result = ArrayList<AmllCandidate>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            if (title != null && !matchesSong(item, title, artist.orEmpty())) continue
            val itemDuration = item.optInt("duration", 0)
            if (localDurationSec > 0 && itemDuration > 0 &&
                abs(localDurationSec - itemDuration) > DURATION_TOLERANCE_SEC
            ) continue

            val inline = extractTtml(item)
            if (inline != null) {
                result.add(AmllCandidate.Inline(inline))
                return result // original code returned the first inline hit immediately
            }
            val id = item.optString("id", "")
            if (id.isNotBlank()) result.add(AmllCandidate.ById(id))
        }
        return result
    }

    private fun normalized(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun names(obj: JSONObject, arrayKey: String, vararg stringKeys: String): List<String> {
        val array = obj.optJSONArray(arrayKey)
        val result = mutableListOf<String>()
        if (array != null) for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            if (value.isNotBlank()) result.add(value)
        }
        for (key in stringKeys) obj.optString(key, "").takeIf { it.isNotBlank() }?.let(result::add)
        return result
    }

    fun matchesSong(obj: JSONObject, title: String, artist: String): Boolean {
        val expectedTitle = normalized(AmllLookup.cleanSearchKeyword(title))
        val expectedArtists = ArtistParser.parse(artist)
            .filterNot { it == "未知歌手" }.map(::normalized).filter { it.isNotEmpty() }.toSet()
        if (expectedTitle.isEmpty() || expectedArtists.isEmpty()) return false
        val titleMatches = names(obj, "musicNames", "trackName", "name")
            .any { normalized(AmllLookup.cleanSearchKeyword(it)) == expectedTitle }
        val artistMatches = names(obj, "artistNames", "artistName")
            .flatMap { ArtistParser.parse(it) }.map(::normalized).any { it in expectedArtists }
        return titleMatches && artistMatches
    }

    /** @throws JSONException if the body isn't a JSON object. */
    fun extractDetailTtml(jsonResponse: String): String? {
        val obj = JSONObject(jsonResponse)
        return extractTtml(obj.optJSONObject("data") ?: obj)
    }

    /** @throws JSONException if the body isn't a JSON object. */
    fun extractLrcLibTtml(jsonResponse: String, title: String, artist: String): String? {
        val obj = JSONObject(jsonResponse)
        return if (matchesSong(obj, title, artist)) extractTtml(obj) else null
    }

    fun extractTtml(obj: JSONObject): String? {
        val text = obj.optString("lyrics", "").ifBlank {
            obj.optString("ttml", "").ifBlank { obj.optString("content", "") }
        }
        return if (text.isNotBlank() && text.contains("<tt", ignoreCase = true)) text else null
    }
}

/** One AMLL search query of the strategy plan. */
internal data class AmllSearchQuery(val title: String, val artist: String, val album: String)

/**
 * Runs the search strategies for one song with a shared [LookupBudget]:
 * strategy order is preserved, duplicate URLs are skipped, and the lookup aborts on the
 * first failed request (network down / server error / budget exhausted).
 */
internal class AmllLookup(
    private val fetch: suspend (url: String, timeoutMs: Int) -> HttpResult,
    private val budget: LookupBudget,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val UNKNOWN_ARTIST = "未知歌手"
        private const val UNKNOWN_ALBUM = "未知专辑"

        fun cleanSearchKeyword(input: String): String {
            val stripped = input.replace(Regex("""\s*[\(\[\{（【].*?[\)\]\}）】]"""), "")
                .trimEnd(';', ' ', '-', ':', '；', '—')
                .trim()
            return if (stripped.isNotBlank()) stripped else input.trim()
        }

        fun mainArtist(artist: String): String =
            artist.split('/', '&', ',', ';').firstOrNull()?.trim() ?: artist.trim()

        /** Strategies 1-5 in their original order (strategy 6 is the LrcLib fallback). */
        fun buildSearchPlan(title: String, artist: String, album: String): List<AmllSearchQuery> {
            val rawTitle = title.trim()
            val rawArtist = mainArtist(artist)
            val rawAlbum = album.trim()
            val cleanTitle = cleanSearchKeyword(rawTitle)
            val cleanArtist = cleanSearchKeyword(rawArtist)
            val cleanAlbum = cleanSearchKeyword(rawAlbum)

            val plan = ArrayList<AmllSearchQuery>()
            // Strategy 1: Exact raw title + main artist (+ album)
            plan.add(AmllSearchQuery(rawTitle, rawArtist, rawAlbum))
            // Search without album before relaxing the artist constraint.
            if (rawAlbum.isNotBlank()) plan.add(AmllSearchQuery(rawTitle, rawArtist, ""))
            // Strategy 2: Raw title only (artist spelling may differ)
            if (rawArtist.isNotBlank() && rawArtist != UNKNOWN_ARTIST) {
                plan.add(AmllSearchQuery(rawTitle, "", ""))
            }
            // Strategies 3 + 4: Cleaned title (+ artist/album), then cleaned title only
            if (cleanTitle != rawTitle && cleanTitle.isNotBlank()) {
                plan.add(AmllSearchQuery(cleanTitle, cleanArtist, cleanAlbum))
                plan.add(AmllSearchQuery(cleanTitle, "", ""))
            }
            // Strategy 5: Trailing punctuation trimmed (e.g. 'world.execute')
            val trimmedPunctTitle = rawTitle.trimEnd(';', ' ', '-', ':', '；', '—', '.')
            if (trimmedPunctTitle != rawTitle && trimmedPunctTitle != cleanTitle && trimmedPunctTitle.isNotBlank()) {
                plan.add(AmllSearchQuery(trimmedPunctTitle, rawArtist, ""))
                plan.add(AmllSearchQuery(trimmedPunctTitle, "", ""))
            }
            return plan
        }

        fun searchUrl(q: AmllSearchQuery): String {
            val sb = StringBuilder("musicName=${encodeParam(q.title)}&page=1&pageSize=5")
            if (q.artist.isNotBlank() && q.artist != UNKNOWN_ARTIST) sb.append("&artistName=${encodeParam(q.artist)}")
            if (q.album.isNotBlank() && q.album != UNKNOWN_ALBUM) sb.append("&albumName=${encodeParam(q.album)}")
            return "${AmllLyricRepository.BASE_URL}/v1/lyrics/search?$sb"
        }

        fun detailUrl(id: String): String = "${AmllLyricRepository.BASE_URL}/v1/lyrics/get?id=${encodeParam(id)}"

        fun lrcLibUrl(title: String, artist: String, album: String, durationMs: Long): String {
            val sb = StringBuilder("track_name=${encodeParam(title)}")
            if (artist.isNotBlank() && artist != UNKNOWN_ARTIST) sb.append("&artist_name=${encodeParam(artist)}")
            if (album.isNotBlank() && album != UNKNOWN_ALBUM) sb.append("&album_name=${encodeParam(album)}")
            val durSec = (durationMs / 1000).toInt()
            if (durSec > 0) sb.append("&duration=$durSec")
            return "${AmllLyricRepository.BASE_URL}/v1/lrclib/get?$sb"
        }

        private fun encodeParam(param: String): String = try {
            URLEncoder.encode(param, "UTF-8")
        } catch (e: Exception) {
            param
        }
    }

    /** First non-fatal failure seen (e.g. a 5xx on one query). Prevents caching a miss. */
    private var softFailure: String? = null

    /** Thrown internally to abort the remaining strategies. */
    private class AbortLookup(val reason: String) : Exception(reason)

    suspend fun run(title: String, artist: String, album: String, durationMs: Long): LookupOutcome {
        softFailure = null
        return try {
            val found = runStrategies(title, artist, album, durationMs)
            when {
                found != null -> LookupOutcome.Found(found)
                softFailure != null -> LookupOutcome.Failed(softFailure!!)
                else -> LookupOutcome.NotFound
            }
        } catch (e: AbortLookup) {
            LookupOutcome.Failed(e.reason)
        }
    }

    private suspend fun runStrategies(title: String, artist: String, album: String, durationMs: Long): String? {
        val plan = buildSearchPlan(title, artist, album)
        log("Searching AMLL for '${title.trim()}' with ${plan.size} search strategies")
        for ((index, query) in plan.withIndex()) {
            log("Strategy ${index + 1}: '${query.title}' / '${query.artist}' / '${query.album}'")
            executeSearch(query, durationMs, title, artist)?.let { return it }
        }

        // Strategy 6: LrcLib compatibility fallback (cleaned keywords)
        val cleanTitle = cleanSearchKeyword(title.trim())
        val cleanArtist = cleanSearchKeyword(mainArtist(artist))
        val cleanAlbum = cleanSearchKeyword(album.trim())
        val body = requestBody(lrcLibUrl(cleanTitle, cleanArtist, cleanAlbum, durationMs)) ?: return null
        return parseOrAbort("lrclib") { AmllResponseParsing.extractLrcLibTtml(body, title, artist) }
    }

    private suspend fun executeSearch(query: AmllSearchQuery, durationMs: Long, title: String, artist: String): String? {
        val body = requestBody(searchUrl(query)) ?: return null
        val candidates = parseOrAbort("search") { AmllResponseParsing.selectCandidates(body, durationMs, title, artist) }
        for (candidate in candidates) {
            when (candidate) {
                is AmllCandidate.Inline -> return candidate.ttml
                is AmllCandidate.ById -> {
                    val detailBody = requestBody(detailUrl(candidate.id)) ?: continue
                    val ttml = parseOrAbort("detail") { AmllResponseParsing.extractDetailTtml(detailBody) }
                    if (ttml != null) {
                        log("Fetched TTML by id ${candidate.id}")
                        return ttml
                    }
                }
            }
        }
        return null
    }

    /**
     * A 2xx body that isn't JSON is most likely a captive portal / proxy page rather
     * than a real answer, so it aborts the lookup without caching a miss.
     */
    private inline fun <T> parseOrAbort(what: String, block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw AbortLookup("malformed $what response")
    }

    /**
     * Returns the body of a 2xx response, or null for a definite miss / duplicate URL /
     * non-fatal server error (recorded in [softFailure]). Aborts the whole lookup on
     * network-level failures, rate limiting, or budget exhaustion.
     */
    private suspend fun requestBody(url: String): String? {
        val result = request(url) ?: return null
        return when (result) {
            is HttpResult.Success -> result.body
            is HttpResult.Miss -> null
            is HttpResult.Failure -> {
                if (result.abortsLookup) throw AbortLookup(result.reason)
                log("Non-fatal failure for $url: ${result.reason}")
                if (softFailure == null) softFailure = result.reason
                null
            }
        }
    }

    /**
     * Performs one request. Returns null if [url] was already tried in this lookup
     * (its answer was a miss or an already-recorded soft failure, since hits return and
     * hard failures abort). Budget exhaustion is reported as a [HttpResult.Failure].
     */
    private suspend fun request(url: String): HttpResult? {
        currentCoroutineContext().ensureActive()
        val timeout = budget.nextRequestTimeoutMs()
        if (timeout <= 0) {
            return HttpResult.Failure("lookup time budget exhausted", networkLevel = false)
        }
        if (!budget.markTried(url)) {
            log("Skipping duplicate request: $url")
            return null
        }
        val result = fetch(url, timeout)
        // A cancelled request typically surfaces as an IO failure; report cancellation instead.
        currentCoroutineContext().ensureActive()
        return result
    }
}
