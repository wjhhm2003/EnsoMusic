package com.enso.music.data.lyric

import com.enso.music.data.entity.SongEntity
import com.enso.music.data.lyric.extractor.LocalLyricExtractor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LyricRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseContentAutoTtmlVsLrc() {
        // Create mock context-free extractor test via reflection or helper
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word">
              <body>
                <div>
                  <p begin="00:01.000" end="00:04.000"><span>Hello </span><span>world</span></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lrc = """
            [00:01.00]Hello world
            [00:05.00]Second line
        """.trimIndent()

        // Testing auto sniffing logic directly
        val parsedTtml = com.enso.music.data.lyric.parser.TtmlParser.parse(ttml)
        assertTrue(parsedTtml.hasWordTiming)
        assertEquals(1, parsedTtml.lines.size)

        val parsedLrc = com.enso.music.data.lyric.parser.LrcParser.parse(lrc)
        assertFalse(parsedLrc.hasWordTiming)
        assertEquals(2, parsedLrc.lines.size)
    }

    @Test
    fun testFindActiveLineIndexBinarySearch() {
        val lrc = """
            [00:10.00]Line 1
            [00:20.00]Line 2
            [00:30.00]Line 3
            [00:40.00]Line 4
        """.trimIndent()

        val lyricData = com.enso.music.data.lyric.parser.LrcParser.parse(lrc)
        assertEquals(-1, lyricData.findActiveLineIndex(5000L)) // before first line
        assertEquals(0, lyricData.findActiveLineIndex(10000L)) // exact first line
        assertEquals(0, lyricData.findActiveLineIndex(15000L)) // mid first line
        assertEquals(1, lyricData.findActiveLineIndex(20000L)) // exact second line
        assertEquals(1, lyricData.findActiveLineIndex(25000L)) // mid second line
        assertEquals(2, lyricData.findActiveLineIndex(30000L)) // exact third line
        assertEquals(3, lyricData.findActiveLineIndex(40000L)) // exact fourth line
        assertEquals(3, lyricData.findActiveLineIndex(99999L)) // past last line
    }
}
