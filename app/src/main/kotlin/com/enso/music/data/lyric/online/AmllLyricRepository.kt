package com.enso.music.data.lyric.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.parser.TtmlParser
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

class AmllLyricRepository(private val context: Context) {

    companion object {
        private const val TAG = "AmllLyricRepo"
        private const val BASE_URL = "https://api.amll.dev"
        private const val TIMEOUT_MS = 5000
        private const val NEGATIVE_CACHE_PREFS = "amll_negative_cache"
        private const val COOLDOWN_DURATION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    private val ttmlCacheDir: File by lazy {
        File(context.filesDir, "ttml_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(NEGATIVE_CACHE_PREFS, Context.MODE_PRIVATE)
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            @Suppress("DEPRECATION")
            val activeInfo = cm.activeNetworkInfo
            if (activeInfo != null && activeInfo.isConnectedOrConnecting) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork
                if (net != null) {
                    val caps = cm.getNetworkCapabilities(net)
                    if (caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        return true
                    }
                }
            }
            // In emulator or special wear environment, fall back to optimistic true
            true
        } catch (e: Exception) {
            true // Optimistically proceed with HTTP if check fails
        }
    }

    fun getCacheFile(songPath: String): File {
        val hash = hashString(songPath)
        return File(ttmlCacheDir, "lyric_$hash.ttml")
    }

    /**
     * Searches for TTML lyrics on AMLL server and saves to persistent storage upon hit.
     */
    fun fetchAndCacheTtml(song: SongEntity, forceRefresh: Boolean = false): LyricData? {
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

        // 2. Check network connectivity
        if (!isNetworkConnected()) {
            Log.w(TAG, "No network connection detected. Skipping online fetch.")
            return null
        }

        // 3. Negative cache cooldown check
        val negKey = generateSongKey(song)
        val lastMissTime = prefs.getLong(negKey, 0L)
        val now = System.currentTimeMillis()
        if (!forceRefresh && lastMissTime > 0 && (now - lastMissTime) < COOLDOWN_DURATION_MS) {
            Log.d(TAG, "Song '$negKey' is in negative cache cooldown. Skipping online fetch.")
            return null
        }

        // 4. Perform search query
        try {
            val ttmlContent = searchTtmlFromAmll(song)
            if (!ttmlContent.isNullOrBlank()) {
                Log.i(TAG, "Successfully acquired TTML lyric for '${song.title}' (${ttmlContent.length} chars)")
                // Persistent write
                targetCacheFile.parentFile?.mkdirs()
                targetCacheFile.writeText(ttmlContent, Charsets.UTF_8)
                // Clear negative cache
                prefs.edit().remove(negKey).apply()
                return TtmlParser.parse(ttmlContent)
            } else {
                Log.i(TAG, "No TTML lyric found on AMLL for '${song.title}'")
                // Negative cache mark
                prefs.edit().putLong(negKey, now).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AMLL request failed: ${e.message}", e)
        }

        return null
    }

    private fun searchTtmlFromAmll(song: SongEntity): String? {
        val rawTitle = song.title.trim()
        val rawArtist = song.artist.split('/', '&', ',', ';').firstOrNull()?.trim() ?: song.artist.trim()
        val rawAlbum = song.album.trim()

        val cleanTitle = cleanSearchKeyword(rawTitle)
        val cleanArtist = cleanSearchKeyword(rawArtist)
        val cleanAlbum = cleanSearchKeyword(rawAlbum)

        Log.i(TAG, "Searching AMLL for rawTitle='$rawTitle', cleanTitle='$cleanTitle', artist='$rawArtist'")

        // Strategy 1: Exact Raw Title + Main Artist (e.g. preserves 'world.execute (me) ;')
        var result = executeAmllSearch(rawTitle, rawArtist, rawAlbum, song.duration)
        if (result != null) return result

        // Strategy 2: Exact Raw Title only (in case artist spelling differs, e.g. Mili vs ミリー)
        if (rawArtist.isNotBlank() && rawArtist != "未知歌手") {
            Log.d(TAG, "Strategy 2: Retrying with raw title only: '$rawTitle'")
            result = executeAmllSearch(rawTitle, "", "", song.duration)
            if (result != null) return result
        }

        // Strategy 3: Cleaned Title + Artist (strips (Live), [Remix], etc.)
        if (cleanTitle != rawTitle && cleanTitle.isNotBlank()) {
            Log.d(TAG, "Strategy 3: Retrying with cleaned title: '$cleanTitle' + '$cleanArtist'")
            result = executeAmllSearch(cleanTitle, cleanArtist, cleanAlbum, song.duration)
            if (result != null) return result

            // Strategy 4: Cleaned Title only
            Log.d(TAG, "Strategy 4: Retrying with cleaned title only: '$cleanTitle'")
            result = executeAmllSearch(cleanTitle, "", "", song.duration)
            if (result != null) return result
        }

        // Strategy 5: Trim trailing punctuation variant (e.g. 'world.execute')
        val trimmedPunctTitle = rawTitle.trimEnd(';', ' ', '-', ':', '；', '—', '.')
        if (trimmedPunctTitle != rawTitle && trimmedPunctTitle != cleanTitle && trimmedPunctTitle.isNotBlank()) {
            Log.d(TAG, "Strategy 5: Retrying with trimmed punctuation title: '$trimmedPunctTitle'")
            result = executeAmllSearch(trimmedPunctTitle, rawArtist, "", song.duration)
            if (result != null) return result
            result = executeAmllSearch(trimmedPunctTitle, "", "", song.duration)
            if (result != null) return result
        }

        // Strategy 6: LrcLib compatibility fallback
        return tryLrcLibFallback(song, cleanTitle, cleanArtist, cleanAlbum)
    }

    private fun executeAmllSearch(
        title: String,
        artist: String,
        album: String,
        songDurationMs: Long
    ): String? {
        val queryParams = StringBuilder("musicName=${encodeParam(title)}&page=1&pageSize=5")
        if (artist.isNotBlank() && artist != "未知歌手") {
            queryParams.append("&artistName=${encodeParam(artist)}")
        }
        if (album.isNotBlank() && album != "未知专辑") {
            queryParams.append("&albumName=${encodeParam(album)}")
        }

        val searchUrl = "$BASE_URL/v1/lyrics/search?$queryParams"
        Log.d(TAG, "HTTP GET search: $searchUrl")
        val jsonResponse = httpGet(searchUrl) ?: return null

        return parseAmllSearchResponse(jsonResponse, title, songDurationMs)
    }

    fun parseAmllSearchResponse(jsonResponse: String, queryTitle: String, songDurationMs: Long): String? {
        return try {
            val rootObj = JSONObject(jsonResponse)
            val dataArray = when {
                rootObj.optJSONArray("data") != null -> rootObj.optJSONArray("data")
                rootObj.optJSONObject("data")?.optJSONArray("items") != null -> rootObj.optJSONObject("data")?.optJSONArray("items")
                rootObj.optJSONArray("items") != null -> rootObj.optJSONArray("items")
                else -> null
            } ?: return null

            Log.d(TAG, "Search response items count: ${dataArray.length()}")
            val localDurationSec = (songDurationMs / 1000).toInt()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val itemDuration = item.optInt("duration", 0)

                // If duration is provided on item, perform relaxed tolerance check (up to 25s)
                if (localDurationSec > 0 && itemDuration > 0) {
                    if (abs(localDurationSec - itemDuration) > 25) {
                        Log.d(TAG, "Item duration $itemDuration mismatch with local $localDurationSec (> 25s), skipping.")
                        continue
                    }
                }

                // Check inline lyrics/ttml
                val inlineTtml = item.optString("lyrics", "").ifBlank {
                    item.optString("ttml", "").ifBlank { item.optString("content", "") }
                }
                if (inlineTtml.isNotBlank() && inlineTtml.contains("<tt", ignoreCase = true)) {
                    Log.i(TAG, "Found inline TTML in search item #$i")
                    return inlineTtml
                }

                // Otherwise fetch detail by id
                val id = item.optString("id", "")
                if (id.isNotBlank()) {
                    val detailUrl = "$BASE_URL/v1/lyrics/get?id=${encodeParam(id)}"
                    Log.d(TAG, "Fetching full detail: $detailUrl")
                    val detailResp = httpGet(detailUrl)
                    if (detailResp != null) {
                        val detailObj = JSONObject(detailResp)
                        val detailData = detailObj.optJSONObject("data") ?: detailObj
                        val fetched = detailData.optString("lyrics", "").ifBlank {
                            detailData.optString("ttml", "").ifBlank {
                                detailData.optString("content", "")
                            }
                        }
                        if (fetched.isNotBlank() && fetched.contains("<tt", ignoreCase = true)) {
                            Log.i(TAG, "Successfully fetched TTML by ID: $id")
                            return fetched
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

    private fun tryLrcLibFallback(song: SongEntity, title: String, artist: String, album: String): String? {
        return try {
            val queryParams = StringBuilder("track_name=${encodeParam(title)}")
            if (artist.isNotBlank() && artist != "未知歌手") {
                queryParams.append("&artist_name=${encodeParam(artist)}")
            }
            if (album.isNotBlank() && album != "未知专辑") {
                queryParams.append("&album_name=${encodeParam(album)}")
            }
            val durSec = (song.duration / 1000).toInt()
            if (durSec > 0) {
                queryParams.append("&duration=$durSec")
            }

            val url = "$BASE_URL/v1/lrclib/get?$queryParams"
            val resp = httpGet(url) ?: return null
            val obj = JSONObject(resp)
            val ttml = obj.optString("lyrics", "").ifBlank {
                obj.optString("ttml", "").ifBlank { obj.optString("content", "") }
            }
            if (ttml.isNotBlank() && ttml.contains("<tt", ignoreCase = true)) {
                ttml
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "EnsoMusic-Watch/1.0")
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun cleanSearchKeyword(input: String): String {
        val stripped = input.replace(Regex("""\s*[\(\[\{（【].*?[\)\]\}）】]"""), "")
            .trimEnd(';', ' ', '-', ':', '；', '—')
            .trim()
        return if (stripped.isNotBlank()) stripped else input.trim()
    }

    private fun encodeParam(param: String): String {
        return try {
            URLEncoder.encode(param, "UTF-8")
        } catch (e: Exception) {
            param
        }
    }

    private fun generateSongKey(song: SongEntity): String {
        return "${song.title}_${song.artist}_${song.duration / 1000}"
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
