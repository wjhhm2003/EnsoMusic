package com.enso.music.service

import kotlin.random.Random

/**
 * Pure queue/ordering model for the playback service.
 *
 * Holds the playlist, the current index and a shuffle permutation, and answers
 * "what comes next / before" for every [PlayMode]. It has no Android
 * dependencies so the ordering rules can be unit tested on the JVM.
 *
 * Shuffle keeps a stable permutation: skipping forward and back walks the same
 * order instead of re-rolling, and the current song is always moved to the front
 * of a newly generated permutation so "next" never repeats it.
 */
class PlayQueue<T>(private val random: Random = Random.Default) {

    private val items = ArrayList<T>()
    private val shuffleOrder = ArrayList<Int>()
    private var shufflePointer = -1

    var currentIndex: Int = -1
        private set

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val lastIndex: Int get() = items.lastIndex

    fun snapshot(): List<T> = ArrayList(items)

    operator fun get(index: Int): T? = items.getOrNull(index)

    val current: T? get() = items.getOrNull(currentIndex)

    fun setItems(newItems: List<T>, startIndex: Int) {
        items.clear()
        items.addAll(newItems)
        currentIndex = if (startIndex in items.indices) startIndex else -1
        reshuffle()
    }

    fun clear() {
        items.clear()
        shuffleOrder.clear()
        shufflePointer = -1
        currentIndex = -1
    }

    /** Jumps to [index]. Returns false when the index is out of range. */
    fun moveTo(index: Int): Boolean {
        if (index !in items.indices) return false
        currentIndex = index
        shufflePointer = shuffleOrder.indexOf(index)
        return true
    }

    /**
     * Index that would play after the current one.
     *
     * @param userInitiated a user pressing "next" always advances (wrapping at
     *   the end, even in SEQUENTIAL or LOOP_ONE). Automatic advancement on
     *   track completion honours the mode: LOOP_ONE repeats the same index and
     *   SEQUENTIAL returns -1 at the end of the list.
     */
    fun peekNext(mode: PlayMode, userInitiated: Boolean): Int {
        if (items.isEmpty()) return -1
        if (currentIndex !in items.indices) return 0
        if (!userInitiated && mode == PlayMode.LOOP_ONE) return currentIndex
        return when (mode) {
            PlayMode.SHUFFLE -> {
                if (shuffleOrder.size != items.size) reshuffle()
                val nextPointer = shufflePointer + 1
                if (nextPointer < shuffleOrder.size) {
                    shuffleOrder[nextPointer]
                } else {
                    // End of one shuffled pass. The caller re-rolls on advance.
                    shuffleOrder[0]
                }
            }
            PlayMode.SEQUENTIAL ->
                if (currentIndex < items.lastIndex) currentIndex + 1
                else if (userInitiated) 0 else -1
            PlayMode.LOOP_ALL, PlayMode.LOOP_ONE -> (currentIndex + 1) % items.size
        }
    }

    /** Advances and returns the new index, or -1 when playback should stop. */
    fun advance(mode: PlayMode, userInitiated: Boolean): Int {
        if (items.isEmpty()) return -1
        if (mode == PlayMode.SHUFFLE && currentIndex in items.indices) {
            if (shuffleOrder.size != items.size) reshuffle()
            if (shufflePointer + 1 >= shuffleOrder.size) {
                // A full pass is complete: start a fresh permutation that does
                // not begin with the song that just played.
                val justPlayed = currentIndex
                reshuffle(avoidFirst = justPlayed)
                shufflePointer = 0
                currentIndex = shuffleOrder[0]
                return currentIndex
            }
        }
        val next = peekNext(mode, userInitiated)
        if (next < 0) return -1
        moveTo(next)
        return next
    }

    /** Steps back and returns the new index. Always wraps around. */
    fun retreat(mode: PlayMode): Int {
        if (items.isEmpty()) return -1
        if (currentIndex !in items.indices) {
            moveTo(0)
            return 0
        }
        val prev = if (mode == PlayMode.SHUFFLE && shuffleOrder.size == items.size && shufflePointer >= 0) {
            val p = if (shufflePointer - 1 < 0) shuffleOrder.size - 1 else shufflePointer - 1
            shuffleOrder[p]
        } else {
            if (currentIndex - 1 < 0) items.lastIndex else currentIndex - 1
        }
        moveTo(prev)
        return prev
    }

    /** Inserts [item] right after the current song (or appends if nothing is playing). */
    fun insertNext(item: T): Int {
        val insertAt = if (currentIndex in items.indices) currentIndex + 1 else items.size
        items.add(insertAt, item)
        // Shift shuffle indices and slot the new song right after the current pointer.
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] >= insertAt) shuffleOrder[i] = shuffleOrder[i] + 1
        }
        if (shufflePointer >= 0) {
            shuffleOrder.add(shufflePointer + 1, insertAt)
        } else {
            shuffleOrder.add(insertAt)
        }
        if (currentIndex < 0) {
            // Nothing selected yet; keep the cursor unset so callers decide.
        }
        return insertAt
    }

    /**
     * Removes the item at [index]. Returns true when the removed item was the
     * current song, in which case the cursor points at the song that took its
     * place (or -1 if the queue became empty).
     */
    fun removeAt(index: Int): Boolean {
        if (index !in items.indices) return false
        val wasCurrent = index == currentIndex
        items.removeAt(index)

        val pointerOfRemoved = shuffleOrder.indexOf(index)
        if (pointerOfRemoved >= 0) {
            shuffleOrder.removeAt(pointerOfRemoved)
            if (pointerOfRemoved < shufflePointer) shufflePointer--
        }
        for (i in shuffleOrder.indices) {
            if (shuffleOrder[i] > index) shuffleOrder[i] = shuffleOrder[i] - 1
        }

        when {
            items.isEmpty() -> {
                currentIndex = -1
                shufflePointer = -1
            }
            index < currentIndex -> currentIndex--
            wasCurrent -> {
                if (currentIndex > items.lastIndex) currentIndex = 0
                shufflePointer = shuffleOrder.indexOf(currentIndex)
            }
        }
        return wasCurrent
    }

    /** Regenerates the shuffle permutation with the current song first. */
    fun reshuffle(avoidFirst: Int = -1) {
        shuffleOrder.clear()
        for (i in items.indices) shuffleOrder.add(i)
        shuffleOrder.shuffle(random)
        if (currentIndex in items.indices && avoidFirst < 0) {
            shuffleOrder.remove(currentIndex)
            shuffleOrder.add(0, currentIndex)
            shufflePointer = 0
        } else {
            if (avoidFirst >= 0 && shuffleOrder.size > 1 && shuffleOrder[0] == avoidFirst) {
                // Swap the first element with a random later one.
                val swapWith = 1 + random.nextInt(shuffleOrder.size - 1)
                shuffleOrder[0] = shuffleOrder[swapWith]
                shuffleOrder[swapWith] = avoidFirst
            }
            shufflePointer = shuffleOrder.indexOf(currentIndex)
        }
    }
}
