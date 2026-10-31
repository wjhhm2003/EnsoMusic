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
    fun findActiveLineIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().startTimeMs) return -1
        if (positionMs >= lines.last().startTimeMs) return lines.lastIndex

        var low = 0
        var high = lines.size - 1
        var result = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]
            if (line.startTimeMs <= positionMs) {
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
