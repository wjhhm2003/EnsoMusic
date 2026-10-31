package com.enso.music.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistParserTest {

    @Test
    fun testSingleArtist() {
        val result = ArtistParser.parse("周杰伦")
        assertEquals(listOf("周杰伦"), result)
    }

    @Test
    fun testSlashDelimiter() {
        val result = ArtistParser.parse("周杰伦 / 费玉清")
        assertEquals(listOf("周杰伦", "费玉清"), result)
    }

    @Test
    fun testBackslashDelimiter() {
        val result = ArtistParser.parse("Artist1 \\ Artist2")
        assertEquals(listOf("Artist1", "Artist2"), result)
    }

    @Test
    fun testAmpersandDelimiter() {
        val result = ArtistParser.parse("Artist1 & Artist2")
        assertEquals(listOf("Artist1", "Artist2"), result)
    }

    @Test
    fun testChineseEnumerationComma() {
        val result = ArtistParser.parse("张三、李四")
        assertEquals(listOf("张三", "李四"), result)
    }

    @Test
    fun testSemicolonDelimiter() {
        val result = ArtistParser.parse("Artist1; Artist2；Artist3")
        assertEquals(listOf("Artist1", "Artist2", "Artist3"), result)
    }

    @Test
    fun testCommaDelimiter() {
        val result = ArtistParser.parse("Artist1, Artist2，Artist3")
        assertEquals(listOf("Artist1", "Artist2", "Artist3"), result)
    }

    @Test
    fun testPipeDelimiter() {
        val result = ArtistParser.parse("Artist1 | Artist2 ｜ Artist3")
        assertEquals(listOf("Artist1", "Artist2", "Artist3"), result)
    }

    @Test
    fun testFeatConnector() {
        val result = ArtistParser.parse("Eminem feat. Rihanna")
        assertEquals(listOf("Eminem", "Rihanna"), result)
    }

    @Test
    fun testFtConnector() {
        val result = ArtistParser.parse("David Guetta ft. Sia")
        assertEquals(listOf("David Guetta", "Sia"), result)
    }

    @Test
    fun testFtWithoutDot() {
        val result = ArtistParser.parse("David Guetta ft Sia")
        assertEquals(listOf("David Guetta", "Sia"), result)
    }

    @Test
    fun testWithConnector() {
        val result = ArtistParser.parse("Taylor Swift with Bon Iver")
        assertEquals(listOf("Taylor Swift", "Bon Iver"), result)
    }

    @Test
    fun testVsConnector() {
        val result = ArtistParser.parse("Marshmello vs. Alan Walker")
        assertEquals(listOf("Marshmello", "Alan Walker"), result)
    }

    @Test
    fun testMixedDelimiters() {
        val result = ArtistParser.parse("A & B、C; D, E｜F|G \\ H")
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "H"), result)
    }

    @Test
    fun testDeduplication() {
        val result = ArtistParser.parse("Jay / Jay")
        assertEquals(listOf("Jay"), result)
    }

    @Test
    fun testNullAndBlankInput() {
        assertEquals(listOf("未知歌手"), ArtistParser.parse(null))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(""))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("   "))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(" / ; , "))
    }

    @Test
    fun testWhitespaceTrimming() {
        val result = ArtistParser.parse("  Artist 1  /   Artist 2   ")
        assertEquals(listOf("Artist 1", "Artist 2"), result)
    }

    @Test
    fun testCaseInsensitiveConnectors() {
        val result = ArtistParser.parse("Artist FEAT. Other FT Another WITH Someone VS Rival")
        assertEquals(listOf("Artist", "Other", "Another", "Someone", "Rival"), result)
    }

    @Test
    fun testArtistNameWithEmbeddedConnectorSubstring() {
        val result = ArtistParser.parse("Taylor Swift")
        assertEquals(listOf("Taylor Swift"), result)
    }
}
