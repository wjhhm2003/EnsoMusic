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

    @Test
    fun testOverlappingLinesDuetBehavior() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml"
                itunes:timing="Word">
              <body>
                <div>
                  <p begin="00:10.000" end="00:16.000" ttm:agent="v1">
                    <span begin="00:10.000" end="00:16.000">Frozen into icy rocks, that's how it starts</span>
                  </p>
                  <p begin="00:13.000" end="00:18.000" ttm:agent="v2">
                    <span begin="00:13.000" end="00:18.000">That's how it starts</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyricData = com.enso.music.data.lyric.parser.TtmlParser.parse(ttml)
        assertEquals(2, lyricData.lines.size)
        val line1 = lyricData.lines[0]
        val line2 = lyricData.lines[1]

        // Verify intentional overlap is preserved
        assertEquals(10000L, line1.startTimeMs)
        assertEquals(16000L, line1.endTimeMs)
        assertEquals(13000L, line2.startTimeMs)
        assertEquals(18000L, line2.endTimeMs)

        // At 14000ms (during overlapping duet singing):
        // 1. Both lines MUST be active simultaneously
        val activeIndices = lyricData.getActiveLineIndices(14000L)
        assertEquals(listOf(0, 1), activeIndices)

        // 2. Focus index MUST stay on line1 until line1 finishes (prevent premature jumping)
        assertEquals(0, lyricData.findActiveLineIndex(14000L))

        // At 16500ms (after line1 finishes, line2 continues):
        // Focus moves smoothly to line2
        assertEquals(1, lyricData.findActiveLineIndex(16500L))
        assertEquals(listOf(1), lyricData.getActiveLineIndices(16500L))
    }
}
