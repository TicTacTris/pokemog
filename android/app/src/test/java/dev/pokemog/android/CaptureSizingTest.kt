package dev.pokemog.android

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class CaptureSizingTest {
    @Test fun preservesNativePhoneAndAppDimensionsWithinLimits() {
        for (size in listOf(PixelSize(1080, 2400), PixelSize(1440, 3120), PixelSize(2000, 4000),
            PixelSize(4096, 1), PixelSize(820, 1180), PixelSize(1, 1))) {
            assertEquals(size, CaptureSizing.nativeSize(size.width, size.height))
        }
        assertEquals(PixelSize(1080, 2400), CaptureSizing.nativeSize(1080, 2400, true))
        assertEquals(PixelSize(1200, 2500), CaptureSizing.nativeSize(1200, 2500, true))
    }

    @Test fun areaAndEdgeCapsAreIndependentAndRoundingIsDeterministic() {
        assertEquals(PixelSize(4096, 819), CaptureSizing.nativeSize(5000, 1000))
        assertEquals(PixelSize(2828, 2828), CaptureSizing.nativeSize(4096, 4096))
        assertEquals(PixelSize(1732, 1732), CaptureSizing.nativeSize(2000, 2000, true))
        assertEquals(PixelSize(2560, 512), CaptureSizing.nativeSize(5000, 1000, true))
    }

    @Test fun extremeInputsStayPositiveBoundedAndRotationSymmetric() {
        val values = listOf(1, 17, 1080, 1440, 2000, 2560, 3120, 4096, 4097, 8000, Int.MAX_VALUE)
        for (lowRam in listOf(false, true)) for (w in values) for (h in values) {
            val size = CaptureSizing.nativeSize(w, h, lowRam)
            val maxEdge = if (lowRam) 2560 else 4096
            val maxPixels = if (lowRam) 3_000_000L else 8_000_000L
            assertTrue(size.width in 1..minOf(w, maxEdge))
            assertTrue(size.height in 1..minOf(h, maxEdge))
            assertTrue(size.width.toLong() * size.height <= maxPixels)
            assertEquals(PixelSize(size.height, size.width), CaptureSizing.nativeSize(h, w, lowRam))
            assertEquals(size, CaptureSizing.nativeSize(w, h, lowRam))
            assertEquals(size, CaptureSizing.nativeSize(size.width, size.height, lowRam))
            // Flooring introduces less than one source-normalized pixel of error on either axis.
            assertTrue(abs(size.width.toDouble() / w - size.height.toDouble() / h) <= 1.0 / w + 1.0 / h)
        }
    }

    @Test fun rejectsNonPositiveAndOverflowingSignedInputs() {
        for (size in listOf(PixelSize(0, 1), PixelSize(1, 0), PixelSize(-1, 100), PixelSize(100, Int.MIN_VALUE))) {
            assertThrows(IllegalArgumentException::class.java) { CaptureSizing.nativeSize(size.width, size.height) }
        }
    }
}
