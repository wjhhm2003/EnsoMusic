package com.enso.music.service

import com.enso.music.data.util.PinyinInitialHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FineVolumeControllerTest {

    private fun calculateGain(percent: Int): Float {
        val coerced = percent.coerceIn(0, 100)
        val normalized = coerced / 100f
        return if (coerced == 0) 0f else normalized.toDouble().pow(1.8).toFloat()
    }

    @Test
    fun testFineVolumeCurveBoundary() {
        assertEquals(0f, calculateGain(0), 0.0001f)
        assertEquals(1.0f, calculateGain(100), 0.0001f)
    }

    @Test
    fun testFineVolumeLogarithmicSmoothness() {
        // Human ear perception: pow(1.8) makes lower volumes much softer and smoother
        val gain10 = calculateGain(10)
        val gain20 = calculateGain(20)
        val gain50 = calculateGain(50)
        val gain75 = calculateGain(75)

        assertTrue("Gain at 10% should be around 0.015", gain10 in 0.01f..0.02f)
        assertTrue("Gain at 20% should be around 0.055", gain20 in 0.04f..0.07f)
        assertTrue("Gain at 50% should be around 0.287", gain50 in 0.25f..0.32f)
        assertTrue("Gain at 75% should be around 0.596", gain75 in 0.55f..0.65f)

        // Monotonically strictly increasing
        for (i in 0 until 100) {
            assertTrue("Gain must be strictly monotonic", calculateGain(i + 1) > calculateGain(i))
        }
    }

    @Test
    fun testAlbumAndArtistPinyinSorting() {
        val albums = listOf("范特西", "七里香", "叶惠美", "11月的萧邦", "Acoustic Live", "魔杰座", "!Special")
        val sorted = albums.map {
            Pair(it, PinyinInitialHelper.getInitial(it))
        }.sortedWith(compareBy({ it.second == "#" }, { PinyinInitialHelper.getSortKey(it.first) }))

        // A-Z should come before #
        val initials = sorted.map { it.second }
        // 11月的萧邦 and !Special should have initial # and be sorted at the end
        assertEquals("A", initials[0]) // Acoustic Live
        assertEquals("F", initials[1]) // 范特西 (Fan)
        assertEquals("M", initials[2]) // 魔杰座 (Mo)
        assertEquals("Q", initials[3]) // 七里香 (Qi)
        assertEquals("Y", initials[4]) // 叶惠美 (Ye)
        assertEquals("#", initials[5]) // !Special or 11月的萧邦
        assertEquals("#", initials[6])
    }
}
