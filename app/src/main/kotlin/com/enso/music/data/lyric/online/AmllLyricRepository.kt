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
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val activeInfo = cm.activeNetworkInfo
                activeInfo != null && activeInfo.isConnected
            }
        } catch (e: Exception) {
            false
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

        // 1. If not forcing refresh and cache already exists, load cached directly
        if (!forceRefresh && targetCacheFile.exists() && targetCacheFile.length() > 0) {
            try {
                val text = targetCacheFile.readText(Charsets.UTF_8)
                val parsed = TtmlParser.parse(text)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading cached ttml: ${e.message}")
            }
        }

        // 2. Check network connectivity
        if (!isNetworkConnected()) {
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
                // Persistent write
                targetCacheFile.writeText(ttmlContent, Charsets.UTF_8)
                // Clear negative cache
                prefs.edit().remove(negKey).apply()
                return TtmlParser.parse(ttmlContent)
            } else {
                // Negative cache mark
                prefs.edit().putLong(negKey, now).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AMLL request failed: ${e.message}")
        }

        return null
    }

    private fun searchTtmlFromAmll(song: SongEntity): String? {
        val cleanTitle = cleanSearchKeyword(song.title)
        val cleanArtist = cleanSearchKeyword(song.artist.split('/', '&', ',', ';').firstOrNull() ?: song.artist)
        val cleanAlbum = cleanSearchKeyword(song.album)

        val queryParams = StringBuilder("musicName=${encodeParam(cleanTitle)}&page=1&pageSize=5")
        if (cleanArtist.isNotBlank() && cleanArtist != "未知歌手") {
            queryParams.append("&artistName=${encodeParam(cleanArtist)}")
        }
        if (cleanAlbum.isNotBlank() && cleanAlbum != "未知专辑") {
            queryParams.append("&albumName=${encodeParam(cleanAlbum)}")
        }

        val searchUrl = "$BASE_URL/v1/lyrics/search?$queryParams"
        val jsonResponse = httpGet(searchUrl) ?: return tryLrcLibFallback(song, cleanTitle, cleanArtist, cleanAlbum)

        val rootObj = JSONObject(jsonResponse)
        val dataArray = rootObj.optJSONArray("data") ?: return null

        val localDurationSec = (song.duration / 1000).toInt()

        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val itemDuration = item.optInt("duration", 0)

            // Duration tolerance check (+/- 8s) if durations are known
            if (localDurationSec > 0 && itemDuration > 0) {
                if (abs(localDurationSec - itemDuration) > 8) {
                    continue
                }
            }

            // Extract inline ttml if available
            val ttml = item.optString("ttml", "")
            if (ttml.isNotBlank() && ttml.contains("<tt", ignoreCase = true)) {
                return ttml
            }

            // Otherwise fetch full details by ID
            val id = item.optString("id", "")
            if (id.isNotBlank()) {
                val detailUrl = "$BASE_URL/v1/lyrics/get?id=${encodeParam(id)}"
                val detailResp = httpGet(detailUrl)
                if (detailResp != null) {
                    val detailObj = JSONObject(detailResp)
                    val detailData = detailObj.optJSONObject("data") ?: detailObj
                    val fetchedTtml = detailData.optString("ttml", "")
                    if (fetchedTtml.isNotBlank() && fetchedTtml.contains("<tt", ignoreCase = true)) {
                        return fetchedTtml
                    }
                }
            }
        }

        // If primary search returned no valid TTML, attempt lrclib compatibility fallback
        return tryLrcLibFallback(song, cleanTitle, cleanArtist, cleanAlbum)
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
            val ttml = obj.optString("ttml", "")
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
        return input.replace(Regex("""\s*[\(\[\{（【].*?[\)\]\}）】]"""), "").trim()
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
