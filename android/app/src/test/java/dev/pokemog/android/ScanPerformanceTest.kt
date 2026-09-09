package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class ScanPerformanceTest {
    @Test fun overlappingStagesAreReportedWithoutAddingThem() {
        val scan = ScanTimings(5, 80, 110, 2, 120)
        val result = ScanPerformance("Overlay", 270, 10, 3, 420, scan, 17)
        val text = result.describe()
        assertTrue(text.contains("result ready 420ms"))
        assertTrue(text.contains("Bar analysis: 80ms"))
        assertTrue(text.contains("OCR: 110ms"))
        assertTrue(text.contains("Scanner total: 120ms"))
        assertTrue(text.contains("Overlay layout: 17ms"))
        assertTrue(text.contains("Capture: 270ms"))
    }

    @Test fun importedImagesUseDecodeLabelAndNoOverlayLayout() {
        val text = ScanPerformance("Import", 8, 0, 2, 100, null).describe()
        assertTrue(text.contains("Decode: 8ms"))
        assertFalse(text.contains("Pixel conversion"))
        assertFalse(text.contains("Overlay layout"))
    }
}
