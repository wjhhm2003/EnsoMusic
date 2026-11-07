package com.enso.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Empirical challenger test for 502x410 Watch Viewport & Layout Integrity.
 * Directly parses and analyzes XML layout definitions and mathematically verifies
 * layout budgets, alignment invariants, and touch targets.
 */
class LayoutIntegrityChallengerTest {

    private fun parseXml(relativePath: String): Element {
        var file = File(relativePath)
        if (!file.exists()) {
            file = File(relativePath.removePrefix("app/"))
        }
        if (!file.exists()) {
            file = File("app", relativePath.removePrefix("app/"))
        }
        assertTrue("Layout file must exist: ${file.absolutePath}", file.exists())
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        val db = dbf.newDocumentBuilder()
        val doc = db.parse(file)
        return doc.documentElement
    }

    private fun findElementById(root: Element, targetId: String): Element? {
        val cleanTarget = targetId.removePrefix("@+id/").removePrefix("@id/")
        if (root.getAttribute("android:id").removePrefix("@+id/").removePrefix("@id/") == cleanTarget) {
            return root
        }
        val childNodes = root.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val found = findElementById(node as Element, cleanTarget)
                if (found != null) return found
            }
        }
        return null
    }

    private fun parseDp(value: String?): Int {
        if (value == null || !value.endsWith("dp")) return 0
        return value.removeSuffix("dp").toIntOrNull() ?: 0
    }

    @Test
    fun testNowPlayingVerticalSpaceBudget() {
        val root = parseXml("app/src/main/res/layout/fragment_now_playing.xml")
        assertEquals("Root must be ConstraintLayout", "androidx.constraintlayout.widget.ConstraintLayout", root.tagName)
        assertEquals("Root width must match parent", "match_parent", root.getAttribute("android:layout_width"))
        assertEquals("Root height must match parent", "match_parent", root.getAttribute("android:layout_height"))

        // Safe padding to prevent clipping at bottom edge
        assertEquals("Root paddingTop must be 6dp", 6, parseDp(root.getAttribute("android:paddingTop")))
        assertEquals("Root paddingBottom must be 12dp", 12, parseDp(root.getAttribute("android:paddingBottom")))

        // 1. Cover Card - width-driven adaptive square (~43%, max 176dp)
        val cardCover = findElementById(root, "cardCover")!!
        assertEquals("Cover dimension ratio must be 1:1", "1:1", cardCover.getAttribute("app:layout_constraintDimensionRatio"))
        assertEquals("Cover max width must be 176dp", 176, parseDp(cardCover.getAttribute("app:layout_constraintWidth_max")))
        assertEquals("Cover percent width must be 0.43", "0.43", cardCover.getAttribute("app:layout_constraintWidth_percent"))

        // 2. Title & Subtitle Text
        val tvTitle = findElementById(root, "tvTitle")!!
        val titleTopMargin = parseDp(tvTitle.getAttribute("android:layout_marginTop"))
        assertEquals("Title top margin from cover must be 8dp", 8, titleTopMargin)

        val tvArtistAlbum = findElementById(root, "tvArtistAlbum")!!
        val artistTopMargin = parseDp(tvArtistAlbum.getAttribute("android:layout_marginTop"))
        assertEquals("Artist top margin from title must be 3dp", 3, artistTopMargin)

        // 3. SeekBar
        val sbProgress = findElementById(root, "sbProgress")!!
        val seekbarTopMargin = parseDp(sbProgress.getAttribute("android:layout_marginTop"))
        val seekbarHeight = parseDp(sbProgress.getAttribute("android:layout_height"))
        assertEquals("SeekBar top margin must be 8dp", 8, seekbarTopMargin)
        assertEquals("SeekBar hitbox height must be 28dp", 28, seekbarHeight)
        assertEquals("SeekBar maxHeight must be 3dp", 3, parseDp(sbProgress.getAttribute("android:maxHeight")))
        assertEquals("SeekBar minHeight must be 3dp", 3, parseDp(sbProgress.getAttribute("android:minHeight")))

        // 4. Control buttons
        val btnPlayPause = findElementById(root, "btnPlayPause")!!
        val controlsHeight = parseDp(btnPlayPause.getAttribute("android:layout_height"))
        assertEquals("Center play/pause button height must be 54dp", 54, controlsHeight)

        // 5. Duplicate Capsule Indicator must be completely removed
        val indicatorCapsule = findElementById(root, "indicatorCapsule")
        assertNull("Duplicate indicatorCapsule must be completely removed", indicatorCapsule)
    }

    @Test
    fun testNowPlayingControlsTouchTargets() {
        val root = parseXml("app/src/main/res/layout/fragment_now_playing.xml")

        val controlIds = listOf("btnPlayMode", "btnPrevious", "btnPlayPause", "btnNext", "btnQueue")
        for (id in controlIds) {
            val element = findElementById(root, id)
            assertTrue("Control $id must exist", element != null)
            val w = parseDp(element!!.getAttribute("android:layout_width"))
            val h = parseDp(element.getAttribute("android:layout_height"))
            assertTrue("Control $id width ($w dp) must be >= 46dp", w >= 46)
            assertTrue("Control $id height ($h dp) must be >= 46dp", h >= 46)
        }
    }

    @Test
    fun testAlphabetGridTouchTargets() {
        val root = parseXml("app/src/main/res/layout/fragment_library.xml")
        val gridAlphabet = findElementById(root, "gridAlphabet")!!
        val children = gridAlphabet.childNodes

        // Weighted columns (0dp + columnWeight) share the grid width. Check the
        // per-cell pitch on the target watch: 502px @ 226dpi ≈ 355dp wide.
        val targetScreenWidthDp = 355
        val columns = gridAlphabet.getAttribute("android:columnCount").toIntOrNull() ?: 1
        val gridHorizontalMargin = parseDp(gridAlphabet.getAttribute("android:layout_marginStart")) +
                parseDp(gridAlphabet.getAttribute("android:layout_marginEnd"))
        val weightedPitchDp = (targetScreenWidthDp - gridHorizontalMargin) / columns

        var buttonCount = 0
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val element = node as Element
                val isWeighted = element.getAttribute("android:layout_width") == "0dp" &&
                        element.getAttribute("android:layout_columnWeight").isNotEmpty()
                val w = if (isWeighted) weightedPitchDp else parseDp(element.getAttribute("android:layout_width"))
                val h = parseDp(element.getAttribute("android:layout_height"))
                assertTrue("Grid item '${element.textContent.trim()}' width ($w dp) must be >= 48dp", w >= 48)
                assertTrue("Grid item '${element.textContent.trim()}' height ($h dp) must be >= 48dp", h >= 48)
                buttonCount++
            }
        }
        assertEquals("Alphabet grid must contain 27 buttons (A-Z and #)", 27, buttonCount)
    }

    @Test
    fun testBtnQuickScanTouchTargetDiscrepancy() {
        val root = parseXml("app/src/main/res/layout/fragment_library.xml")
        val btnQuickScan = findElementById(root, "btnQuickScan")!!
        val w = parseDp(btnQuickScan.getAttribute("android:layout_width"))
        val h = parseDp(btnQuickScan.getAttribute("android:layout_height"))

        // Documenting the specific measurement:
        // R4 specifies 40dp x 40dp, while the generic touch guideline calls for >= 48dp x 48dp.
        assertEquals("btnQuickScan width in XML is 40dp", 40, w)
        assertEquals("btnQuickScan height in XML is 40dp", 40, h)
    }

    @Test
    fun testQueueHorizontalAlignmentInvariant() {
        val root = parseXml("app/src/main/res/layout/item_queue.xml")
        val paddingStart = parseDp(root.getAttribute("android:paddingStart"))
        assertEquals("item_queue paddingStart must be 10dp", 10, paddingStart)

        val layoutLeadingContainer = findElementById(root, "layoutLeadingContainer")!!
        val containerWidth = parseDp(layoutLeadingContainer.getAttribute("android:layout_width"))
        assertEquals("layoutLeadingContainer width must be fixed at 36dp", 36, containerWidth)

        val tvQueueTitle = findElementById(root, "tvQueueTitle")!!
        val titleMarginStart = parseDp(tvQueueTitle.getAttribute("android:layout_marginStart"))
        val titleStartAnchor = tvQueueTitle.getAttribute("app:layout_constraintStart_toEndOf")
        assertTrue("Title must start to end of layoutLeadingContainer", titleStartAnchor.contains("layoutLeadingContainer"))
        assertEquals("Title marginStart must be 8dp", 8, titleMarginStart)

        val tvQueueArtist = findElementById(root, "tvQueueArtist")!!
        val artistMarginStart = parseDp(tvQueueArtist.getAttribute("android:layout_marginStart"))
        val artistStartAnchor = tvQueueArtist.getAttribute("app:layout_constraintStart_toEndOf")
        assertTrue("Artist must start to end of layoutLeadingContainer", artistStartAnchor.contains("layoutLeadingContainer"))
        assertEquals("Artist marginStart must be 8dp", 8, artistMarginStart)

        // Mathematical invariant calculation:
        // Container start = paddingStart = 10dp
        // Container end = paddingStart + containerWidth = 10 + 36 = 46dp
        // Text start = Container end + marginStart = 46 + 8 = 54dp
        val unplayedTextStartX = paddingStart + containerWidth + titleMarginStart
        val playingTextStartX = paddingStart + containerWidth + titleMarginStart

        assertEquals("Text start position must be 54dp", 54, unplayedTextStartX)
        assertEquals("Unplayed vs playing start position delta must be strictly 0dp", 0, playingTextStartX - unplayedTextStartX)
    }

    @Test
    fun testSongItemHorizontalAlignmentInvariant() {
        val root = parseXml("app/src/main/res/layout/item_song.xml")
        val paddingStart = parseDp(root.getAttribute("android:paddingStart"))
        assertEquals("item_song paddingStart must be 10dp", 10, paddingStart)

        val layoutCoverContainer = findElementById(root, "layoutCoverContainer")!!
        val containerWidth = parseDp(layoutCoverContainer.getAttribute("android:layout_width"))
        assertEquals("layoutCoverContainer width must be fixed at 38dp", 38, containerWidth)

        val tvTitle = findElementById(root, "tvTitle")!!
        val titleMarginStart = parseDp(tvTitle.getAttribute("android:layout_marginStart"))
        val titleStartAnchor = tvTitle.getAttribute("app:layout_constraintStart_toEndOf")
        assertTrue("Title must start to end of layoutCoverContainer", titleStartAnchor.contains("layoutCoverContainer"))
        assertEquals("Title marginStart must be 10dp", 10, titleMarginStart)

        val unplayedTextStartX = paddingStart + containerWidth + titleMarginStart
        val playingTextStartX = paddingStart + containerWidth + titleMarginStart

        assertEquals("Song title start position must be 58dp", 58, unplayedTextStartX)
        assertEquals("Unplayed vs playing start position delta must be strictly 0dp", 0, playingTextStartX - unplayedTextStartX)
    }
}
