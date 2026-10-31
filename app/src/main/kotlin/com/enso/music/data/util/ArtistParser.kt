package com.enso.music.data.util

object ArtistParser {

    /**
     * Regex matching artist delimiters:
     * - Symbols: / \ & 、 ; , ， ； ｜ |
     * - Word connectors: feat. ft. vs. with (case-insensitive, with optional dot or word boundary)
     */
    private val DELIMITER_REGEX = Regex(
        """\s*(?:[/\\&、;,，；｜|]+|\b(?:feat|ft|vs|with)(?:\.|\b))\s*""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank()) {
            return listOf("未知歌手")
        }

        val tokens = rawArtist.split(DELIMITER_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return if (tokens.isEmpty()) listOf("未知歌手") else tokens
    }
}
