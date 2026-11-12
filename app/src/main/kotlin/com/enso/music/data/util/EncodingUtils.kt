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

        // Re-encoding arbitrary Unicode through Latin-1 is lossy. Only inspect
        // strings that can be represented by the original single-byte decoder.
        if (trimmed.any { it.code > 0xFF } || !containsSuspiciousLatin1(trimmed)) return trimmed

        val bytes = trimmed.toByteArray(CHARSET_ISO_8859_1)
        val utf8Str = String(bytes, CHARSET_UTF_8)
        if (!utf8Str.contains('\uFFFD') && hasCjk(utf8Str)) return utf8Str

        // Latin letters such as ã and Ø are valid metadata. GBK byte sequences
        // are ambiguous with them, so only attempt repair when C1 controls make
        // the original text clearly suspicious.
        if (trimmed.any { it in '\u0080'..'\u009F' }) {
            val gbkStr = String(bytes, CHARSET_GB18030)
            if (!gbkStr.contains('\uFFFD') && hasCjk(gbkStr)) return gbkStr
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
