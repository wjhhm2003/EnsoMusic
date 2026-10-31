package com.enso.music.ui.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun testFormatDurationZero() {
        assertEquals("00:00", FormatUtils.formatDuration(0))
    }

    @Test
    fun testFormatDurationNegative() {
        assertEquals("00:00", FormatUtils.formatDuration(-5000))
    }

    @Test
    fun testFormatDurationUnderOneMinute() {
        assertEquals("00:59", FormatUtils.formatDuration(59_000))
    }

    @Test
    fun testFormatDurationExactMinute() {
        assertEquals("01:00", FormatUtils.formatDuration(60_000))
    }

    @Test
    fun testFormatDurationTypicalSong() {
        assertEquals("04:59", FormatUtils.formatDuration(299_000))
    }

    @Test
    fun testFormatDurationLongAudio() {
        assertEquals("65:05", FormatUtils.formatDuration(3_905_000))
    }
}
