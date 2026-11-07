package com.enso.music.data.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverKeysAndColorsTest {

    @Test
    fun md5HexMatchesKnownVector() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", CoverKeys.md5Hex(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", CoverKeys.md5Hex("abc".toByteArray()))
    }

    @Test
    fun hashPathUnchangedForLegacyKeys() {
        // Legacy per-song files were named md5(path); keep that format stable.
        assertEquals("900150983cd24fb0d6963f7d28e17f72", CoverManager.hashPath("abc"))
    }

    @Test
    fun identicalEmbeddedBytesShareKey() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)
        val c = byteArrayOf(1, 2, 3, 5)
        assertEquals(CoverKeys.embeddedKey(a), CoverKeys.embeddedKey(b))
        assertNotEquals(CoverKeys.embeddedKey(a), CoverKeys.embeddedKey(c))
    }

    @Test
    fun folderImageKeyDependsOnPathAndLastModified() {
        val k1 = CoverKeys.folderImageKey("/Music/Album/cover.jpg", 1000L)
        assertEquals(k1, CoverKeys.folderImageKey("/Music/Album/cover.jpg", 1000L))
        assertNotEquals(k1, CoverKeys.folderImageKey("/Music/Album/cover.jpg", 2000L))
        assertNotEquals(k1, CoverKeys.folderImageKey("/Music/Other/cover.jpg", 1000L))
    }

    @Test
    fun embeddedAndFolderKeysDoNotCollide() {
        assertTrue(CoverKeys.embeddedKey(byteArrayOf(1)).startsWith("e_"))
        assertTrue(CoverKeys.folderImageKey("/x", 0L).startsWith("f_"))
    }

    @Test
    fun emptyPixelsGiveNull() {
        assertNull(CoverColors.weightedAverage(IntArray(0)))
    }

    @Test
    fun uniformColourIsReturnedOpaque() {
        val red = 0xFFC83232.toInt()
        assertEquals(red, CoverColors.weightedAverage(IntArray(64) { red }))
    }

    @Test
    fun blackAndWhiteBordersAreWeightedOut() {
        val blue = 0xFF2050C0.toInt()
        val pixels = IntArray(64) { i ->
            when {
                i < 24 -> 0xFF000000.toInt()
                i < 48 -> 0xFFFFFFFF.toInt()
                else -> blue
            }
        }
        val c = CoverColors.weightedAverage(pixels)!!
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        // Plain average would be a washed-out grey; weighted result stays blue.
        assertTrue("b=$b r=$r", b > r + 60)
        assertTrue("b=$b g=$g", b > g + 40)
    }

    @Test
    fun allBlackFallsBackToPlainAverage() {
        val c = CoverColors.weightedAverage(IntArray(64) { 0xFF050505.toInt() })
        assertNotNull(c)
        assertEquals(0xFF050505.toInt(), c)
    }
}
