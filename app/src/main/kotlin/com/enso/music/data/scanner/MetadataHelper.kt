package com.enso.music.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import com.enso.music.data.cover.CoverManager
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.util.EncodingUtils
import com.enso.music.data.util.PinyinInitialHelper
import java.io.File

class MetadataHelper(private val context: Context) {

    private val coverManager = CoverManager.getInstance(context)

    fun parseSong(file: File): SongEntity {
        val path = file.absolutePath
        val nameWithoutExt = file.nameWithoutExtension
        val size = file.length()
        val lastModified = file.lastModified()

        var rawTitle: String? = null
        var rawArtist: String? = null
        var rawAlbum: String? = null
        var rawTrack: String? = null
        var durationMs = 0L
        // Read the embedded picture from this retriever so CoverManager does
        // not have to open the same file a second time.
        var embeddedPicture: ByteArray? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
            embeddedPicture = try {
                retriever.embeddedPicture
            } catch (ignored: Exception) {
                null
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }

        // Clean & fix encodings
        val title = EncodingUtils.fixEncoding(rawTitle).ifBlank { nameWithoutExt }
        val artist = EncodingUtils.fixEncoding(rawArtist).ifBlank { "未知歌手" }
        val album = EncodingUtils.fixEncoding(rawAlbum).ifBlank { "未知专辑" }
        val trackNumber = parseTrackNumber(rawTrack, nameWithoutExt)

        val sortInitial = PinyinInitialHelper.getInitial(title)
        val sortKey = PinyinInitialHelper.getSortKey(title)

        // Extract thumbnail to cache
        val coverPath = coverManager.extractCoverToCache(path, embeddedPicture)

        return SongEntity(
            path = path,
            title = title,
            artist = artist,
            rawArtist = artist,
            album = album,
            duration = durationMs,
            trackNumber = trackNumber,
            size = size,
            lastModified = lastModified,
            coverPath = coverPath,
            sortKey = sortKey,
            sortInitial = sortInitial
        )
    }

    fun parseTrackNumber(trackStr: String?, fileNameWithoutExt: String): Int {
        // 1. Try parsing from metadata tag (handles "1", "01", "1/12", "01/10", "Track 01")
        if (!trackStr.isNullOrBlank()) {
            val trimmed = trackStr.trim()
            val slashIndex = trimmed.indexOf('/')
            val numPart = if (slashIndex != -1) trimmed.substring(0, slashIndex).trim() else trimmed
            val match = Regex("""\d+""").find(numPart)
            if (match != null) {
                val num = match.value.toIntOrNull()
                if (num != null && num > 0) {
                    return num
                }
            }
        }

        // 2. Fallback: extract leading track number from filename (e.g. "01. Song", "02 - Song", "1_Song", "1-03 Song")
        val fileMatch = Regex("""^(?:(?:CD|cd|Disc|disc)?\s*\d+[-_])?(\d{1,3})(?:[\s._\-\–]|$)""").find(fileNameWithoutExt.trim())
        if (fileMatch != null) {
            val num = fileMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0) {
                return num
            }
        }

        return 0
    }

    fun parseTrackNumberOnly(file: File): Int {
        var rawTrack: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            rawTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        } catch (ignored: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
        return parseTrackNumber(rawTrack, file.nameWithoutExtension)
    }
}
