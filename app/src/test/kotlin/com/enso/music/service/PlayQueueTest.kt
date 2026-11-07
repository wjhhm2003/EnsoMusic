package com.enso.music.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayQueueTest {

    private fun queueOf(n: Int, start: Int = 0, seed: Int = 7): PlayQueue<String> =
        PlayQueue<String>(Random(seed)).apply { setItems((0 until n).map { "s$it" }, start) }

    @Test
    fun loopAllWrapsAround() {
        val q = queueOf(3, start = 2)
        assertEquals(0, q.advance(PlayMode.LOOP_ALL, userInitiated = false))
        assertEquals(2, q.retreat(PlayMode.LOOP_ALL))
    }

    @Test
    fun loopOneRepeatsOnCompletionButUserSkipAdvances() {
        val q = queueOf(3, start = 1)
        assertEquals(1, q.peekNext(PlayMode.LOOP_ONE, userInitiated = false))
        assertEquals(2, q.advance(PlayMode.LOOP_ONE, userInitiated = true))
    }

    @Test
    fun sequentialStopsAtEndOnlyForAutomaticAdvance() {
        val q = queueOf(3, start = 2)
        assertEquals(-1, q.advance(PlayMode.SEQUENTIAL, userInitiated = false))
        assertEquals(2, q.currentIndex)
        assertEquals(0, q.advance(PlayMode.SEQUENTIAL, userInitiated = true))
    }

    @Test
    fun shufflePassVisitsEverySongOnceStartingFromCurrent() {
        val n = 20
        val q = queueOf(n, start = 5)
        q.reshuffle()
        val seen = linkedSetOf(q.currentIndex)
        repeat(n - 1) { seen.add(q.advance(PlayMode.SHUFFLE, userInitiated = false)) }
        assertEquals(n, seen.size)
        assertEquals(5, seen.first())
    }

    @Test
    fun shuffleBackThenForwardReturnsToSameSong() {
        val q = queueOf(10, start = 0)
        q.reshuffle()
        val a = q.advance(PlayMode.SHUFFLE, true)
        val b = q.advance(PlayMode.SHUFFLE, true)
        assertEquals(a, q.retreat(PlayMode.SHUFFLE))
        assertEquals(b, q.advance(PlayMode.SHUFFLE, true))
    }

    @Test
    fun newShufflePassDoesNotRepeatLastSong() {
        for (seed in 0 until 50) {
            val q = queueOf(4, start = 0, seed = seed)
            q.reshuffle()
            var last = q.currentIndex
            repeat(3) { last = q.advance(PlayMode.SHUFFLE, false) }
            val firstOfNextPass = q.advance(PlayMode.SHUFFLE, false)
            assertTrue("seed=$seed repeated $last", firstOfNextPass != last)
        }
    }

    @Test
    fun insertNextPlaysImmediatelyAfterCurrentInAllModes() {
        for (mode in listOf(PlayMode.LOOP_ALL, PlayMode.SHUFFLE, PlayMode.SEQUENTIAL)) {
            val q = PlayQueue<String>(Random(3)).apply { setItems(listOf("a", "b", "c", "d"), 1) }
            q.reshuffle()
            val at = q.insertNext("x")
            assertEquals(2, at)
            assertEquals("b", q.current)
            q.advance(mode, userInitiated = true)
            assertEquals("mode=$mode", "x", q.current)
        }
    }

    @Test
    fun removeBeforeCurrentKeepsCurrentSong() {
        val q = PlayQueue<String>().apply { setItems(listOf("a", "b", "c"), 2) }
        assertFalse(q.removeAt(0))
        assertEquals("c", q.current)
        assertEquals(1, q.currentIndex)
    }

    @Test
    fun removeCurrentMovesToFollowingSongAndWrapsAtEnd() {
        val q = PlayQueue<String>().apply { setItems(listOf("a", "b", "c"), 1) }
        assertTrue(q.removeAt(1))
        assertEquals("c", q.current)
        assertTrue(q.removeAt(1))
        assertEquals("a", q.current)
        assertTrue(q.removeAt(0))
        assertEquals(-1, q.currentIndex)
        assertTrue(q.isEmpty)
    }

    @Test
    fun shuffleOrderStaysValidAfterRemovals() {
        val q = queueOf(8, start = 3)
        q.reshuffle()
        q.removeAt(0)
        q.removeAt(5)
        val seen = linkedSetOf(q.currentIndex)
        repeat(q.size - 1) { seen.add(q.advance(PlayMode.SHUFFLE, false)) }
        assertEquals((0 until q.size).toSet(), seen)
    }

    @Test
    fun emptyQueueIsSafe() {
        val q = PlayQueue<String>()
        assertEquals(-1, q.advance(PlayMode.LOOP_ALL, true))
        assertEquals(-1, q.retreat(PlayMode.SHUFFLE))
        assertEquals(null, q.current)
    }

    @Test
    fun fineVolumeCurveMatchesLegacyFormula() {
        assertEquals(0f, FineVolume.gain(0), 0f)
        assertEquals(1f, FineVolume.gain(100), 1e-6f)
        assertEquals(Math.pow(0.75, 1.8).toFloat(), FineVolume.gain(75), 1e-6f)
        assertEquals(1f, FineVolume.gain(150), 1e-6f)
    }

    @Test
    fun buttonCycleSkipsSequential() {
        assertEquals(PlayMode.LOOP_ONE, PlayMode.LOOP_ALL.nextForButton())
        assertEquals(PlayMode.SHUFFLE, PlayMode.LOOP_ONE.nextForButton())
        assertEquals(PlayMode.LOOP_ALL, PlayMode.SHUFFLE.nextForButton())
        assertEquals(PlayMode.LOOP_ALL, PlayMode.SEQUENTIAL.nextForButton())
    }
}
