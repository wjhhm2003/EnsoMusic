package com.enso.music.data.lyric

import com.enso.music.data.lyric.model.LyricAlignment
import com.enso.music.data.lyric.parser.LrcParser
import com.enso.music.data.lyric.parser.TimeUtils
import com.enso.music.data.lyric.parser.TtmlParser
import org.junit.Assert.*
import org.junit.Test

class LyricParserTest {

    @Test
    fun testTimeUtilsParsing() {
        assertEquals(75420L, TimeUtils.parseTimeToMs("01:15.42"))
        assertEquals(75420L, TimeUtils.parseTimeToMs("01:15.420"))
        assertEquals(3675420L, TimeUtils.parseTimeToMs("01:01:15.420"))
        assertEquals(12340L, TimeUtils.parseTimeToMs("12.34s"))
        assertEquals(15000L, TimeUtils.parseTimeToMs("15s"))
        assertEquals(5000L, TimeUtils.parseTimeToMs("5.0"))
        assertNull(TimeUtils.parseTimeToMs(""))
        assertNull(TimeUtils.parseTimeToMs("invalid"))
    }

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [ti:Test Title]
            [ar:Test Artist]
            [offset:500]
            [00:10.00]First line
            [00:15.50]Second line
            [00:20.00]Third line
        """.trimIndent()

        val lyricData = LrcParser.parse(lrc)
        assertEquals(3, lyricData.lines.size)
        // With offset 500ms
        assertEquals(10500L, lyricData.lines[0].startTimeMs)
        assertEquals("First line", lyricData.lines[0].text)
        assertEquals(16000L, lyricData.lines[1].startTimeMs)
        assertEquals("Second line", lyricData.lines[1].text)
        assertFalse(lyricData.hasWordTiming)
    }

    @Test
    fun testLrcTranslationMerge() {
        val lrc = """
            [00:10.00]Hello world
            [00:10.00]你好世界
            [00:20.00]Goodbye friend
            [00:20.02]再见朋友
        """.trimIndent()

        val lyricData = LrcParser.parse(lrc)
        assertEquals(2, lyricData.lines.size)
        assertEquals("Hello world", lyricData.lines[0].text)
        assertEquals("你好世界", lyricData.lines[0].translation)
        assertEquals("Goodbye friend", lyricData.lines[1].text)
        assertEquals("再见朋友", lyricData.lines[1].translation)
        assertTrue(lyricData.hasTranslation)
    }

    @Test
    fun testEnhancedLrcWordTiming() {
        val lrc = """
            [00:10.00]<00:10.00>Never <00:10.50>gonna <00:11.20>give <00:11.80>you <00:12.30>up
        """.trimIndent()

        val lyricData = LrcParser.parse(lrc)
        assertEquals(1, lyricData.lines.size)
        assertTrue(lyricData.hasWordTiming)
        val line = lyricData.lines[0]
        assertEquals("Never gonna give you up", line.text)
        assertNotNull(line.words)
        assertEquals(5, line.words!!.size)
        assertEquals("Never ", line.words!![0].text)
        assertEquals(10000L, line.words!![0].startTimeMs)
        assertEquals(10500L, line.words!![0].endTimeMs)
    }

    @Test
    fun testTtmlFullFeatures() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"
                xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:itunes="http://music.apple.com/lyric-ttml"
                xmlns:tts="http://www.w3.org/ns/ttml#styling"
                itunes:timing="Word">
              <head>
                <metadata>
                  <ttm:agent type="person" xml:id="v1">Singer One</ttm:agent>
                  <ttm:agent type="person" xml:id="v2">Singer Two</ttm:agent>
                  <ttm:agent type="group" xml:id="v1000">Chorus</ttm:agent>
                  <iTunesMetadata>
                    <translations>
                      <translation>
                        <text for="L2">世界如此之大</text>
                      </translation>
                    </translations>
                    <transliterations>
                      <transliteration>
                        <text for="L2">shi jie ru ci zhi da</text>
                      </transliteration>
                    </transliterations>
                  </iTunesMetadata>
                </metadata>
              </head>
              <body>
                <div itunes:song-part="Verse 1">
                  <p begin="00:10.000" end="00:15.000" ttm:agent="v1" itunes:key="L1">
                    <span begin="00:10.000" end="00:11.000">Hello </span>
                    <span begin="00:11.000" end="00:12.500">World</span>
                    <span ttm:role="x-bg" begin="00:12.600" end="00:14.500">
                      <span begin="00:12.600" end="00:13.500">(Echo </span>
                      <span begin="00:13.500" end="00:14.500">here)</span>
                      <span ttm:role="x-translation">回声在此</span>
                    </span>
                    <span ttm:role="x-translation">你好世界</span>
                    <span ttm:role="x-roman">ni hao shi jie</span>
                  </p>
                </div>
                <div itunes:song-part="Chorus">
                  <p begin="00:16.000" end="00:20.000" ttm:agent="v2" itunes:key="L2">
                    <span begin="00:16.000" end="00:18.000">Big </span>
                    <span begin="00:18.000" end="00:20.000">World</span>
                  </p>
                  <p begin="00:21.000" end="00:25.000" ttm:agent="v1000" itunes:key="L3">
                    <span tts:ruby="container">
                      <span tts:ruby="base" begin="00:21.000" end="00:23.000">明日</span>
                      <span tts:ruby="text" begin="00:21.000" end="00:23.000">あした</span>
                    </span>
                    <span begin="00:23.000" end="00:25.000"> へ</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyricData = TtmlParser.parse(ttml)
        assertEquals(3, lyricData.lines.size)
        assertTrue(lyricData.hasWordTiming)
        assertTrue(lyricData.hasTranslation)
        assertTrue(lyricData.hasTransliteration)

        // Line 1 assertions
        val line1 = lyricData.lines[0]
        assertEquals("Verse 1", line1.songPart)
        assertEquals("Hello World", line1.text)
        assertEquals(LyricAlignment.LEFT, line1.alignment)
        assertEquals("你好世界", line1.translation)
        assertEquals("ni hao shi jie", line1.transliteration)
        assertNotNull(line1.words)
        assertEquals(2, line1.words!!.size)
        assertEquals("Hello ", line1.words!![0].text)

        // Bg vocals in Line 1
        assertNotNull(line1.bgVocals)
        assertEquals(1, line1.bgVocals!!.size)
        val bg = line1.bgVocals!![0]
        assertEquals("Echo here", bg.text) // brackets cleaned
        assertEquals("回声在此", bg.translation)
        assertNotNull(bg.words)
        assertEquals(2, bg.words!!.size)

        // Line 2 assertions (Sidecar metadata)
        val line2 = lyricData.lines[1]
        assertEquals("Chorus", line2.songPart)
        assertEquals(LyricAlignment.RIGHT, line2.alignment) // v2 -> RIGHT
        assertEquals("世界如此之大", line2.translation)
        assertEquals("shi jie ru ci zhi da", line2.transliteration)

        // Line 3 assertions (Ruby & Chorus alignment)
        val line3 = lyricData.lines[2]
        assertEquals(LyricAlignment.CENTER, line3.alignment) // v1000 -> CENTER
        assertNotNull(line3.rubyList)
        assertEquals(1, line3.rubyList!!.size)
        val ruby = line3.rubyList!![0]
        assertEquals("明日", ruby.baseText)
        assertEquals("あした", ruby.rubyText)
        assertEquals(21000L, ruby.startTimeMs)
    }
}
