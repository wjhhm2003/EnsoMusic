package com.enso.music.ui.lyric

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.enso.music.data.lyric.model.LyricData
import com.enso.music.data.lyric.model.LyricLine

class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val recyclerView = RecyclerView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.BLACK)
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null // Disable default change animations for zero lag
    }

    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
    private val adapter = LyricAdapter()

    var onLineClickListener: ((startTimeMs: Long) -> Unit)? = null

    private var lyricData: LyricData = LyricData.EMPTY
    private val currentActiveIndices = HashSet<Int>()
    // Reused per tick so the 30 fps karaoke loop does not allocate.
    private val scratchActiveSet = HashSet<Int>()
    private var lastPositionMs = 0L
    private var currentFocusIndex = -1
    private var isUserScrolling = false
    private var lastUserTouchTime = 0L
    private val userScrollTimeoutMs = 3000L

    // Translation toggle mode: 0 = All on, 1 = Only main text, 2 = Main + Translation only
    var displayMode = 0
        private set

    init {
        setBackgroundColor(Color.BLACK)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        addView(recyclerView)

        // Set generous top and bottom padding so the first and last lines can scroll into the center
        recyclerView.post {
            val h = height
            if (h > 0) {
                val padTop = (h * 0.35f).toInt()
                val padBottom = (h * 0.50f).toInt()
                recyclerView.setPadding(0, padTop, 0, padBottom)
                recyclerView.clipToPadding = false
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserScrolling = true
                    lastUserTouchTime = System.currentTimeMillis()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    lastUserTouchTime = System.currentTimeMillis()
                }
            }
        })
    }

    fun setLyricData(data: LyricData) {
        this.lyricData = data
        this.currentFocusIndex = -1
        this.currentActiveIndices.clear()
        this.lastPositionMs = 0L
        adapter.submitList(data.lines)
        if (data.lines.isNotEmpty()) {
            recyclerView.scrollToPosition(0)
        }
    }

    /**
     * Cycles translation & transliteration mode:
     * 0: All shown
     * 1: Only original
     * 2: Original + Translation (no roman)
     */
    fun cycleDisplayMode(): String {
        displayMode = (displayMode + 1) % 3
        val (showTrans, showRoman, label) = when (displayMode) {
            0 -> Triple(true, true, "译/音")
            1 -> Triple(false, false, "原词")
            else -> Triple(true, false, "仅译")
        }
        adapter.updateDisplayFlags(showTrans, showRoman)
        return label
    }

    /**
     * Ultra-low power progress updater.
     * Only invalidates the active line view and smooth-scrolls if appropriate.
     */
    /**
     * Ultra-low power progress updater with Multi-line Overlap support.
     * When duet / multi-vocal lines overlap in time, all active lines remain highlighted
     * and animated simultaneously. The scroll focus stays on the earliest active line
     * (minHighlighted in AMLL) so that playback never prematurely jumps to the next line.
     */
    fun updatePlaybackProgress(currentPositionMs: Long) {
        if (lyricData.isEmpty()) return

        val activeList = lyricData.getActiveLineIndices(currentPositionMs)
        val focusIndex = lyricData.findActiveLineIndex(currentPositionMs)
        val now = System.currentTimeMillis()
        if (isUserScrolling && (now - lastUserTouchTime > userScrollTimeoutMs)) {
            isUserScrolling = false
        }

        lastPositionMs = currentPositionMs
        val newActiveSet = scratchActiveSet
        newActiveSet.clear()
        for (i in activeList.indices) newActiveSet.add(activeList[i])

        // 1. Deactivate lines that have ended
        for (idx in currentActiveIndices) {
            if (!newActiveSet.contains(idx)) {
                if (idx in 0 until adapter.itemCount) {
                    val holder = recyclerView.findViewHolderForAdapterPosition(idx) as? LyricViewHolder
                    holder?.lineView?.setActiveState(false)
                }
            }
        }

        // 2. Activate newly started lines
        for (idx in newActiveSet) {
            if (!currentActiveIndices.contains(idx)) {
                if (idx in 0 until adapter.itemCount) {
                    val holder = recyclerView.findViewHolderForAdapterPosition(idx) as? LyricViewHolder
                    holder?.lineView?.setActiveState(true)
                }
            }
        }

        // 3. Update word-timing karaoke progress on all currently active lines
        for (idx in newActiveSet) {
            if (idx in 0 until adapter.itemCount) {
                val holder = recyclerView.findViewHolderForAdapterPosition(idx) as? LyricViewHolder
                holder?.lineView?.updateProgress(currentPositionMs)
            }
        }

        currentActiveIndices.clear()
        currentActiveIndices.addAll(newActiveSet)

        // 4. Smooth-scroll only when focus line changes and user is not manually scrolling
        if (focusIndex != currentFocusIndex) {
            currentFocusIndex = focusIndex
            if (!isUserScrolling && focusIndex in 0 until adapter.itemCount) {
                smoothScrollToLine(focusIndex)
            }
        }
    }

    private fun smoothScrollToLine(position: Int) {
        val scroller = object : LinearSmoothScroller(context) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                val targetCenter = boxStart + ((boxEnd - boxStart) * 0.36f).toInt()
                val viewCenter = (viewStart + viewEnd) / 2
                return targetCenter - viewCenter
            }

            override fun calculateTimeForScrolling(dx: Int): Int {
                // Quick and responsive scroll
                return (super.calculateTimeForScrolling(dx) * 0.8f).toInt()
            }
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    inner class LyricAdapter : RecyclerView.Adapter<LyricViewHolder>() {
        private var lines: List<LyricLine> = emptyList()
        private var showTranslation = true
        private var showTransliteration = true

        fun submitList(newLines: List<LyricLine>) {
            this.lines = newLines
            notifyDataSetChanged()
        }

        fun updateDisplayFlags(showTrans: Boolean, showRoman: Boolean) {
            this.showTranslation = showTrans
            this.showTransliteration = showRoman
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
            val lineView = LyricLineView(parent.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            return LyricViewHolder(lineView)
        }

        override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
            val line = lines[position]
            val isActive = currentActiveIndices.contains(position)
            holder.lineView.showTranslation = showTranslation
            holder.lineView.showTransliteration = showTransliteration
            // Bind with the live position so a recycled active line keeps its
            // karaoke fill instead of flashing back to empty.
            holder.lineView.bind(line, isActive, lastPositionMs)
            holder.lineView.setOnClickListener {
                onLineClickListener?.invoke(line.startTimeMs)
            }
        }
    }

    inner class LyricViewHolder(val lineView: LyricLineView) : RecyclerView.ViewHolder(lineView)
}
