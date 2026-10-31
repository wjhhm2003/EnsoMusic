package com.enso.music.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinInitialHelperTest {

    @Test
    fun testChineseCharactersLevel1() {
        assertEquals("Q", PinyinInitialHelper.getInitial("七里香"))
        assertEquals("Q", PinyinInitialHelper.getInitial("晴天"))
        assertEquals("A", PinyinInitialHelper.getInitial("安静"))
        assertEquals("D", PinyinInitialHelper.getInitial("稻香"))
        assertEquals("Q", PinyinInitialHelper.getInitial("青花瓷"))
        assertEquals("J", PinyinInitialHelper.getInitial("简单爱"))
        assertEquals("Y", PinyinInitialHelper.getInitial("夜曲"))
        assertEquals("B", PinyinInitialHelper.getInitial("本草纲目"))
        assertEquals("C", PinyinInitialHelper.getInitial("彩虹"))
        assertEquals("H", PinyinInitialHelper.getInitial("花海"))
        assertEquals("T", PinyinInitialHelper.getInitial("退后"))
        assertEquals("F", PinyinInitialHelper.getInitial("枫"))
    }

    @Test
    fun testEnglishTitles() {
        assertEquals("A", PinyinInitialHelper.getInitial("All of Me"))
        assertEquals("B", PinyinInitialHelper.getInitial("bad guy"))
        assertEquals("Y", PinyinInitialHelper.getInitial("Yellow"))
        assertEquals("Z", PinyinInitialHelper.getInitial("Zombie"))
    }

    @Test
    fun testNumbersAndSymbols() {
        assertEquals("#", PinyinInitialHelper.getInitial("1989"))
        assertEquals("#", PinyinInitialHelper.getInitial("21 Guns"))
        assertEquals("#", PinyinInitialHelper.getInitial("!Hero"))
        assertEquals("#", PinyinInitialHelper.getInitial("(Intro)"))
        assertEquals("#", PinyinInitialHelper.getInitial("...Baby One More Time"))
    }

    @Test
    fun testNullAndBlankInput() {
        assertEquals("#", PinyinInitialHelper.getInitial(null))
        assertEquals("#", PinyinInitialHelper.getInitial(""))
        assertEquals("#", PinyinInitialHelper.getInitial("   "))
    }

    @Test
    fun testSupplementaryCharacters() {
        assertEquals("Y", PinyinInitialHelper.getInitial("怡然自得"))
        assertEquals("X", PinyinInitialHelper.getInitial("鑫"))
        assertEquals("M", PinyinInitialHelper.getInitial("沐雪"))
        assertEquals("R", PinyinInitialHelper.getInitial("睿智"))
        assertEquals("K", PinyinInitialHelper.getInitial("恺歌"))
        assertEquals("H", PinyinInitialHelper.getInitial("涵义"))
        assertEquals("Z", PinyinInitialHelper.getInitial("梓里"))
    }

    @Test
    fun testLeadingWhitespace() {
        assertEquals("Q", PinyinInitialHelper.getInitial("  七里香"))
        assertEquals("A", PinyinInitialHelper.getInitial("\tAll of Me"))
    }

    @Test
    fun testSortKeyFormat() {
        val keyQ = PinyinInitialHelper.getSortKey("七里香")
        assertTrue(keyQ.startsWith("Q:"))

        val keyA = PinyinInitialHelper.getSortKey("安静")
        assertTrue(keyA.startsWith("A:"))

        val keyNum = PinyinInitialHelper.getSortKey("1989")
        assertTrue(keyNum.startsWith("~:#:"))

        val keySymbol = PinyinInitialHelper.getSortKey("!Hero")
        assertTrue(keySymbol.startsWith("~:#:"))
    }

    @Test
    fun testSortKeyOrdering() {
        val titles = listOf(
            "1989",
            "Yellow",
            "七里香",
            "!Hero",
            "安静",
            "本草纲目",
            "彩虹",
            "All of Me",
            "稻香",
            "99 Luftballons"
        )

        val sorted = titles.sortedBy { PinyinInitialHelper.getSortKey(it) }

        // A-Z songs must appear before numbers and symbols ('~:#:...')
        val aIndex = sorted.indexOf("安静")
        val allOfMeIndex = sorted.indexOf("All of Me")
        val bIndex = sorted.indexOf("本草纲目")
        val cIndex = sorted.indexOf("彩虹")
        val dIndex = sorted.indexOf("稻香")
        val qIndex = sorted.indexOf("七里香")
        val yIndex = sorted.indexOf("Yellow")
        val numIndex = sorted.indexOf("1989")
        val num99Index = sorted.indexOf("99 Luftballons")
        val symbolIndex = sorted.indexOf("!Hero")

        // Check relative alphabetical order
        assertTrue(aIndex < bIndex)
        assertTrue(bIndex < cIndex)
        assertTrue(cIndex < dIndex)
        assertTrue(dIndex < qIndex)
        assertTrue(qIndex < yIndex)

        // All A-Z items must precede numbers and symbols
        assertTrue(yIndex < symbolIndex)
        assertTrue(yIndex < numIndex)
        assertTrue(yIndex < num99Index)
    }
}
