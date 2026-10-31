package com.enso.music.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PinyinInitialHelperChallengerTest {

    @Test
    fun testAllSupplementaryCharactersIndividually() {
        val supplementaryExpected = mapOf(
            '怡' to 'Y', '鑫' to 'X', '琪' to 'Q', '睿' to 'R', '晟' to 'S',
            '璟' to 'J', '沐' to 'M', '奕' to 'Y', '梓' to 'Z', '昊' to 'H',
            '恺' to 'K', '涵' to 'H', '萱' to 'X', '茜' to 'Q', '雯' to 'W',
            '祺' to 'Q', '熙' to 'X', '皓' to 'H', '玥' to 'Y', '旻' to 'M',
            '喆' to 'Z', '淼' to 'M', '霆' to 'T', '琨' to 'K', '璇' to 'X',
            '璨' to 'C', '梵' to 'F', '珈' to 'J', '珞' to 'L', '瑜' to 'Y',
            '瑶' to 'Y', '琦' to 'Q', '珏' to 'J', '斐' to 'F', '泓' to 'H',
            '瀚' to 'H', '骅' to 'H', '骁' to 'X', '骥' to 'J', '麒' to 'Q',
            '麟' to 'L', '黛' to 'D', '芙' to 'F', '蓉' to 'R', '苇' to 'W',
            '芮' to 'R', '茉' to 'M', '莉' to 'L', '萌' to 'M', '蓓' to 'B',
            '蕾' to 'L', '蔚' to 'W', '蕙' to 'H', '薰' to 'X', '蘅' to 'H'
        )

        for ((char, expectedInitial) in supplementaryExpected) {
            val initial = PinyinInitialHelper.getInitial(char.toString())
            assertEquals("Failed for character '$char'", expectedInitial.toString(), initial)
        }
    }

    @Test
    fun testSupplementaryInFullTitles() {
        assertEquals("Y", PinyinInitialHelper.getInitial("怡然自得"))
        assertEquals("X", PinyinInitialHelper.getInitial("鑫盛辉煌"))
        assertEquals("Q", PinyinInitialHelper.getInitial("琪花瑶草"))
        assertEquals("R", PinyinInitialHelper.getInitial("睿智人生"))
        assertEquals("M", PinyinInitialHelper.getInitial("沐雪而行"))
        assertEquals("Y", PinyinInitialHelper.getInitial("奕奕神采"))
        assertEquals("Z", PinyinInitialHelper.getInitial("梓里乡情"))
        assertEquals("Z", PinyinInitialHelper.getInitial("喆喆称奇"))
        // First character non-supplementary, second is supplementary
        assertEquals("T", PinyinInitialHelper.getInitial("陶喆"))
    }

    @Test
    fun testGB2312Level1FullAlphabetCoverage() {
        val testSamples = mapOf(
            'A' to "安静",
            'B' to "本草纲目",
            'C' to "彩虹",
            'D' to "稻香",
            'E' to "恶作剧",
            'F' to "枫",
            'G' to "告白气球",
            'H' to "花海",
            'J' to "简单爱",
            'K' to "开不了口",
            'L' to "龙卷风",
            'M' to "迷迭香",
            'N' to "南拳妈妈",
            'O' to "欧洲之行",
            'P' to "蒲公英的约定",
            'Q' to "七里香",
            'R' to "如果的事",
            'S' to "双截棍",
            'T' to "退后",
            'W' to "屋顶",
            'X' to "星晴",
            'Y' to "夜曲",
            'Z' to "最长的电影"
        )

        for ((expectedInitial, title) in testSamples) {
            assertEquals("Failed for title '$title'", expectedInitial.toString(), PinyinInitialHelper.getInitial(title))
        }
    }

    @Test
    fun testGB2312ZSectionBoundaries() {
        val zChars = listOf("在", "再", "曾", "张", "赵", "这", "真", "中", "周", "朱", "抓", "字", "自", "宗", "走", "最", "昨", "左", "作", "坐", "座")
        for (ch in zChars) {
            assertEquals("Failed for Z-boundary char '$ch'", "Z", PinyinInitialHelper.getInitial(ch))
        }
    }

    @Test
    fun testMixedChineseEnglishNumbersAndSymbols() {
        // Mixed Chinese + English
        assertEquals("Z", PinyinInitialHelper.getInitial("周杰伦 (Jay Chou)"))
        val sortKeyJay = PinyinInitialHelper.getSortKey("周杰伦 (Jay Chou)")
        assertTrue("SortKey must start with Z: for 周杰伦 (Jay Chou), was: $sortKeyJay", sortKeyJay.startsWith("Z:"))

        // Numbers
        assertEquals("#", PinyinInitialHelper.getInitial("1989"))
        assertEquals("~:#:1989", PinyinInitialHelper.getSortKey("1989"))
        assertEquals("#", PinyinInitialHelper.getInitial("007 Theme"))
        assertEquals("#", PinyinInitialHelper.getInitial("21 Guns"))
        assertEquals("#", PinyinInitialHelper.getInitial("99 Luftballons"))

        // Special symbols
        assertEquals("#", PinyinInitialHelper.getInitial("!@#$"))
        assertEquals("~:#:!@#$", PinyinInitialHelper.getSortKey("!@#$"))
        assertEquals("#", PinyinInitialHelper.getInitial("...Baby One More Time"))
        assertEquals("#", PinyinInitialHelper.getInitial("(Intro)"))
        assertEquals("#", PinyinInitialHelper.getInitial("[Live]"))
        assertEquals("#", PinyinInitialHelper.getInitial("$$$ Money"))

        // Foreign languages (Japanese Hiragana, Katakana, Korean Hangul)
        assertEquals("#", PinyinInitialHelper.getInitial("さくら (Sakura)"))
        assertEquals("#", PinyinInitialHelper.getInitial("カタカナ"))
        assertEquals("#", PinyinInitialHelper.getInitial("눈, 코, 입"))
        assertEquals("#", PinyinInitialHelper.getInitial("아이유 (IU)"))
        assertTrue(PinyinInitialHelper.getSortKey("さくら").startsWith("~:#:"))
        assertTrue(PinyinInitialHelper.getSortKey("아이유").startsWith("~:#:"))

        // Emoji & Symbols
        assertEquals("#", PinyinInitialHelper.getInitial("🎵 Music Note"))
        assertEquals("#", PinyinInitialHelper.getInitial("🔥 Fire Track"))
        assertEquals("#", PinyinInitialHelper.getInitial("✨ Sparkle"))
        assertTrue(PinyinInitialHelper.getSortKey("🎵 Music Note").startsWith("~:#:"))

        // Leading whitespace
        assertEquals("Y", PinyinInitialHelper.getInitial("  \t\n  夜曲  "))
        assertEquals("A", PinyinInitialHelper.getInitial("   A.I. Love   "))

        // Null and blank
        assertEquals("#", PinyinInitialHelper.getInitial(null))
        assertEquals("#", PinyinInitialHelper.getInitial(""))
        assertEquals("#", PinyinInitialHelper.getInitial("   "))
        assertEquals("~:#:", PinyinInitialHelper.getSortKey(null))
        assertEquals("~:#:", PinyinInitialHelper.getSortKey(""))
        assertEquals("~:#:", PinyinInitialHelper.getSortKey("   "))
    }

    @Test
    fun testSortKeyASCIIComparisonRule() {
        val azTitles = listOf(
            "All of Me",
            "安静",
            "bad guy",
            "本草纲目",
            "Coldplay",
            "彩虹",
            "Drake",
            "稻香",
            "Eminem",
            "恶作剧",
            "Foo Fighters",
            "枫",
            "Gorillaz",
            "告白气球",
            "Hozier",
            "花海",
            "怡然自得", // Y
            "简单爱",
            "Kanye",
            "开不了口",
            "Lorde",
            "龙卷风",
            "Maroon 5",
            "迷迭香",
            "沐雪", // M
            "Nirvana",
            "南拳妈妈",
            "Oasis",
            "Post Malone",
            "蒲公英的约定",
            "Queen",
            "七里香",
            "琪花瑶草", // Q
            "Radiohead",
            "如果的事",
            "睿智", // R
            "Sia",
            "双截棍",
            "Taylor Swift",
            "退后",
            "U2",
            "Vance Joy",
            "Weeknd",
            "屋顶",
            "XXXTentacion",
            "星晴",
            "鑫盛", // X
            "Yellow",
            "夜曲",
            "奕奕神采", // Y
            "Zedd",
            "最长的电影",
            "梓里乡情", // Z
            "周杰伦 (Jay Chou)"
        )

        val hashTitles = listOf(
            "007 Theme",
            "1989",
            "21 Guns",
            "500 Miles",
            "99 Luftballons",
            "!@#$",
            "!Hero",
            "$$$ Money",
            "...Baby One More Time",
            "(Intro)",
            "[Live]",
            "さくら",
            "カタカナ",
            "아이유",
            "🎵 Music Note",
            "🔥 Fire",
            "✨ Sparkle"
        )

        val allTitles = (azTitles + hashTitles).shuffled(Random(12345))
        val sortedTitles = allTitles.sortedBy { PinyinInitialHelper.getSortKey(it) }

        // Find boundary where A-Z ends and '#' begins
        var lastAZIndex = -1
        var firstHashIndex = -1

        for ((index, title) in sortedTitles.withIndex()) {
            val initial = PinyinInitialHelper.getInitial(title)
            val sortKey = PinyinInitialHelper.getSortKey(title)

            if (initial in "A".."Z") {
                assertTrue("Song '$title' has initial '$initial' but sortKey doesn't start with '$initial:'", sortKey.startsWith("$initial:"))
                lastAZIndex = maxOf(lastAZIndex, index)
            } else {
                assertEquals("Non A-Z title '$title' must have '#' initial", "#", initial)
                assertTrue("Song '$title' has '#' initial but sortKey doesn't start with '~:#:', was: $sortKey", sortKey.startsWith("~:#:"))
                if (firstHashIndex == -1) {
                    firstHashIndex = index
                }
            }
        }

        assertTrue("Found at least one A-Z item", lastAZIndex != -1)
        assertTrue("Found at least one '#' item", firstHashIndex != -1)
        assertTrue(
            "CRITICAL: All A-Z items must sort strictly before all '#' items! lastAZIndex=$lastAZIndex, firstHashIndex=$firstHashIndex",
            lastAZIndex < firstHashIndex
        )

        // Verify strictly monotonic A-Z ordering across sortedTitles
        var currentLetter = 'A'
        for (title in sortedTitles) {
            val initial = PinyinInitialHelper.getInitial(title)
            if (initial in "A".."Z") {
                val letter = initial[0]
                assertTrue("Letter ordering violated: $letter is before $currentLetter", letter >= currentLetter)
                currentLetter = letter
            }
        }
    }

    @Test
    fun testGenerativeStressPinyinInitialHelper() {
        val testStrings = ArrayList<String>()
        testStrings.add("")
        testStrings.add("   ")
        testStrings.add("\t\r\n")

        // Add 5,000 diverse synthetic strings
        val rnd = Random(999)
        val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;':\",./<>? \t\n" +
                "的一是在不了有和人这中大为上个国我以要他时来用生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它" +
                "怡鑫琪睿晟璟沐奕梓昊恺涵萱茜雯祺熙皓玥旻喆淼霆琨璇璨梵珈珞瑜瑶琦珏斐泓瀚骅骁骥麒麟黛芙蓉苇芮茉莉萌蓓蕾蔚蕙薰蘅" +
                "さくらカタカナ아이유🎵🔥✨"

        for (i in 0 until 5000) {
            val length = rnd.nextInt(20) + 1
            val sb = StringBuilder()
            for (j in 0 until length) {
                sb.append(charPool[rnd.nextInt(charPool.length)])
            }
            testStrings.add(sb.toString())
        }

        for (str in testStrings) {
            val initial = PinyinInitialHelper.getInitial(str)
            assertNotNull(initial)
            assertTrue("Initial must be single char: '$initial'", initial.length == 1)
            val ch = initial[0]
            assertTrue("Initial must be A-Z or #: '$ch'", ch in 'A'..'Z' || ch == '#')

            val pinyinInitials = PinyinInitialHelper.getPinyinInitials(str)
            assertNotNull(pinyinInitials)

            val sortKey = PinyinInitialHelper.getSortKey(str)
            assertNotNull(sortKey)
            if (ch in 'A'..'Z') {
                assertTrue("SortKey should start with $ch:", sortKey.startsWith("$ch:"))
            } else {
                assertTrue("SortKey should start with ~:#:", sortKey.startsWith("~:#:"))
            }
        }
    }
}
