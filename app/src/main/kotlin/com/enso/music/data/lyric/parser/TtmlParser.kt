package com.enso.music.data.lyric.parser

import com.enso.music.data.lyric.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object TtmlParser {

    fun parse(xmlContent: String): LyricData {
        if (xmlContent.isBlank()) return LyricData.EMPTY

        val factory = XmlPullParserFactory.newInstance().apply {
            // Set to false for ultimate resilience against missing xmlns declarations in real-world TTML files
            isNamespaceAware = false
        }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlContent))

        val lines = ArrayList<LyricLine>()
        val agents = HashMap<String, LyricAgent>()
        val sidecarTranslations = HashMap<String, String>()
        val sidecarTransliterations = HashMap<String, String>()

        val tagStack = ArrayDeque<String>()
        var currentSongPart: String? = null
        var isWordTimingRoot = false
        var lineCounter = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawName = parser.name ?: ""
                    val name = if (rawName.contains(':')) rawName.substringAfter(':') else rawName
                    val parentName = tagStack.lastOrNull()
                    tagStack.addLast(name)

                    when {
                        name.equals("tt", ignoreCase = true) -> {
                            val timingAttr = parser.getAttributeByLocalName("timing")
                            if ("Word".equals(timingAttr, ignoreCase = true)) {
                                isWordTimingRoot = true
                            }
                        }
                        name.equals("agent", ignoreCase = true) -> {
                            parseAgentTag(parser, agents)
                        }
                        name.equals("text", ignoreCase = true) -> {
                            // Check for sidecar translations in <iTunesMetadata>
                            val forKey = parser.getAttributeByLocalName("for")
                            val textContent = parser.nextText()
                            if (tagStack.isNotEmpty() && tagStack.last().equals("text", ignoreCase = true)) {
                                tagStack.removeLast()
                            }
                            if (!forKey.isNullOrBlank() && textContent.isNotBlank()) {
                                if ("transliteration".equals(parentName, ignoreCase = true)) {
                                    sidecarTransliterations[forKey] = textContent.trim()
                                } else {
                                    sidecarTranslations[forKey] = textContent.trim()
                                }
                            }
                        }
                        name.equals("div", ignoreCase = true) -> {
                            currentSongPart = parser.getAttributeByLocalName("song-part")
                                ?: parser.getAttributeByLocalName("songPart")
                        }
                        name.equals("p", ignoreCase = true) -> {
                            val parsedLine = parseParagraph(
                                parser = parser,
                                lineIndex = lineCounter++,
                                defaultSongPart = currentSongPart,
                                sidecarTranslations = sidecarTranslations,
                                sidecarTransliterations = sidecarTransliterations,
                                knownAgents = agents
                            )
                            if (tagStack.isNotEmpty() && tagStack.last().equals("p", ignoreCase = true)) {
                                tagStack.removeLast()
                            }
                            if (parsedLine != null) {
                                lines.add(parsedLine)
                                currentSongPart = null
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val rawName = parser.name ?: ""
                    val name = if (rawName.contains(':')) rawName.substringAfter(':') else rawName
                    if (tagStack.isNotEmpty() && tagStack.last().equals(name, ignoreCase = true)) {
                        tagStack.removeLast()
                    }
                    if (name.equals("div", ignoreCase = true)) {
                        currentSongPart = null
                    }
                }
            }
            eventType = parser.next()
        }

        // Sort lines by startTime
        lines.sortBy { it.startTimeMs }

        // Recalculate end times if 0 or invalid
        for (i in lines.indices) {
            val cur = lines[i]
            if (cur.endTimeMs <= cur.startTimeMs) {
                val nextStart = if (i + 1 < lines.size) lines[i + 1].startTimeMs else cur.startTimeMs + 4000L
                lines[i] = cur.copy(endTimeMs = kotlin.math.max(cur.startTimeMs + 1000L, nextStart))
            }
        }

        val hasWordTiming = isWordTimingRoot || lines.any { it.isWordTimed }
        val hasTrans = lines.any { !it.translation.isNullOrBlank() }
        val hasRoman = lines.any { !it.transliteration.isNullOrBlank() }

        return LyricData(
            lines = lines,
            hasWordTiming = hasWordTiming,
            hasTranslation = hasTrans,
            hasTransliteration = hasRoman,
            agents = agents
        )
    }

    private fun parseAgentTag(parser: XmlPullParser, agents: HashMap<String, LyricAgent>) {
        val id = parser.getAttributeByLocalName("id")
            ?: parser.getAttributeValue(null, "xml:id")
            ?: parser.getAttributeValue(null, "id")
        val type = parser.getAttributeByLocalName("type")
        val name = try {
            parser.nextText()
        } catch (e: Exception) {
            null
        }
        if (!id.isNullOrBlank()) {
            agents[id] = LyricAgent(id = id, name = name?.trim(), type = type?.trim())
        }
    }

    private fun parseParagraph(
        parser: XmlPullParser,
        lineIndex: Int,
        defaultSongPart: String?,
        sidecarTranslations: Map<String, String>,
        sidecarTransliterations: Map<String, String>,
        knownAgents: Map<String, LyricAgent>
    ): LyricLine? {
        val beginAttr = parser.getAttributeByLocalName("begin")
        val endAttr = parser.getAttributeByLocalName("end")
        val durAttr = parser.getAttributeByLocalName("dur")
        val agentAttr = parser.getAttributeByLocalName("agent")
        val keyAttr = parser.getAttributeByLocalName("key")
            ?: parser.getAttributeByLocalName("id")

        val pStart = TimeUtils.parseTimeToMs(beginAttr) ?: 0L
        val pEnd = TimeUtils.parseTimeToMs(endAttr)
            ?: (durAttr?.let { TimeUtils.parseTimeToMs(it)?.let { d -> pStart + d } } ?: 0L)

        val words = ArrayList<LyricWord>()
        val bgVocals = ArrayList<LyricBgVocal>()
        val rubyList = ArrayList<LyricRuby>()
        val mainTextBuilder = StringBuilder()

        var inlineTranslation: String? = null
        var inlineTransliteration: String? = null

        val alignment = resolveAlignment(agentAttr)

        var eventType = parser.next()
        var currentRubyBase: String? = null
        var currentRubyText: String? = null
        var currentRubyStartMs: Long? = null
        var currentRubyEndMs: Long? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawTag = parser.name ?: ""
                    val tagName = if (rawTag.contains(':')) rawTag.substringAfter(':') else rawTag
                    if (tagName.equals("span", ignoreCase = true)) {
                        val role = parser.getAttributeByLocalName("role")
                        val rubyType = parser.getAttributeByLocalName("ruby")

                        when {
                            "x-translation".equals(role, ignoreCase = true) -> {
                                inlineTranslation = parser.nextText()
                            }
                            "x-roman".equals(role, ignoreCase = true) -> {
                                inlineTransliteration = parser.nextText()
                            }
                            "x-bg".equals(role, ignoreCase = true) -> {
                                val bgVocal = parseBgSpan(parser, pStart, pEnd)
                                if (bgVocal != null) {
                                    bgVocals.add(bgVocal)
                                }
                            }
                            "container".equals(rubyType, ignoreCase = true) -> {
                                // Container start
                                currentRubyBase = null
                                currentRubyText = null
                                currentRubyStartMs = null
                                currentRubyEndMs = null
                            }
                            "base".equals(rubyType, ignoreCase = true) -> {
                                val baseBegin = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("begin")) ?: pStart
                                val baseEnd = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("end")) ?: pEnd
                                val baseText = parser.nextText()
                                currentRubyBase = baseText
                                currentRubyStartMs = baseBegin
                                currentRubyEndMs = baseEnd

                                val startIndex = mainTextBuilder.length
                                mainTextBuilder.append(baseText)
                                val endIndex = mainTextBuilder.length

                                words.add(LyricWord(startTimeMs = baseBegin, endTimeMs = baseEnd, text = baseText))

                                // If rubyText was parsed before or after, record it
                                if (!currentRubyText.isNullOrEmpty()) {
                                    rubyList.add(
                                        LyricRuby(
                                            baseText = baseText,
                                            rubyText = currentRubyText!!,
                                            startIndex = startIndex,
                                            endIndex = endIndex,
                                            startTimeMs = baseBegin,
                                            endTimeMs = baseEnd
                                        )
                                    )
                                }
                            }
                            "text".equals(rubyType, ignoreCase = true) || "textContainer".equals(rubyType, ignoreCase = true) -> {
                                val textBegin = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("begin")) ?: currentRubyStartMs ?: pStart
                                val textEnd = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("end")) ?: currentRubyEndMs ?: pEnd
                                val rubyTxt = parser.nextText()
                                currentRubyText = rubyTxt

                                if (!currentRubyBase.isNullOrEmpty()) {
                                    val startIndex = (mainTextBuilder.length - currentRubyBase!!.length).coerceAtLeast(0)
                                    val endIndex = mainTextBuilder.length
                                    rubyList.add(
                                        LyricRuby(
                                            baseText = currentRubyBase!!,
                                            rubyText = rubyTxt,
                                            startIndex = startIndex,
                                            endIndex = endIndex,
                                            startTimeMs = textBegin,
                                            endTimeMs = textEnd
                                        )
                                    )
                                }
                            }
                            else -> {
                                // Regular word span
                                val spanBegin = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("begin")) ?: pStart
                                val spanEnd = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("end"))
                                    ?: (parser.getAttributeByLocalName("dur")?.let { d ->
                                        TimeUtils.parseTimeToMs(d)?.let { spanBegin + it }
                                    } ?: pEnd)

                                val spanText = parser.nextText()
                                if (spanText.isNotEmpty()) {
                                    mainTextBuilder.append(spanText)
                                    words.add(LyricWord(startTimeMs = spanBegin, endTimeMs = spanEnd, text = spanText))
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    // TTML whitespace sensitivity: preserve inline text/whitespace inside <p>,
                    // but ignore formatting indentation with newlines
                    val raw = parser.text ?: ""
                    if (raw.isNotEmpty()) {
                        if ((raw.contains('\n') || raw.contains('\r')) && raw.trim().isEmpty()) {
                            // Ignored formatting indent
                        } else {
                            mainTextBuilder.append(raw)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val rawName = parser.name ?: ""
                    val name = if (rawName.contains(':')) rawName.substringAfter(':') else rawName
                    if (name.equals("p", ignoreCase = true)) {
                        break
                    }
                }
            }
            eventType = parser.next()
        }

        val fullText = if (words.isNotEmpty()) {
            words.joinToString("") { it.text }.trim()
        } else {
            mainTextBuilder.toString().trim()
        }
        if (fullText.isEmpty() && bgVocals.isEmpty()) {
            return null
        }

        // Sidecar overrides or fallbacks
        val finalTranslation = inlineTranslation?.trim()
            ?: keyAttr?.let { sidecarTranslations[it] }
        val finalTransliteration = inlineTransliteration?.trim()
            ?: keyAttr?.let { sidecarTransliterations[it] }

        val lineStart = if (words.isNotEmpty()) words.first().startTimeMs else pStart
        val lineEnd = if (words.isNotEmpty() && pEnd <= lineStart) {
            words.last().endTimeMs
        } else {
            pEnd
        }

        return LyricLine(
            index = lineIndex,
            startTimeMs = lineStart,
            endTimeMs = lineEnd,
            text = fullText,
            words = if (words.isNotEmpty()) words else null,
            translation = finalTranslation,
            transliteration = finalTransliteration,
            bgVocals = if (bgVocals.isNotEmpty()) bgVocals else null,
            agentId = agentAttr,
            alignment = alignment,
            songPart = defaultSongPart,
            rubyList = if (rubyList.isNotEmpty()) rubyList else null
        )
    }

    private fun parseBgSpan(parser: XmlPullParser, defaultStart: Long, defaultEnd: Long): LyricBgVocal? {
        val bgStart = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("begin")) ?: defaultStart
        val bgEnd = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("end")) ?: defaultEnd

        val bgWords = ArrayList<LyricWord>()
        val textBuilder = StringBuilder()
        var bgTranslation: String? = null
        var bgTransliteration: String? = null

        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val rawTag = parser.name ?: ""
                    val tag = if (rawTag.contains(':')) rawTag.substringAfter(':') else rawTag
                    if (tag.equals("span", ignoreCase = true)) {
                        val role = parser.getAttributeByLocalName("role")
                        when {
                            "x-translation".equals(role, ignoreCase = true) -> {
                                bgTranslation = parser.nextText()
                            }
                            "x-roman".equals(role, ignoreCase = true) -> {
                                bgTransliteration = parser.nextText()
                            }
                            else -> {
                                val sBegin = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("begin")) ?: bgStart
                                val sEnd = TimeUtils.parseTimeToMs(parser.getAttributeByLocalName("end")) ?: bgEnd
                                val sText = parser.nextText()
                                textBuilder.append(sText)
                                bgWords.add(LyricWord(startTimeMs = sBegin, endTimeMs = sEnd, text = sText))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val raw = parser.text ?: ""
                    if (raw.isNotEmpty()) {
                        if ((raw.contains('\n') || raw.contains('\r')) && raw.trim().isEmpty()) {
                            // Ignored formatting indent
                        } else {
                            textBuilder.append(raw)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val rawTag = parser.name ?: ""
                    val tag = if (rawTag.contains(':')) rawTag.substringAfter(':') else rawTag
                    if (tag.equals("span", ignoreCase = true)) {
                        break
                    }
                }
            }
            eventType = parser.next()
        }

        var cleanedText = textBuilder.toString().trim()
        // Clean outer wrapping brackets e.g. "(chorus)" -> "chorus"
        if (cleanedText.startsWith("(") && cleanedText.endsWith(")")) {
            cleanedText = cleanedText.substring(1, cleanedText.length - 1).trim()
        } else if (cleanedText.startsWith("（") && cleanedText.endsWith("）")) {
            cleanedText = cleanedText.substring(1, cleanedText.length - 1).trim()
        }

        if (cleanedText.isEmpty()) return null

        return LyricBgVocal(
            startTimeMs = bgStart,
            endTimeMs = bgEnd,
            text = cleanedText,
            words = if (bgWords.isNotEmpty()) bgWords else null,
            translation = bgTranslation?.trim(),
            transliteration = bgTransliteration?.trim()
        )
    }

    private fun resolveAlignment(agentId: String?): LyricAlignment {
        if (agentId.isNullOrBlank()) return LyricAlignment.LEFT
        val lower = agentId.lowercase()
        return when {
            lower.contains("1000") || lower.contains("all") || lower.contains("chorus") || lower.contains("group") -> {
                LyricAlignment.CENTER
            }
            lower == "v2" || lower.endsWith("2") -> {
                LyricAlignment.RIGHT
            }
            else -> {
                LyricAlignment.LEFT
            }
        }
    }

    private fun XmlPullParser.getAttributeByLocalName(name: String): String? {
        val count = attributeCount
        for (i in 0 until count) {
            val attrName = getAttributeName(i)
            if (attrName.equals(name, ignoreCase = true) || attrName.endsWith(":$name", ignoreCase = true)) {
                return getAttributeValue(i)
            }
        }
        return null
    }

    private val XmlPullParser.parentTagName: String?
        get() = try {
            // Best effort inspection
            null
        } catch (e: Exception) {
            null
        }
}
