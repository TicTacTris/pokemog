package dev.pokemog.android

import kotlin.math.sqrt

data class PixelSize(val width: Int, val height: Int)

object CaptureSizing {
    /** Preserve native pixels when possible; floor both scaled axes without ever rounding past a cap. */
    fun nativeSize(width: Int, height: Int, lowRam: Boolean = false): PixelSize {
        require(width > 0 && height > 0) { "Capture dimensions must be positive" }
        val maxPixels = if (lowRam) 3_000_000L else 8_000_000L
        val maxEdge = if (lowRam) 2560 else 4096
        val pixels = width.toLong() * height
        if (pixels <= maxPixels && maxOf(width, height) <= maxEdge) return PixelSize(width, height)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height), sqrt(maxPixels.toDouble() / pixels))
        var w = (width * scale).toInt().coerceIn(1, maxEdge)
        var h = (height * scale).toInt().coerceIn(1, maxEdge)
        // Guard the area cap even at floating-point rounding boundaries.
        if (w.toLong() * h > maxPixels) {
            if (w >= h) w = (maxPixels / h).toInt() else h = (maxPixels / w).toInt()
        }
        return PixelSize(w, h)
    }
}
