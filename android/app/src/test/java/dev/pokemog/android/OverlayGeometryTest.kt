package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun portraitPanelIsCenteredBelowUsableMidpoint() {
        val usable = OverlayGeometry.usable(1080, 2400, OverlayInsets(0, 90, 0, 120))
        val panel = OverlayGeometry.panel(usable, 1000, Int.MAX_VALUE, 24)
        assertEquals(OverlayBounds(40, 1185, 1000, 1071), panel)
        assertEquals(usable.bottom - 24, panel.bottom)
        assertTrue(panel.top * 2 >= usable.top * 2 + usable.height)
    }

    @Test fun landscapeCutoutsAndNavigationInsetsBoundTallFontScaledContent() {
        val usable = OverlayGeometry.usable(2400, 1080, OverlayInsets(100, 36, 120, 60))
        val panel = OverlayGeometry.panel(usable, 1140, 5000, 24)
        assertEquals(usable.left + (usable.width - panel.width) / 2, panel.left)
        assertTrue(panel.top >= usable.top + usable.height / 2)
        assertEquals(usable.bottom - 24, panel.bottom)
        assertTrue(panel.height <= usable.height / 2)
    }

    @Test fun shortPanelsWrapAtBottomAndOddHeightsNeverCrossMidpoint() {
        val usable = OverlayGeometry.usable(361, 801, OverlayInsets(3, 25, 7, 31))
        val short = OverlayGeometry.panel(usable, 340, 96, 8)
        assertEquals(96, short.height)
        assertEquals(usable.bottom - 8, short.bottom)
        val tall = OverlayGeometry.panel(usable, 340, Int.MAX_VALUE, 8)
        assertTrue(tall.top.toLong() * 2 >= usable.top.toLong() * 2 + usable.height)
        assertTrue(tall.left >= usable.left && tall.right <= usable.right)
    }

    @Test fun panelOpenCollapseAndDetailsDoNotChangeOrbCoordinates() {
        val usable = OverlayGeometry.usable(1080, 2400, OverlayInsets(0, 80, 0, 100))
        val before = OverlayGeometry.orb(usable, 912, 1780, 168, true)
        OverlayGeometry.panel(usable, 1020, 600, 24)
        OverlayGeometry.panel(usable, 1020, 6000, 24)
        assertEquals(before, OverlayGeometry.orb(usable, before.left, before.top, 168))
    }

    @Test fun orbClampsAfterRotationAndSnapsToNearestSafeEdge() {
        val usable = OverlayGeometry.usable(800, 360, OverlayInsets(30, 20, 40, 24))
        assertEquals(OverlayBounds(704, 280, 56, 56), OverlayGeometry.orb(usable, 900, 2000, 56, true))
        assertEquals(30, OverlayGeometry.orb(usable, -100, -100, 56, true).left)
        assertEquals(20, OverlayGeometry.orb(usable, -100, -100, 56, true).top)
    }

    @Test fun extremeInsetsAndMarginsCannotOverflowOrProduceNegativeDimensions() {
        val empty = OverlayGeometry.usable(1, 1, OverlayInsets(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0, OverlayGeometry.panel(empty, 340, Int.MAX_VALUE, Int.MAX_VALUE).height)
        assertEquals(0, OverlayGeometry.orb(empty, Int.MIN_VALUE, Int.MAX_VALUE, 56).width)
        val huge = OverlayGeometry.usable(Int.MAX_VALUE, Int.MAX_VALUE, OverlayInsets())
        val panel = OverlayGeometry.panel(huge, Int.MAX_VALUE, Int.MAX_VALUE, 0)
        assertTrue(panel.top.toLong() * 2 >= huge.height.toLong())
        assertEquals(Int.MAX_VALUE, panel.bottom)
    }

    @Test fun metricsStackWhenNarrowOrFontScaled() {
        assertTrue(OverlayGeometry.sideBySide(936, 3f, 1f))
        assertFalse(OverlayGeometry.sideBySide(780, 3f, 1f))
        assertFalse(OverlayGeometry.sideBySide(936, 3f, 1.5f))
    }

    @Test fun arcClampsAndPreservesNullAndTiedPercentiles() {
        assertEquals(0f, OverlayGeometry.arcSweep(0.0)!!, 0f)
        assertEquals(90f, OverlayGeometry.arcSweep(50.0)!!, 0f)
        assertEquals(180f, OverlayGeometry.arcSweep(100.0)!!, 0f)
        assertEquals(0f, OverlayGeometry.arcSweep(-5.0)!!, 0f)
        assertEquals(180f, OverlayGeometry.arcSweep(120.0)!!, 0f)
        assertNull(OverlayGeometry.arcSweep(null))
        assertNull(OverlayGeometry.arcSweep(Double.NaN))
        assertNull(OverlayGeometry.arcSweep(Double.POSITIVE_INFINITY))
        val tied = listOf(87.25, 87.25).map(OverlayGeometry::arcSweep)
        assertEquals(tied[0], tied[1])
        assertEquals(157.05f, tied[0]!!, 0.001f)
    }
}
