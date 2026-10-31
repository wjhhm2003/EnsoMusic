package com.enso.music.data.lyric.parser

import kotlin.math.roundToLong

object TimeUtils {

    /**
     * Parses time representations into milliseconds.
     * Supports:
     * - "MM:SS.xx" or "MM:SS.xxx" (e.g. "03:14.15", "00:05.100")
     * - "HH:MM:SS.xx" or "HH:MM:SS.xxx" (e.g. "01:03:14.150")
     * - "SS.xxs" or "SSs" (e.g. "12.34s", "45s")
     * - "SS.xxx" pure seconds (e.g. "15.42")
     */
    fun parseTimeToMs(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        val str = timeStr.trim()

        // 1. Seconds format with trailing 's' or 'S', e.g. "12.34s", "5s"
        if (str.endsWith("s", ignoreCase = true)) {
            val secPart = str.dropLast(1).trim()
            val sec = secPart.toDoubleOrNull() ?: return null
            return (sec * 1000.0).roundToLong()
        }

        // 2. Colon-separated format: HH:MM:SS.xxx or MM:SS.xxx
        if (str.contains(':')) {
            val parts = str.split(':')
            return try {
                when (parts.size) {
                    2 -> {
                        val minutes = parts[0].trim().toLong()
                        val seconds = parts[1].trim().toDouble()
                        (minutes * 60 * 1000L) + (seconds * 1000.0).roundToLong()
                    }
                    3 -> {
                        val hours = parts[0].trim().toLong()
                        val minutes = parts[1].trim().toLong()
                        val seconds = parts[2].trim().toDouble()
                        (hours * 3600 * 1000L) + (minutes * 60 * 1000L) + (seconds * 1000.0).roundToLong()
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }

        // 3. Raw seconds floating-point without 's', e.g. "14.250"
        val rawSec = str.toDoubleOrNull()
        if (rawSec != null) {
            return (rawSec * 1000.0).roundToLong()
        }

        return null
    }
}
