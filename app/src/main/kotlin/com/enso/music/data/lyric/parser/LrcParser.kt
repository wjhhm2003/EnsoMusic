package com.enso.music.data.lyric.parser

import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.model.LyricLine
import com.enso.music.data.lyric.model.LyricWord
import java.io.BufferedReader
import java.io.StringReader

object LrcParser {

    private val LINE_TAG_REGEX = Regex("""\[(\d{1,2}:\d{2}(?:\.\d{1,3})?)]""")
    private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val ENHANCED_WORD_REGEX = Regex("""<(\d{1,2}:\d{2}(?:\.\d{1,3})?)>([^<]*)""")

    fun parse(lrcContent: String): LyricData {
        if (lrcContent.isBlank()) return LyricData.EMPTY

        var globalOffsetMs = 0L
        val rawEntries = ArrayList<RawLineEntry>()

        val reader = BufferedReader(StringReader(lrcContent))
        var lineStr = reader.readLine()

        while (lineStr != null) {
            val trimmed = lineStr.trim()
            if (trimmed.isNotEmpty()) {
                val offsetMatch = OFFSET_REGEX.find(trimmed)
                if (offsetMatch != null) {
                    globalOffsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                } else {
                    parseLineIntoEntries(trimmed, rawEntries)
                }
            }
            lineStr = reader.readLine()
        }

        if (rawEntries.isEmpty()) return LyricData.EMPTY

        // Apply offset
        if (globalOffsetMs != 0L) {
            for (i in rawEntries.indices) {
                val e = rawEntries[i]
                rawEntries[i] = e.copy(
                    timeMs = (e.timeMs + globalOffsetMs).coerceAtLeast(0L),
                    words = e.words?.map { w ->
                        w.copy(
                            startTimeMs = (w.startTimeMs + globalOffsetMs).coerceAtLeast(0L),
                            endTimeMs = (w.endTimeMs + globalOffsetMs).coerceAtLeast(0L)
                        )
                    }
                )
            }
        }

        // Sort by timestamp
        rawEntries.sortBy { it.timeMs }

        // Merge duplicate timestamps (Main Lyric + Translation)
        val mergedLines = ArrayList<LyricLine>()
        var i = 0
        var lineIndex = 0
        var hasWordTiming = false
        var hasTranslation = false

        while (i < rawEntries.size) {
            val current = rawEntries[i]
            var translationText: String? = null

            // Lookahead for translation line with the identical or near-identical timestamp (within 50ms)
            if (i + 1 < rawEntries.size) {
                val next = rawEntries[i + 1]
                if (kotlin.math.abs(next.timeMs - current.timeMs) <= 50L &&
                    current.words.isNullOrEmpty() && next.words.isNullOrEmpty()
                ) {
                    translationText = next.text
                    hasTranslation = true
                    i++ // consume translation line
                }
            }

            if (!current.words.isNullOrEmpty()) {
                hasWordTiming = true
            }

            val endTimeMs = if (i + 1 < rawEntries.size) {
                rawEntries[i + 1].timeMs
            } else {
                current.timeMs + 5000L
            }

            // Fix word end times for the last word if needed
            val adjustedWords = current.words?.let { words ->
                if (words.isNotEmpty()) {
                    val last = words.last()
                    if (last.endTimeMs <= last.startTimeMs) {
                        words.toMutableList().also { list ->
                            list[list.lastIndex] = last.copy(endTimeMs = kotlin.math.max(last.startTimeMs + 400L, endTimeMs))
                        }
                    } else words
                } else null
            }

            mergedLines.add(
                LyricLine(
                    index = lineIndex++,
                    startTimeMs = current.timeMs,
                    endTimeMs = endTimeMs,
                    text = current.text,
                    words = adjustedWords,
                    translation = translationText
                )
            )
            i++
        }

        return LyricData(
            lines = mergedLines,
            hasWordTiming = hasWordTiming,
            hasTranslation = hasTranslation,
            offsetMs = globalOffsetMs
        )
    }

    private fun parseLineIntoEntries(line: String, output: ArrayList<RawLineEntry>) {
        val lineMatches = LINE_TAG_REGEX.findAll(line).toList()
        if (lineMatches.isEmpty()) return

        val timestamps = lineMatches.mapNotNull { TimeUtils.parseTimeToMs(it.groupValues[1]) }
        if (timestamps.isEmpty()) return

        val lastMatch = lineMatches.last()
        val content = line.substring(lastMatch.range.last + 1).trim()
        if (content.isEmpty()) return

        // Check if this line has word-level timestamps (Enhanced LRC with <mm:ss.xx>)
        val wordMatches = ENHANCED_WORD_REGEX.findAll(content).toList()
        val words = if (wordMatches.isNotEmpty()) {
            val list = ArrayList<LyricWord>()
            for (idx in wordMatches.indices) {
                val wm = wordMatches[idx]
                val wordTime = TimeUtils.parseTimeToMs(wm.groupValues[1]) ?: continue
                val wordText = wm.groupValues[2]
                val nextTime = if (idx + 1 < wordMatches.size) {
                    TimeUtils.parseTimeToMs(wordMatches[idx + 1].groupValues[1]) ?: (wordTime + 300L)
                } else {
                    wordTime + 500L
                }
                list.add(LyricWord(startTimeMs = wordTime, endTimeMs = nextTime, text = wordText))
            }
            if (list.isNotEmpty()) list else null
        } else {
            null
        }

        // Clean plain text representation
        val plainText = if (words != null) {
            words.joinToString("") { it.text }.trim()
        } else {
            content
        }

        for (ts in timestamps) {
            output.add(RawLineEntry(timeMs = ts, text = plainText, words = words))
        }
    }

    private data class RawLineEntry(
        val timeMs: Long,
        val text: String,
        val words: List<LyricWord>? = null
    )
}
