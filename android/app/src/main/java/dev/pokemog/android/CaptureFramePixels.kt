package dev.pokemog.android

import java.nio.ByteBuffer

/** An owned crop of RGBA bytes, independent of the ImageReader and safe to convert off-thread. */
class CaptureFramePixels private constructor(
    val width: Int,
    val height: Int,
    private val pixelStride: Int,
    private val rowBytes: Int,
    private val bytes: ByteArray,
) {
    companion object {
        /** Offsets are from buffer index zero. The caller's position and limit are left unchanged. */
        fun snapshot(
            buffer: ByteBuffer,
            width: Int,
            height: Int,
            pixelStride: Int,
            rowStride: Int,
            cropLeft: Int = 0,
            cropTop: Int = 0,
            cropWidth: Int = width,
            cropHeight: Int = height,
        ): CaptureFramePixels {
            require(CaptureSizing.nativeSize(width, height) == PixelSize(width, height)) {
                "Capture exceeds the native buffer limits"
            }
            require(cropLeft >= 0 && cropTop >= 0 && cropWidth > 0 && cropHeight > 0 &&
                cropLeft.toLong() + cropWidth <= width && cropTop.toLong() + cropHeight <= height) {
                "Capture crop is outside the image"
            }
            require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4) {
                "Invalid RGBA pixel or row stride"
            }
            val lastExclusive = (cropTop.toLong() + cropHeight - 1) * rowStride +
                (cropLeft.toLong() + cropWidth - 1) * pixelStride + 4
            require(lastExclusive <= buffer.limit()) { "Incomplete capture buffer" }
            val rowBytes = (cropWidth - 1L) * pixelStride + 4
            require(rowBytes * cropHeight <= 64_000_000L) { "Capture byte storage is too large" }
            val packedRow = rowBytes.toInt()
            val bytes = ByteArray(packedRow * cropHeight)
            val source = buffer.duplicate()
            // Bulk row copies retain pixel spacing but omit row padding and tolerate a short final row.
            for (y in 0 until cropHeight) {
                source.position(((cropTop.toLong() + y) * rowStride + cropLeft.toLong() * pixelStride).toInt())
                source.get(bytes, y * packedRow, packedRow)
            }
            return CaptureFramePixels(cropWidth, cropHeight, pixelStride, packedRow, bytes)
        }
    }

    /** Converts one row so callers can check cancellation without allocating a full ARGB pixel array. */
    fun readArgbRow(y: Int, target: IntArray) {
        require(y in 0 until height && target.size == width) { "Invalid output row" }
        var offset = y * rowBytes
        for (x in target.indices) {
            val r = bytes[offset].toInt() and 255
            val g = bytes[offset + 1].toInt() and 255
            val b = bytes[offset + 2].toInt() and 255
            val a = bytes[offset + 3].toInt() and 255
            target[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            offset += pixelStride
        }
    }
}
