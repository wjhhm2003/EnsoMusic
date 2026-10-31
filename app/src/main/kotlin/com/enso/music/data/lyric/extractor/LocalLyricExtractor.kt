package com.enso.music.data.lyric.extractor

import android.content.Context
import com.enso.music.data.entity.SongEntity
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.parser.LrcParser
import com.enso.music.data.lyric.parser.TtmlParser
import com.enso.music.data.util.EncodingUtils
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest

class LocalLyricExtractor(private val context: Context) {

    private val ttmlCacheDir: File by lazy {
        File(context.filesDir, "ttml_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Resolves the cache file corresponding to a given song.
     */
    fun getCachedTtmlFile(songPath: String): File {
        val hash = hashString(songPath)
        return File(ttmlCacheDir, "lyric_$hash.ttml")
    }

    /**
     * Checks whether a physical .ttml file exists locally (sibling or cached).
     */
    fun hasLocalTtmlFile(songPath: String): Boolean {
        val audioFile = File(songPath)
        val parentDir = audioFile.parentFile
        val nameWithoutExt = audioFile.nameWithoutExtension

        if (parentDir != null && parentDir.exists()) {
            val siblingTtml = File(parentDir, "$nameWithoutExt.ttml")
            if (siblingTtml.exists() && siblingTtml.length() > 0) return true
        }

        val cachedTtml = getCachedTtmlFile(songPath)
        return cachedTtml.exists() && cachedTtml.length() > 0
    }

    /**
     * Loads lyric strictly from local disk or embedded metadata without network access.
     * Priority:
     * 1. Sibling .ttml file
     * 2. Cached .ttml file
     * 3. Sibling .lrc file
     * 4. Embedded lyrics (ID3v2 USLT/SYLT, FLAC VORBIS_COMMENT, M4A ©lyr)
     */
    fun loadLocalLyric(song: SongEntity): LyricData? {
        val audioFile = File(song.path)
        if (!audioFile.exists()) return null

        val parentDir = audioFile.parentFile
        val nameWithoutExt = audioFile.nameWithoutExtension

        // 1. Sibling .ttml
        if (parentDir != null && parentDir.exists()) {
            val siblingTtml = File(parentDir, "$nameWithoutExt.ttml")
            if (siblingTtml.exists() && siblingTtml.length() > 0) {
                try {
                    val content = siblingTtml.readText(Charsets.UTF_8)
                    val parsed = TtmlParser.parse(content)
                    if (parsed.isNotEmpty()) return parsed
                } catch (ignored: Exception) {
                }
            }
        }

        // 2. Cached .ttml in app storage
        val cachedTtml = getCachedTtmlFile(song.path)
        if (cachedTtml.exists() && cachedTtml.length() > 0) {
            try {
                val content = cachedTtml.readText(Charsets.UTF_8)
                val parsed = TtmlParser.parse(content)
                if (parsed.isNotEmpty()) return parsed
            } catch (ignored: Exception) {
            }
        }

        // 3. Sibling .lrc
        if (parentDir != null && parentDir.exists()) {
            val siblingLrc = File(parentDir, "$nameWithoutExt.lrc")
            if (siblingLrc.exists() && siblingLrc.length() > 0) {
                try {
                    val rawBytes = siblingLrc.readBytes()
                    val text = decodeBytesWithAutoCharset(rawBytes)
                    val parsed = parseContentAuto(text)
                    if (parsed.isNotEmpty()) return parsed
                } catch (ignored: Exception) {
                }
            }
        }

        // 4. Embedded lyrics from audio header metadata
        val embeddedText = extractEmbeddedLyricText(audioFile)
        if (!embeddedText.isNullOrBlank()) {
            val parsed = parseContentAuto(embeddedText)
            if (parsed.isNotEmpty()) return parsed
        }

        return null
    }

    fun parseContentAuto(text: String): LyricData {
        val trimmed = text.trim()
        return if (trimmed.contains("<tt", ignoreCase = true) && trimmed.contains("</tt>", ignoreCase = true)) {
            TtmlParser.parse(trimmed)
        } else {
            LrcParser.parse(trimmed)
        }
    }

    /**
     * Extracts embedded lyrics by parsing audio metadata container headers only.
     */
    private fun extractEmbeddedLyricText(file: File): String? {
        val ext = file.extension.lowercase()
        return try {
            when (ext) {
                "mp3" -> extractMp3Lyrics(file)
                "flac" -> extractFlacLyrics(file)
                "m4a", "aac", "mp4" -> extractM4aLyrics(file)
                "ogg" -> extractOggLyrics(file)
                else -> null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /**
     * Scans MP3 ID3v2 header for USLT or SYLT frames.
     */
    private fun extractMp3Lyrics(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return null
            val header = ByteArray(10)
            raf.readFully(header)

            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return null
            }

            val version = header[3].toInt() // 3 = ID3v2.3, 4 = ID3v2.4
            val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)

            val tagEnd = 10L + tagSize.coerceAtMost((raf.length() - 10).toInt())
            val frameHeaderSize = 10

            while (raf.filePointer + frameHeaderSize < tagEnd) {
                val frameHeader = ByteArray(10)
                raf.readFully(frameHeader)
                val frameId = String(frameHeader, 0, 4, Charsets.US_ASCII)
                if (frameId.isBlank() || frameId[0] == '\u0000') break

                val frameSize = if (version == 4) {
                    ((frameHeader[4].toInt() and 0x7F) shl 21) or
                            ((frameHeader[5].toInt() and 0x7F) shl 14) or
                            ((frameHeader[6].toInt() and 0x7F) shl 7) or
                            (frameHeader[7].toInt() and 0x7F)
                } else {
                    ((frameHeader[4].toInt() and 0xFF) shl 24) or
                            ((frameHeader[5].toInt() and 0xFF) shl 16) or
                            ((frameHeader[6].toInt() and 0xFF) shl 8) or
                            (frameHeader[7].toInt() and 0xFF)
                }

                if (frameSize <= 0 || raf.filePointer + frameSize > tagEnd) break

                if (frameId == "USLT") {
                    val frameData = ByteArray(frameSize)
                    raf.readFully(frameData)
                    return decodeId3UsltFrame(frameData)
                } else {
                    raf.skipBytes(frameSize)
                }
            }
        }
        return null
    }

    private fun decodeId3UsltFrame(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt()
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.UTF_8
        }

        // Bytes 1..3 are 3-character language code
        // After language is null-terminated content descriptor, followed by lyric text
        var descEnd = 4
        if (encoding == 1 || encoding == 2) {
            // 2-byte null terminator
            while (descEnd + 1 < data.size) {
                if (data[descEnd] == 0.toByte() && data[descEnd + 1] == 0.toByte()) {
                    descEnd += 2
                    break
                }
                descEnd += 2
            }
        } else {
            // 1-byte null terminator
            while (descEnd < data.size) {
                if (data[descEnd] == 0.toByte()) {
                    descEnd += 1
                    break
                }
                descEnd++
            }
        }

        if (descEnd < data.size) {
            val lyricBytes = data.copyOfRange(descEnd, data.size)
            val text = String(lyricBytes, charset)
            return EncodingUtils.fixEncoding(text)
        }
        return null
    }

    /**
     * Scans FLAC file metadata blocks for VORBIS_COMMENT with LYRICS or UNSYNCEDLYRICS.
     */
    private fun extractFlacLyrics(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
            ) {
                return null
            }

            var isLast = false
            while (!isLast && raf.filePointer + 4 <= raf.length()) {
                val blockHeader = ByteArray(4)
                raf.readFully(blockHeader)
                isLast = (blockHeader[0].toInt() and 0x80) != 0
                val blockType = blockHeader[0].toInt() and 0x7F
                val blockSize = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)

                if (blockSize < 0 || raf.filePointer + blockSize > raf.length()) break

                if (blockType == 4) { // VORBIS_COMMENT
                    val commentData = ByteArray(blockSize)
                    raf.readFully(commentData)
                    return parseVorbisComment(commentData)
                } else {
                    raf.skipBytes(blockSize)
                }
            }
        }
        return null
    }

    /**
     * Scans OGG files for Vorbis comments.
     */
    private fun extractOggLyrics(file: File): String? {
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16384)
            val read = fis.read(buf)
            if (read > 0) {
                val text = String(buf, 0, read, Charsets.ISO_8859_1)
                val regex = Regex("""(?i)(?:LYRICS|UNSYNCEDLYRICS)=([^=\x00-\x1F\x7F]+)""")
                val match = regex.find(text)
                if (match != null) {
                    val raw = match.groupValues[1]
                    return EncodingUtils.fixEncoding(raw)
                }
            }
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray): String? {
        if (data.size < 8) return null
        var offset = 0
        val vendorLen = readInt32LittleEndian(data, offset)
        offset += 4 + vendorLen
        if (offset + 4 > data.size) return null

        val userCommentListLen = readInt32LittleEndian(data, offset)
        offset += 4

        for (i in 0 until userCommentListLen) {
            if (offset + 4 > data.size) break
            val commentLen = readInt32LittleEndian(data, offset)
            offset += 4
            if (offset + commentLen > data.size || commentLen <= 0) break

            val commentStr = String(data, offset, commentLen, Charsets.UTF_8)
            offset += commentLen

            val eqIdx = commentStr.indexOf('=')
            if (eqIdx != -1) {
                val key = commentStr.substring(0, eqIdx).trim()
                if (key.equals("LYRICS", ignoreCase = true) || key.equals("UNSYNCEDLYRICS", ignoreCase = true)) {
                    val value = commentStr.substring(eqIdx + 1).trim()
                    if (value.isNotEmpty()) {
                        return EncodingUtils.fixEncoding(value)
                    }
                }
            }
        }
        return null
    }

    /**
     * Scans M4A boxes for '©lyr' atom inside 'moov.udta.meta.ilst'.
     */
    private fun extractM4aLyrics(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val maxScan = (raf.length()).coerceAtMost(2 * 1024 * 1024L) // 2MB max header scan
            var pos = 0L

            while (pos + 8 <= maxScan) {
                raf.seek(pos)
                val boxSize = raf.readInt().toLong() and 0xFFFFFFFFL
                val boxType = ByteArray(4)
                raf.readFully(boxType)
                val typeStr = String(boxType, Charsets.US_ASCII)

                if (boxSize < 8) break

                if (typeStr == "moov") {
                    return scanM4aContainer(raf, pos + 8, pos + boxSize)
                }
                pos += boxSize
            }
        }
        return null
    }

    private fun scanM4aContainer(raf: RandomAccessFile, start: Long, end: Long): String? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)

            if (size < 8 || pos + size > end) break

            if (typeStr == "udta" || typeStr == "meta" || typeStr == "ilst") {
                val headerOffset = if (typeStr == "meta") 12L else 8L
                val found = scanM4aContainer(raf, pos + headerOffset, pos + size)
                if (found != null) return found
            } else if (typeStr == "©lyr") {
                // Inside ©lyr, look for 'data' atom
                return extractM4aDataAtom(raf, pos + 8, pos + size)
            }
            pos += size
        }
        return null
    }

    private fun extractM4aDataAtom(raf: RandomAccessFile, start: Long, end: Long): String? {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.US_ASCII)

            if (size < 8 || pos + size > end) break

            if (typeStr == "data") {
                val dataLen = (size - 16).toInt() // skip 8 byte header + 8 byte data flags
                if (dataLen > 0) {
                    raf.seek(pos + 16)
                    val bytes = ByteArray(dataLen)
                    raf.readFully(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    return EncodingUtils.fixEncoding(text)
                }
            }
            pos += size
        }
        return null
    }

    private fun readInt32LittleEndian(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun decodeBytesWithAutoCharset(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // Try UTF-8
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) {
            return utf8
        }

        // Try GB18030 / GBK
        try {
            val gbk = String(bytes, Charset.forName("GB18030"))
            if (!gbk.contains('\uFFFD') && EncodingUtils.hasCjk(gbk)) {
                return gbk
            }
        } catch (ignored: Exception) {
        }

        return utf8
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
