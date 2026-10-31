package com.enso.music.ui.lyric

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.enso.music.data.lyric.model.*

class LyricLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Pre-allocated paints to guarantee ZERO allocation during onDraw
    private val paintNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FFFFFF") // 35% opacity dim white
    }
    private val paintHighlight = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF") // 100% solid white
    }

    private val paintBgNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        textSkewX = -0.2f // soft italic
    }
    private val paintBgHighlight = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CB399") // Morandi accent
        isFakeBoldText = true
        textSkewX = -0.2f
    }

    private val paintRubyNormal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
    }
    private val paintRubyHighlight = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CB399")
    }

    private val paintSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93") // Apple cold gray
    }

    private val paintPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#268CB399") // semi-transparent pill
        style = Paint.Style.FILL
    }
    private val paintPillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CB399")
        isFakeBoldText = true
    }

    // Pre-allocated rect for clipping
    private val clipRect = RectF()
    private val pillRect = RectF()

    // Configuration flags
    var showTranslation: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    var showTransliteration: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    private var lineData: LyricLine? = null
    private var currentTimeMs: Long = 0L
    private var isCurrentActiveLine: Boolean = false

    // Pre-measured coordinates and metrics
    private val wordMetricsList = ArrayList<WordMetric>()
    private val bgWordMetricsList = ArrayList<WordMetric>()
    private val rubyMetricList = ArrayList<RubyMetric>()

    private var pillWidth = 0f
    private var pillHeight = 0f
    private var mainLineBaselineY = 0f
    private var rubyBaselineY = 0f
    private var bgBaselineY = 0f
    private var romanBaselineY = 0f
    private var transBaselineY = 0f
    private var totalMainWidth = 0f
    private var measuredContentHeight = 0

    init {
        val density = resources.displayMetrics.scaledDensity
        paintNormal.textSize = 16f * density
        paintHighlight.textSize = 16f * density
        paintBgNormal.textSize = 12f * density
        paintBgHighlight.textSize = 12f * density
        paintRubyNormal.textSize = 8.5f * density
        paintRubyHighlight.textSize = 8.5f * density
        paintSub.textSize = 11.5f * density
        paintPillText.textSize = 9f * density
    }

    fun bind(line: LyricLine, isActive: Boolean, curTimeMs: Long) {
        this.lineData = line
        this.isCurrentActiveLine = isActive
        this.currentTimeMs = curTimeMs
        requestLayout()
        invalidate()
    }

    fun updateProgress(curTimeMs: Long) {
        if (this.currentTimeMs != curTimeMs) {
            this.currentTimeMs = curTimeMs
            invalidate()
        }
    }

    fun setActiveState(isActive: Boolean) {
        if (this.isCurrentActiveLine != isActive) {
            this.isCurrentActiveLine = isActive
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val line = lineData
        if (line == null) {
            setMeasuredDimension(0, 0)
            return
        }

        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val paddingHorizontal = (16f * density).toInt()
        val usableWidth = (availableWidth - paddingHorizontal * 2).coerceAtLeast(0)

        wordMetricsList.clear()
        bgWordMetricsList.clear()
        rubyMetricList.clear()

        var currentY = 8f * density

        // 1. Song part pill
        val part = line.songPart
        if (!part.isNullOrBlank()) {
            val pillText = part.uppercase()
            val textW = paintPillText.measureText(pillText)
            pillWidth = textW + 12f * density
            pillHeight = 16f * density
            currentY += pillHeight + 4f * density
        } else {
            pillWidth = 0f
            pillHeight = 0f
        }

        // 2. Ruby height offset
        val hasRuby = !line.rubyList.isNullOrEmpty()
        if (hasRuby) {
            val rubyH = paintRubyNormal.fontMetrics.let { it.descent - it.ascent }
            rubyBaselineY = currentY - paintRubyNormal.fontMetrics.ascent
            currentY += rubyH + 2f * density
        }

        // 3. Main lyric line
        val mainFm = if (isCurrentActiveLine) paintHighlight.fontMetrics else paintNormal.fontMetrics
        val mainLineHeight = mainFm.descent - mainFm.ascent
        mainLineBaselineY = currentY - mainFm.ascent
        currentY += mainLineHeight

        // Measure main words or full text
        val activePaint = if (isCurrentActiveLine) paintHighlight else paintNormal
        var totalMainWidth = 0f

        val words = line.words
        if (!words.isNullOrEmpty()) {
            for (w in words) {
                val wWidth = activePaint.measureText(w.text)
                wordMetricsList.add(WordMetric(w.startTimeMs, w.endTimeMs, w.text, 0f, wWidth))
                totalMainWidth += wWidth
            }
        } else {
            totalMainWidth = activePaint.measureText(line.text)
        }

        this.totalMainWidth = totalMainWidth

        // Calculate alignment start X
        val mainStartX = when (line.alignment) {
            LyricAlignment.LEFT -> paddingHorizontal.toFloat()
            LyricAlignment.RIGHT -> {
                if (totalMainWidth > usableWidth) {
                    paddingHorizontal.toFloat()
                } else {
                    availableWidth - paddingHorizontal - totalMainWidth
                }
            }
            LyricAlignment.CENTER -> {
                if (totalMainWidth > usableWidth) {
                    paddingHorizontal.toFloat()
                } else {
                    (availableWidth - totalMainWidth) / 2f
                }
            }
        }

        // Position words
        var runningX = mainStartX
        for (wm in wordMetricsList) {
            wm.startX = runningX
            runningX += wm.width
        }

        // Position Ruby
        if (hasRuby) {
            for (r in line.rubyList!!) {
                val rubyW = paintRubyNormal.measureText(r.rubyText)
                // Approximate base character position
                val baseLeft = if (wordMetricsList.isNotEmpty()) {
                    wordMetricsList.find { it.text.contains(r.baseText) }?.startX ?: mainStartX
                } else {
                    mainStartX
                }
                val baseW = paintNormal.measureText(r.baseText)
                val rubyX = baseLeft + (baseW - rubyW) / 2f
                rubyMetricList.add(RubyMetric(r.baseText, r.rubyText, rubyX, rubyW, r.startTimeMs, r.endTimeMs))
            }
        }

        // 4. Background vocals (x-bg)
        val bgVocals = line.bgVocals
        if (!bgVocals.isNullOrEmpty()) {
            currentY += 4f * density
            val bgFm = if (isCurrentActiveLine) paintBgHighlight.fontMetrics else paintBgNormal.fontMetrics
            val bgLineH = bgFm.descent - bgFm.ascent
            bgBaselineY = currentY - bgFm.ascent
            currentY += bgLineH

            val bgActivePaint = if (isCurrentActiveLine) paintBgHighlight else paintBgNormal
            for (bg in bgVocals) {
                val bgWords = bg.words
                var totalBgWidth = 0f
                if (!bgWords.isNullOrEmpty()) {
                    for (bw in bgWords) {
                        val bwW = bgActivePaint.measureText(bw.text)
                        bgWordMetricsList.add(WordMetric(bw.startTimeMs, bw.endTimeMs, bw.text, 0f, bwW))
                        totalBgWidth += bwW
                    }
                } else {
                    totalBgWidth = bgActivePaint.measureText(bg.text)
                }

                val bgStartX = when (line.alignment) {
                    LyricAlignment.LEFT -> paddingHorizontal.toFloat()
                    LyricAlignment.RIGHT -> {
                        if (totalBgWidth > usableWidth) {
                            paddingHorizontal.toFloat()
                        } else {
                            availableWidth - paddingHorizontal - totalBgWidth
                        }
                    }
                    LyricAlignment.CENTER -> {
                        if (totalBgWidth > usableWidth) {
                            paddingHorizontal.toFloat()
                        } else {
                            (availableWidth - totalBgWidth) / 2f
                        }
                    }
                }

                var rX = bgStartX
                for (bwm in bgWordMetricsList) {
                    bwm.startX = rX
                    rX += bwm.width
                }
            }
        }

        // 5. Transliteration (Romanized)
        val roman = line.transliteration
        if (showTransliteration && !roman.isNullOrBlank()) {
            currentY += 3f * density
            val subFm = paintSub.fontMetrics
            romanBaselineY = currentY - subFm.ascent
            currentY += (subFm.descent - subFm.ascent)
        }

        // 6. Translation
        val trans = line.translation
        if (showTranslation && !trans.isNullOrBlank()) {
            currentY += 3f * density
            val subFm = paintSub.fontMetrics
            transBaselineY = currentY - subFm.ascent
            currentY += (subFm.descent - subFm.ascent)
        }

        currentY += 8f * density
        measuredContentHeight = currentY.toInt()

        setMeasuredDimension(availableWidth, measuredContentHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val line = lineData ?: return
        val density = resources.displayMetrics.density
        val paddingHorizontal = 16f * density

        // 1. Draw song part pill
        val part = line.songPart
        if (!part.isNullOrBlank() && pillWidth > 0) {
            val pillLeft = when (line.alignment) {
                LyricAlignment.LEFT -> paddingHorizontal
                LyricAlignment.RIGHT -> width - paddingHorizontal - pillWidth
                LyricAlignment.CENTER -> (width - pillWidth) / 2f
            }
            pillRect.set(pillLeft, 4f * density, pillLeft + pillWidth, 4f * density + pillHeight)
            canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, paintPillBg)
            val pFm = paintPillText.fontMetrics
            val pTextY = pillRect.centerY() - (pFm.ascent + pFm.descent) / 2f
            canvas.drawText(part.uppercase(), pillLeft + 6f * density, pTextY, paintPillText)
        }

        val usableWidth = (width - 2 * paddingHorizontal).coerceAtLeast(0f)
        val maxScroll = (totalMainWidth - usableWidth).coerceAtLeast(0f)
        val lineProgress = if (maxScroll > 0f && isCurrentActiveLine) {
            val dur = (line.endTimeMs - line.startTimeMs).coerceAtLeast(1L)
            ((currentTimeMs - line.startTimeMs).toFloat() / dur).coerceIn(0f, 1f)
        } else {
            0f
        }
        val scrollOffset = maxScroll * lineProgress

        // 2. Draw Ruby annotations (wrapped in scroll translation)
        canvas.save()
        if (scrollOffset > 0f) {
            canvas.translate(-scrollOffset, 0f)
        }
        for (rm in rubyMetricList) {
            val isRubyActive = isCurrentActiveLine && currentTimeMs in rm.startTimeMs..rm.endTimeMs
            val p = if (isRubyActive) paintRubyHighlight else paintRubyNormal
            canvas.drawText(rm.rubyText, rm.x, rubyBaselineY, p)
        }

        // 3. Draw main lyric line
        if (wordMetricsList.isNotEmpty()) {
            // Word-level karaoke rendering with Zero GC
            for (wm in wordMetricsList) {
                val isSung = isCurrentActiveLine && currentTimeMs >= wm.endTimeMs
                val isSinging = isCurrentActiveLine && (currentTimeMs in wm.startTimeMs until wm.endTimeMs)

                if (isSung) {
                    // Fully sung word -> highlight
                    canvas.drawText(wm.text, wm.startX, mainLineBaselineY, paintHighlight)
                } else if (isSinging) {
                    // Singing word -> linear clip fill
                    val dur = (wm.endTimeMs - wm.startTimeMs).coerceAtLeast(1L)
                    val progress = ((currentTimeMs - wm.startTimeMs).toFloat() / dur).coerceIn(0f, 1f)

                    // Draw base dim text first
                    canvas.drawText(wm.text, wm.startX, mainLineBaselineY, paintNormal)

                    // Clip highlight layer
                    canvas.save()
                    clipRect.set(wm.startX, mainLineBaselineY - paintHighlight.textSize, wm.startX + wm.width * progress, mainLineBaselineY + 10f)
                    canvas.clipRect(clipRect)
                    canvas.drawText(wm.text, wm.startX, mainLineBaselineY, paintHighlight)
                    canvas.restore()
                } else {
                    // Not reached yet -> normal dim
                    canvas.drawText(wm.text, wm.startX, mainLineBaselineY, paintNormal)
                }
            }
        } else {
            // Line-level rendering
            val p = if (isCurrentActiveLine) paintHighlight else paintNormal
            val lineW = p.measureText(line.text)
            val lineStartX = when (line.alignment) {
                LyricAlignment.LEFT -> paddingHorizontal
                LyricAlignment.RIGHT -> {
                    if (lineW > usableWidth) paddingHorizontal else width - paddingHorizontal - lineW
                }
                LyricAlignment.CENTER -> {
                    if (lineW > usableWidth) paddingHorizontal else (width - lineW) / 2f
                }
            }
            canvas.drawText(line.text, lineStartX, mainLineBaselineY, p)
        }
        canvas.restore()

        // 4. Draw background vocals (x-bg)
        if (bgWordMetricsList.isNotEmpty()) {
            for (bwm in bgWordMetricsList) {
                val isSung = isCurrentActiveLine && currentTimeMs >= bwm.endTimeMs
                val isSinging = isCurrentActiveLine && (currentTimeMs in bwm.startTimeMs until bwm.endTimeMs)

                if (isSung) {
                    canvas.drawText(bwm.text, bwm.startX, bgBaselineY, paintBgHighlight)
                } else if (isSinging) {
                    val dur = (bwm.endTimeMs - bwm.startTimeMs).coerceAtLeast(1L)
                    val progress = ((currentTimeMs - bwm.startTimeMs).toFloat() / dur).coerceIn(0f, 1f)

                    canvas.drawText(bwm.text, bwm.startX, bgBaselineY, paintBgNormal)
                    canvas.save()
                    clipRect.set(bwm.startX, bgBaselineY - paintBgHighlight.textSize, bwm.startX + bwm.width * progress, bgBaselineY + 10f)
                    canvas.clipRect(clipRect)
                    canvas.drawText(bwm.text, bwm.startX, bgBaselineY, paintBgHighlight)
                    canvas.restore()
                } else {
                    canvas.drawText(bwm.text, bwm.startX, bgBaselineY, paintBgNormal)
                }
            }
        } else if (!line.bgVocals.isNullOrEmpty()) {
            val bg = line.bgVocals.first()
            val p = if (isCurrentActiveLine) paintBgHighlight else paintBgNormal
            val bgText = "(${bg.text})"
            val bgW = p.measureText(bgText)
            val bgMaxScroll = (bgW - usableWidth).coerceAtLeast(0f)
            val bgScroll = if (isCurrentActiveLine) bgMaxScroll * lineProgress else 0f
            val bgStartX = when (line.alignment) {
                LyricAlignment.LEFT -> paddingHorizontal - bgScroll
                LyricAlignment.RIGHT -> if (bgW > usableWidth) paddingHorizontal - bgScroll else width - paddingHorizontal - bgW
                LyricAlignment.CENTER -> if (bgW > usableWidth) paddingHorizontal - bgScroll else (width - bgW) / 2f
            }
            canvas.drawText(bgText, bgStartX, bgBaselineY, p)
        }

        // 5. Draw Transliteration
        val roman = line.transliteration
        if (showTransliteration && !roman.isNullOrBlank()) {
            val romanW = paintSub.measureText(roman)
            val romanMaxScroll = (romanW - usableWidth).coerceAtLeast(0f)
            val romanScroll = if (isCurrentActiveLine) romanMaxScroll * lineProgress else 0f
            val romanStartX = when (line.alignment) {
                LyricAlignment.LEFT -> paddingHorizontal - romanScroll
                LyricAlignment.RIGHT -> if (romanW > usableWidth) paddingHorizontal - romanScroll else width - paddingHorizontal - romanW
                LyricAlignment.CENTER -> if (romanW > usableWidth) paddingHorizontal - romanScroll else (width - romanW) / 2f
            }
            canvas.drawText(roman, romanStartX, romanBaselineY, paintSub)
        }

        // 6. Draw Translation
        val trans = line.translation
        if (showTranslation && !trans.isNullOrBlank()) {
            val transW = paintSub.measureText(trans)
            val transMaxScroll = (transW - usableWidth).coerceAtLeast(0f)
            val transScroll = if (isCurrentActiveLine) transMaxScroll * lineProgress else 0f
            val transStartX = when (line.alignment) {
                LyricAlignment.LEFT -> paddingHorizontal - transScroll
                LyricAlignment.RIGHT -> if (transW > usableWidth) paddingHorizontal - transScroll else width - paddingHorizontal - transW
                LyricAlignment.CENTER -> if (transW > usableWidth) paddingHorizontal - transScroll else (width - transW) / 2f
            }
            canvas.drawText(trans, transStartX, transBaselineY, paintSub)
        }
    }

    private class WordMetric(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val text: String,
        var startX: Float,
        val width: Float
    )

    private class RubyMetric(
        val baseText: String,
        val rubyText: String,
        val x: Float,
        val width: Float,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
}
