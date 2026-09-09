package dev.pokemog.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CaptureFramePixelsTest {
    @Test fun retainsNativeRowsBeyondTheFormer2000PixelLimit() {
        val source = ByteBuffer.allocate(3120 * 4)
        source.put(3119 * 4, 42)
        source.put(3119 * 4 + 3, -1)
        val pixels = CaptureFramePixels.snapshot(source, 1, 3120, 4, 4)
        assertEquals(3120, pixels.height)
        val row = IntArray(1)
        pixels.readArgbRow(3119, row)
        assertEquals(0xFF2A0000.toInt(), row[0])
    }

    @Test fun rgbaByteOrderIsIndependentOfBufferEndiannessAndPreservesAlpha() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val source = ByteBuffer.wrap(byteArrayOf(0x12, 0x34, 0x56, 0x78, -1, 0, -128, -1)).order(order)
            val pixels = CaptureFramePixels.snapshot(source, 2, 1, 4, 8)
            val row = IntArray(2)
            pixels.readArgbRow(0, row)
            assertArrayEquals(intArrayOf(0x78123456, 0xFFFF0080.toInt()), row)
        }
    }

    @Test fun ownsCroppedBytesWithPixelPaddingRowPaddingAndShortFinalRow() {
        // Three 6-byte pixels per row, eight padding bytes, and no padding after the final pixel.
        val source = ByteBuffer.allocateDirect(42)
        for (y in 0..1) for (x in 0..2) {
            val start = y * 26 + x * 6
            source.put(start, (10 + y * 3 + x).toByte())
            source.put(start + 1, (20 + x).toByte())
            source.put(start + 2, (30 + y).toByte())
            source.put(start + 3, -1)
        }
        source.position(7)
        val readOnly = source.asReadOnlyBuffer()
        val pixels = CaptureFramePixels.snapshot(readOnly, 3, 2, 6, 26,
            cropLeft = 1, cropTop = 1, cropWidth = 2, cropHeight = 1)
        assertEquals(2, pixels.width)
        assertEquals(1, pixels.height)
        assertEquals(7, source.position())
        assertEquals(42, source.limit())
        assertEquals(7, readOnly.position())
        assertEquals(42, readOnly.limit())
        // Changing the producer's storage must not change the snapshot.
        for (i in 0 until source.limit()) source.put(i, 0)
        val row = IntArray(2)
        pixels.readArgbRow(0, row)
        assertArrayEquals(intArrayOf(0xFF0E151F.toInt(), 0xFF0F161F.toInt()), row)
    }

    @Test fun acceptsUnpaddedFinalRowAndDoesNotReadCapacityPastLimit() {
        val source = ByteBuffer.allocate(24)
        source.limit(20) // Two pixels per row, row stride 12: final row only needs eight bytes.
        source.put(12, 9)
        source.put(15, -1)
        val pixels = CaptureFramePixels.snapshot(source, 2, 2, 4, 12)
        val row = IntArray(2)
        pixels.readArgbRow(1, row)
        assertEquals(0xFF090000.toInt(), row[0])
        source.limit(19)
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 2, 2, 4, 12) }
    }

    @Test fun cropDoesNotRequireUnusedRowsOrRightPaddingInBuffer() {
        val source = ByteBuffer.allocate(4)
        assertEquals(1, CaptureFramePixels.snapshot(source, 2, 2, 4, 8,
            cropWidth = 1, cropHeight = 1).width)
    }

    @Test fun rejectsInvalidDimensionsCropsStridesAndOutputRowsBeforeReading() {
        val source = ByteBuffer.allocate(64)
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 0, 2, 4, 8) }
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 4096, 4096, 4, 16384) }
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 2, 2, 3, 8) }
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 2, 2, 4, 7) }
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 2, 2, 4, -1) }
        assertThrows(IllegalArgumentException::class.java) { CaptureFramePixels.snapshot(source, 2, 2, Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureFramePixels.snapshot(source, 2, 2, 4, 8, cropLeft = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureFramePixels.snapshot(source, 2, 2, 4, 8, cropWidth = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureFramePixels.snapshot(source, 2, 2, 4, 8, cropLeft = Int.MAX_VALUE, cropWidth = Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureFramePixels.snapshot(source, 2, 2, 4, 8, cropTop = Int.MAX_VALUE, cropHeight = Int.MAX_VALUE)
        }
        val pixels = CaptureFramePixels.snapshot(source, 2, 2, 4, 8)
        assertThrows(IllegalArgumentException::class.java) { pixels.readArgbRow(2, IntArray(2)) }
        assertThrows(IllegalArgumentException::class.java) { pixels.readArgbRow(0, IntArray(1)) }
    }
}
