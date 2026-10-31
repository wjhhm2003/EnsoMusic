package com.enso.music.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.enso.music.R
import kotlin.math.abs

/**
 * Vertical alphabet index sidebar for fast jumping in song/item lists.
 * Renders letters A–Z and # along the right edge and notifies listeners
 * on touch down, move, and up.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        val LETTERS = arrayOf(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
            "U", "V", "W", "X", "Y", "Z", "#"
        )
    }

    private var currentIndex = -1

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val normalColor: Int by lazy {
        ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val highlightColor: Int by lazy {
        ContextCompat.getColor(context, R.color.accent_green)
    }

    var onLetterSelectedListener: ((letter: String) -> Unit)? = null
    var onTouchEndListener: (() -> Unit)? = null
    var onGridTriggerListener: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun setOnLetterSelected(listener: (letter: String) -> Unit) {
        this.onLetterSelectedListener = listener
    }

    fun setOnTouchEnd(listener: () -> Unit) {
        this.onTouchEndListener = listener
    }

    fun setOnGridTrigger(listener: () -> Unit) {
        this.onGridTriggerListener = listener
    }

    fun getCurrentLetter(): String? =
        if (currentIndex in LETTERS.indices) LETTERS[currentIndex] else null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableHeight = height - paddingTop - paddingBottom
        if (availableHeight <= 0 || width <= 0) return

        val singleHeight = availableHeight.toFloat() / LETTERS.size
        val x = width / 2f

        for (i in LETTERS.indices) {
            val letter = LETTERS[i]
            val isSelected = (i == currentIndex)

            if (isSelected) {
                textPaint.color = highlightColor
                textPaint.isFakeBoldText = true
                textPaint.textSize = (singleHeight * 0.82f).coerceIn(spToPx(8f), spToPx(13f))
            } else {
                textPaint.color = normalColor
                textPaint.isFakeBoldText = false
                textPaint.textSize = (singleHeight * 0.72f).coerceIn(spToPx(7f), spToPx(11f))
            }

            val fontMetrics = textPaint.fontMetrics
            val baseline = paddingTop + singleHeight * i + (singleHeight - fontMetrics.bottom + fontMetrics.top) / 2f - fontMetrics.top
            canvas.drawText(letter, x, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                handleTouchPosition(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchPosition(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val upTime = System.currentTimeMillis()
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val isQuickTap = (upTime - downTime < 350) && (dx < spToPx(16f)) && (dy < spToPx(16f))

                currentIndex = -1
                onTouchEndListener?.invoke()
                invalidate()

                if (isQuickTap) {
                    performClick()
                    onGridTriggerListener?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchPosition(touchY: Float) {
        val availableHeight = height - paddingTop - paddingBottom
        if (availableHeight <= 0) return

        val singleHeight = availableHeight.toFloat() / LETTERS.size
        val rawIndex = ((touchY - paddingTop) / singleHeight).toInt()
        val index = rawIndex.coerceIn(0, LETTERS.size - 1)

        if (index != currentIndex) {
            currentIndex = index
            val letter = LETTERS[index]
            onLetterSelectedListener?.invoke(letter)
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
