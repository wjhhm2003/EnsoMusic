package com.enso.music.data.lyric.model

enum class LyricAlignment {
    LEFT,
    RIGHT,
    CENTER
}

data class LyricWord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

data class LyricRuby(
    val baseText: String,
    val rubyText: String,
    val startIndex: Int,
    val endIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long
)

data class LyricBgVocal(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val translation: String? = null,
    val transliteration: String? = null
)

data class LyricAgent(
    val id: String,
    val name: String? = null,
    val type: String? = null
)

data class LyricLine(
    val index: Int = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val words: List<LyricWord>? = null,
    val translation: String? = null,
    val transliteration: String? = null,
    val bgVocals: List<LyricBgVocal>? = null,
    val agentId: String? = null,
    val alignment: LyricAlignment = LyricAlignment.LEFT,
    val songPart: String? = null,
    val rubyList: List<LyricRuby>? = null
) {
    val isWordTimed: Boolean get() = !words.isNullOrEmpty()
}

data class LyricData(
    val lines: List<LyricLine> = emptyList(),
    val hasWordTiming: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasTransliteration: Boolean = false,
    val agents: Map<String, LyricAgent> = emptyMap(),
    val offsetMs: Long = 0L
) {
    fun isEmpty(): Boolean = lines.isEmpty()
    fun isNotEmpty(): Boolean = lines.isNotEmpty()

    /**
     * Finds the index of the line currently being sung at [positionMs].
     * Returns -1 if before the first line, or returns the last line if past the end.
     */
    /**
     * Finds the primary active line index at [positionMs] for scrolling focus.
     * When lines overlap, returns the earliest active line (minHighlighted in AMLL),
     * preventing premature jumping before the current line finishes singing.
     */
    fun findActiveLineIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().startTimeMs) return -1

        val latestStarted = latestStartedIndex(positionMs)
        if (latestStarted < 0) return -1

        // Search only the started suffix. This preserves the earliest active
        // duet line while avoiding a full-list scan on every 33 ms tick.
        for (i in latestStarted downTo 0) {
            val line = lines[i]
            if (positionMs < line.startTimeMs) break
            if (positionMs < line.endTimeMs) {
                var earliest = i
                while (earliest > 0) {
                    val previous = lines[earliest - 1]
                    if (previous.startTimeMs > positionMs || positionMs >= previous.endTimeMs) break
                    earliest--
                }
                return earliest
            }
        }

        return latestStarted
    }

    /**
     * Returns all line indices that are actively being sung at [positionMs].
     * Supports simultaneous duet / multi-vocal lines when timestamps overlap.
     */
    fun getActiveLineIndices(positionMs: Long): List<Int> {
        if (lines.isEmpty()) return emptyList()
        val latestStarted = latestStartedIndex(positionMs)
        if (latestStarted < 0) return emptyList()

        val result = ArrayList<Int>(2)
        for (i in latestStarted downTo 0) {
            val line = lines[i]
            if (line.startTimeMs > positionMs) break
            if (positionMs in line.startTimeMs until line.endTimeMs) {
                result.add(i)
            }
        }
        if (result.isEmpty()) {
            val fallback = findActiveLineIndex(positionMs)
            if (fallback != -1) {
                result.add(fallback)
            }
        } else if (result.size > 1) {
            result.reverse()
        }
        return result
    }

    private fun latestStartedIndex(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startTimeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    companion object {
        val EMPTY = LyricData()
    }
}
