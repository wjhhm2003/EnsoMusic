package com.enso.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPathMatcherTest {

    @Test
    fun matchesFilesInsideFolderAndNested() {
        val m = FolderPathMatcher.compile("/sdcard/Music", null)
        assertTrue(m.contains("/sdcard/Music/a.mp3"))
        assertTrue(m.contains("/sdcard/Music/Album/b.flac"))
        assertTrue(m.contains("/sdcard/Music"))
    }

    @Test
    fun requiresSeparatorBoundary() {
        val m = FolderPathMatcher.compile("/sdcard/Music", null)
        assertFalse(m.contains("/sdcard/Music2/a.mp3"))
        assertFalse(m.contains("/sdcard/MusicBox"))
        assertFalse(m.contains("/sdcard/Musi"))
        assertFalse(m.contains("/sdcard/Download/a.mp3"))
    }

    @Test
    fun trailingSeparatorOnFolderIsIgnored() {
        val m = FolderPathMatcher.compile("/sdcard/Music/", null)
        assertTrue(m.contains("/sdcard/Music/a.mp3"))
        assertTrue(m.contains("/sdcard/Music"))
        assertFalse(m.contains("/sdcard/Music2/a.mp3"))
    }

    @Test
    fun matchesEitherAbsoluteOrCanonicalForm() {
        val m = FolderPathMatcher.compile("/sdcard/Music", "/storage/emulated/0/Music")
        assertTrue(m.contains("/sdcard/Music/a.mp3"))
        assertTrue(m.contains("/storage/emulated/0/Music/a.mp3"))
        assertFalse(m.contains("/storage/emulated/0/Music2/a.mp3"))
        assertFalse(m.contains("/storage/emulated/0/Download/a.mp3"))
    }

    @Test
    fun rootFolderMatchesEverythingBelow() {
        val m = FolderPathMatcher.compile("/", null)
        assertTrue(m.contains("/a.mp3"))
        assertTrue(m.contains("/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun blankFolderMatchesNothing() {
        val m = FolderPathMatcher.compile("", "  ")
        assertFalse(m.contains("/sdcard/Music/a.mp3"))
        assertFalse(m.contains(""))
    }

    @Test
    fun normalizeFolderStripsTrailingSeparators() {
        assertEquals("/sdcard/Music", FolderPathMatcher.normalizeFolder("/sdcard/Music//"))
        assertEquals("/", FolderPathMatcher.normalizeFolder("/"))
        assertEquals("/", FolderPathMatcher.normalizeFolder("///"))
        assertNull(FolderPathMatcher.normalizeFolder(""))
    }

    @Test
    fun backslashIsTreatedAsSeparator() {
        val m = FolderPathMatcher.compile("C:\\Music", null)
        assertTrue(m.contains("C:\\Music\\a.mp3"))
        assertFalse(m.contains("C:\\Music2\\a.mp3"))
    }
}
