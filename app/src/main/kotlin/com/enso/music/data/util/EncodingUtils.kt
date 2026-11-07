package com.enso.music.data.util

import java.nio.charset.Charset

object EncodingUtils {

    private val CHARSET_ISO_8859_1: Charset = Charsets.ISO_8859_1
    private val CHARSET_UTF_8: Charset = Charsets.UTF_8
    private val CHARSET_GB18030: Charset by lazy {
        try {
            Charset.forName("GB18030")
        } catch (e: Exception) {
            try {
                Charset.forName("GBK")
            } catch (e2: Exception) {
                Charsets.UTF_8
            }
        }
    }

    /**
     * Checks if a string contains Chinese / CJK unified ideographs.
     */
    fun hasCjk(text: String): Boolean {
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Detects if a string is likely misdecoded Latin-1 and attempts to repair it
     * back into UTF-8 or GB18030/GBK Chinese text.
     */
    fun fixEncoding(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val trimmed = input.trim()

        // If it already has CJK characters, it is most likely already correctly decoded
        if (hasCjk(trimmed)) {
            // Check for double-encoded UTF-8 inside Latin-1 (e.g. å‘¨æ°ä¼¦)
            // But if it has normal Chinese characters, return directly
            if (!containsSuspiciousLatin1(trimmed)) {
                return trimmed
            }
        }

        // Check if there are extended ASCII / Latin-1 bytes misread
        if (containsSuspiciousLatin1(trimmed)) {
            // U+00D8 LATIN CAPITAL LETTER O WITH STROKE (Ø) is valid Unicode,
            // not a mojibake marker. Interpreting its single Latin-1 byte as
            // the lead byte of GBK can turn the following "N" into a CJK glyph
            // (e.g. OBLIVIØN -> OBLIVI豐) and split otherwise identical albums.
            val textWithoutKnownLatinLetters = trimmed
                .replace("Ø", "")
                .replace("ø", "")
            if (!containsSuspiciousLatin1(textWithoutKnownLatinLetters)) return trimmed

            try {
                val bytes = trimmed.toByteArray(CHARSET_ISO_8859_1)

                // 1. Try UTF-8 first
                val utf8Str = String(bytes, CHARSET_UTF_8)
                if (!utf8Str.contains('\uFFFD') && hasCjk(utf8Str)) {
                    return utf8Str
                }

                // 2. Try GB18030 / GBK
                val gbkStr = String(bytes, CHARSET_GB18030)
                if (!gbkStr.contains('\uFFFD') && hasCjk(gbkStr)) {
                    return gbkStr
                }
            } catch (ignored: Exception) {
            }
        }

        return trimmed
    }

    private fun containsSuspiciousLatin1(text: String): Boolean {
        for (ch in text) {
            // Latin-1 Supplement characters typically indicating misdecoded Chinese multi-byte sequences
            if (ch in '\u0080'..'\u00FF') {
                return true
            }
        }
        return false
    }
}
