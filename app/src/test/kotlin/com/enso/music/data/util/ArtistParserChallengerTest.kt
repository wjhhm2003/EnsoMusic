package com.enso.music.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ArtistParserChallengerTest {

    @Test
    fun testMandatoryPromptExtremeArtistString() {
        val input = "周杰伦 / 费玉清 & 蔡依林、方文山; 林俊杰, 陶喆 ｜ 王力宏 | 陈奕迅 \\ 孙燕姿 feat. 李宗盛 FT. 罗大佑 vs. 汪峰 WITH 许巍"
        val expected = listOf(
            "周杰伦", "费玉清", "蔡依林", "方文山", "林俊杰",
            "陶喆", "王力宏", "陈奕迅", "孙燕姿", "李宗盛",
            "罗大佑", "汪峰", "许巍"
        )
        val actual = ArtistParser.parse(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testConsecutiveDelimiters() {
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦///费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦 / / / 费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦 / & , ; | 费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦 feat. with vs. ft. 费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦 feat. / with & 费玉清"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A,,,,,,,,B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A \\\\\\\\ B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A ｜｜｜ B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A 、、、 B"))
    }

    @Test
    fun testLeadingAndTrailingDelimiters() {
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("/ 周杰伦 / 费玉清 /"))
        assertEquals(listOf("周杰伦"), ArtistParser.parse("& 、 ; 周杰伦 feat."))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("feat. 周杰伦 with 费玉清 vs."))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("   /   "))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(" / ; , "))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(";;;;"))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("feat. ft. vs. with"))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("FEAT.   WITH   VS.   FT."))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(" / \\ & 、 ; , ， ； ｜ | feat. ft. vs. with "))
    }

    @Test
    fun testMixedSpacesAndWhitespace() {
        assertEquals(listOf("A", "B", "C"), ArtistParser.parse("A\t/\nB \r& C"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A\u3000/\u3000B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A          /          B"))
    }

    @Test
    fun testCaseInsensitiveConnectors() {
        assertEquals(listOf("A", "B"), ArtistParser.parse("A FEAT. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A Feat. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A fEaT. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A feat B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A FEAT B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A With B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A WITH B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A wItH B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A Vs. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A VS. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A vs B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A VS B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A Ft. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A FT. B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A ft B"))
        assertEquals(listOf("A", "B"), ArtistParser.parse("A FT B"))
    }

    @Test
    fun testEdgeCasesNullEmptyAndBlanks() {
        assertEquals(listOf("未知歌手"), ArtistParser.parse(null))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(""))
        assertEquals(listOf("未知歌手"), ArtistParser.parse(" "))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("       "))
        assertEquals(listOf("未知歌手"), ArtistParser.parse("\t\n\r  "))
    }

    @Test
    fun testEmbeddedConnectorSubstringsInArtistNames() {
        // "with" in "Swift", "Within"
        assertEquals(listOf("Taylor Swift"), ArtistParser.parse("Taylor Swift"))
        assertEquals(listOf("Within Temptation"), ArtistParser.parse("Within Temptation"))
        assertEquals(listOf("The Soft Moon"), ArtistParser.parse("The Soft Moon"))
        assertEquals(listOf("Elvis Costello"), ArtistParser.parse("Elvis Costello"))
        assertEquals(listOf("Deftones"), ArtistParser.parse("Deftones"))
        assertEquals(listOf("Devo"), ArtistParser.parse("Devo"))
        assertEquals(listOf("Swift"), ArtistParser.parse("Swift"))
        assertEquals(listOf("Taylor Swift", "Within Temptation"), ArtistParser.parse("Taylor Swift / Within Temptation"))
    }

    @Test
    fun testDeduplicationAcrossVariousDelimiters() {
        assertEquals(listOf("Jay"), ArtistParser.parse("Jay / Jay & Jay feat. Jay WITH Jay"))
        assertEquals(listOf("周杰伦", "费玉清"), ArtistParser.parse("周杰伦 / 费玉清 / 周杰伦 / 费玉清"))
    }

    @Test
    fun testGenerativeStressArtistParser() {
        val delimiters = listOf(
            "/", "\\", "&", "、", ";", ",", "，", "；", "｜", "|",
            "feat.", "FEAT.", "Feat", "ft.", "FT.", "ft", "vs.", "VS.", "Vs", "with", "WITH", "With"
        )
        val names = listOf("周杰伦", "Taylor Swift", "Eminem", "李宗盛", "Sia", "Coldplay", "Adele", "陶喆")
        val rnd = Random(42)

        for (iteration in 0 until 1000) {
            val count = rnd.nextInt(10) + 1
            val sb = StringBuilder()
            // Random leading delimiters
            if (rnd.nextBoolean()) {
                sb.append(delimiters[rnd.nextInt(delimiters.size)]).append(" ")
            }
            for (i in 0 until count) {
                if (i > 0) {
                    val delimCount = rnd.nextInt(3) + 1
                    for (d in 0 until delimCount) {
                        sb.append(" ").append(delimiters[rnd.nextInt(delimiters.size)]).append(" ")
                    }
                }
                sb.append(names[rnd.nextInt(names.size)])
            }
            // Random trailing delimiters
            if (rnd.nextBoolean()) {
                sb.append(" ").append(delimiters[rnd.nextInt(delimiters.size)])
            }

            val parsed = ArtistParser.parse(sb.toString())
            assertTrue(parsed.isNotEmpty())
            // Ensure no empty strings and no duplicates
            for (item in parsed) {
                assertTrue(item.isNotBlank())
            }
            assertEquals(parsed.size, parsed.distinct().size)
        }
    }
}
