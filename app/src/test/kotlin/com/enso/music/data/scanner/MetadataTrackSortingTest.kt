package com.enso.music.data.scanner

import com.enso.music.data.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataTrackSortingTest {

    private fun parseTrackNumber(trackStr: String?, fileNameWithoutExt: String): Int {
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

        val fileMatch = Regex("""^(?:(?:CD|cd|Disc|disc)?\s*\d+[-_])?(\d{1,3})(?:[\s._\-\–]|$)""").find(fileNameWithoutExt.trim())
        if (fileMatch != null) {
            val num = fileMatch.groupValues[1].toIntOrNull()
            if (num != null && num > 0) {
                return num
            }
        }

        return 0
    }

    @Test
    fun testTrackNumberParsingFromMetadataTags() {
        assertEquals(1, parseTrackNumber("1/12", "Song A"))
        assertEquals(5, parseTrackNumber("05/10", "Song B"))
        assertEquals(3, parseTrackNumber("3", "Song C"))
        assertEquals(12, parseTrackNumber("12 ", "Song D"))
        assertEquals(7, parseTrackNumber("Track 07", "Song E"))
    }

    @Test
    fun testTrackNumberParsingFallbackFromFilename() {
        assertEquals(1, parseTrackNumber(null, "01. 稻香"))
        assertEquals(2, parseTrackNumber("", "02 - 晴天"))
        assertEquals(3, parseTrackNumber("0", "03_夜曲"))
        assertEquals(4, parseTrackNumber(null, "4 枫"))
        assertEquals(1, parseTrackNumber(null, "1-01 以父之名"))
        assertEquals(11, parseTrackNumber(null, "CD1_11_彩虹"))
    }

    @Test
    fun testAlbumTrackSortingAscending() {
        val rawSongs = listOf(
            SongEntity(path = "/music/song3.mp3", title = "夜曲", artist = "周杰伦", album = "十一月的萧邦", duration = 220000, trackNumber = 3),
            SongEntity(path = "/music/song1.mp3", title = "夜的第七章", artist = "周杰伦", album = "依然范特西", duration = 220000, trackNumber = 1),
            SongEntity(path = "/music/song0.mp3", title = "未编号附赠曲", artist = "周杰伦", album = "依然范特西", duration = 220000, trackNumber = 0),
            SongEntity(path = "/music/song2.mp3", title = "听妈妈的话", artist = "周杰伦", album = "依然范特西", duration = 220000, trackNumber = 2)
        )

        // Filter and sort for album "依然范特西"
        val albumSongs = rawSongs.filter { it.album == "依然范特西" }
        val sorted = albumSongs.sortedWith(compareBy(
            { if (it.trackNumber <= 0) 999999 else it.trackNumber },
            { it.path }
        ))

        assertEquals("Track 1 must be first", 1, sorted[0].trackNumber)
        assertEquals("Track 2 must be second", 2, sorted[1].trackNumber)
        assertEquals("Track 0 (untracked) must be last", 0, sorted[2].trackNumber)
    }
}
