package com.enso.music.data.util

import java.nio.charset.Charset

object PinyinInitialHelper {

    private val CHARSET_GB2312: Charset by lazy {
        try {
            Charset.forName("GB2312")
        } catch (e: Exception) {
            try {
                Charset.forName("GBK")
            } catch (e2: Exception) {
                Charsets.UTF_8
            }
        }
    }

    // GB2312 Level 1 section-position boundaries (secPos = (high - 160) * 100 + (low - 160))
    private val SEC_POS_VALUES = intArrayOf(
        1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787,
        3106, 3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086,
        4390, 4558, 4684, 4925, 5249, 5590
    )

    private val PINYIN_LETTERS = charArrayOf(
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S',
        'T', 'W', 'X', 'Y', 'Z'
    )

    // Frequent Chinese characters in names/music outside GB2312 Level 1
    private val SUPPLEMENTARY_MAP: Map<Char, Char> = mapOf(
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

    fun getCharInitial(ch: Char): Char? {
        if (ch in 'A'..'Z') return ch
        if (ch in 'a'..'z') return ch.uppercaseChar()

        // Check supplementary mapping
        SUPPLEMENTARY_MAP[ch]?.let { return it }

        // Check GB2312 level 1
        return try {
            val bytes = ch.toString().toByteArray(CHARSET_GB2312)
            if (bytes.size >= 2) {
                val high = bytes[0].toInt() and 0xFF
                val low = bytes[1].toInt() and 0xFF
                if (high in 176..215 && low in 161..254) {
                    val secPos = (high - 160) * 100 + (low - 160)
                    for (i in 0 until 23) {
                        if (secPos >= SEC_POS_VALUES[i] && secPos < SEC_POS_VALUES[i + 1]) {
                            return PINYIN_LETTERS[i]
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts single letter index ("A".."Z" or "#") for first non-space character.
     */
    fun getInitial(text: String?): String {
        if (text.isNullOrBlank()) return "#"
        val trimmed = text.trim()
        val firstChar = trimmed.firstOrNull { !it.isWhitespace() } ?: return "#"
        val initial = getCharInitial(firstChar)
        return if (initial != null && initial in 'A'..'Z') initial.toString() else "#"
    }

    /**
     * Extracts Pinyin initials for all characters in the string.
     */
    fun getPinyinInitials(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder()
        for (ch in text) {
            val initial = getCharInitial(ch)
            if (initial != null) {
                sb.append(initial)
            } else if (ch.isDigit()) {
                sb.append(ch)
            } else if (!ch.isWhitespace()) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Generates a normalized sorting key for SQLite ORDER BY.
     * Songs with initial 'A'..'Z' sort from "A:..." to "Z:...".
     * Songs with initial '#' sort under "~:#:..." so they appear at the bottom.
     */
    fun getSortKey(text: String?): String {
        if (text.isNullOrBlank()) return "~:#:"
        val trimmed = text.trim()
        val initial = getInitial(trimmed)
        val initials = getPinyinInitials(trimmed)
        val upper = trimmed.uppercase()

        return if (initial in "A".."Z") {
            "$initial:$initials:$upper"
        } else {
            "~:#:$upper"
        }
    }
}
