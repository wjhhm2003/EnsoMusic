package com.enso.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ScanRequestGateTest {

    @Test
    fun firstRequestWinsAndReleasesWhenNothingPending() {
        val gate = ScanRequestGate()
        assertTrue(gate.tryStart(false))
        assertTrue(gate.isRunning)
        assertNull(gate.finishOrTakePending())
        assertFalse(gate.isRunning)
        assertTrue(gate.tryStart(false))
    }

    @Test
    fun requestsWhileRunningCoalesceIntoOne() {
        val gate = ScanRequestGate()
        assertTrue(gate.tryStart(false))
        assertFalse(gate.tryStart(false))
        assertFalse(gate.tryStart(false))
        assertEquals(false, gate.finishOrTakePending())
        assertTrue("owner keeps the guard for the pending run", gate.isRunning)
        assertNull(gate.finishOrTakePending())
        assertFalse(gate.isRunning)
    }

    @Test
    fun fullRequestWinsOverIncremental() {
        val gate = ScanRequestGate()
        assertTrue(gate.tryStart(false))
        assertFalse(gate.tryStart(true))
        assertFalse(gate.tryStart(false))
        assertEquals(true, gate.finishOrTakePending())
    }

    @Test
    fun abandonReleasesAndReturnsPending() {
        val gate = ScanRequestGate()
        assertTrue(gate.tryStart(false))
        assertFalse(gate.tryStart(true))
        assertEquals(true, gate.abandon())
        assertFalse(gate.isRunning)
        assertNull(gate.abandon())
    }

    @Test
    fun onlyOneConcurrentCallerWins() {
        val gate = ScanRequestGate()
        val winners = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = (1..16).map {
            Thread {
                start.await()
                if (gate.tryStart(false)) winners.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
        assertEquals(false, gate.finishOrTakePending())
    }
}
