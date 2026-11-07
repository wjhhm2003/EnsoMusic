package com.enso.music.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodingUtilsTest {

    @Test
    fun preservesValidLatinCapitalOWithStrokeInAlbumName() {
        val album = "exuvium [OBLIVIØN Pt. II]"

        assertEquals(album, EncodingUtils.fixEncoding(album))
    }

    @Test
    fun preservesValidLatinSmallOWithStroke() {
        val title = "smøke and water"

        assertEquals(title, EncodingUtils.fixEncoding(title))
    }

    @Test
    fun stillRepairsUtf8MojibakeWhenCjkIsPresent() {
        val mojibake = String("周杰伦".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

        assertEquals("周杰伦", EncodingUtils.fixEncoding(mojibake))
    }
}
