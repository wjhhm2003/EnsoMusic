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

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            rawTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            rawArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            rawAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            rawTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
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
        val trackNumber = parseTrackNumber(rawTrack)

        val sortInitial = PinyinInitialHelper.getInitial(title)
        val sortKey = PinyinInitialHelper.getSortKey(title)

        // Extract thumbnail to cache
        val coverPath = coverManager.extractCoverToCache(path)

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

    private fun parseTrackNumber(trackStr: String?): Int {
        if (trackStr.isNullOrBlank()) return 0
        val slashIndex = trackStr.indexOf('/')
        val numberPart = if (slashIndex != -1) trackStr.substring(0, slashIndex).trim() else trackStr.trim()
        return numberPart.toIntOrNull() ?: 0
    }
}
