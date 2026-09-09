package dev.pokemog.android

data class OverlayInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
data class OverlayBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** Pixel coordinates in the full display frame. Orb placement never depends on panel placement. */
object OverlayGeometry {
    fun usable(width: Int, height: Int, insets: OverlayInsets): OverlayBounds {
        require(width > 0 && height > 0)
        val left = insets.left.coerceIn(0, width)
        val top = insets.top.coerceIn(0, height)
        val right = insets.right.coerceIn(0, width - left)
        val bottom = insets.bottom.coerceIn(0, height - top)
        return OverlayBounds(left, top, width - left - right, height - top - bottom)
    }

    fun panel(usable: OverlayBounds, maxWidth: Int, desiredHeight: Int, margin: Int): OverlayBounds {
        require(maxWidth > 0 && desiredHeight >= 0 && margin >= 0)
        val horizontalMargin = minOf(margin, usable.width / 2)
        val bottomMargin = minOf(margin, usable.height / 2)
        val width = minOf(maxWidth, usable.width - horizontalMargin * 2)
        val height = minOf(desiredHeight, usable.height / 2 - bottomMargin)
        return OverlayBounds(usable.left + (usable.width - width) / 2,
            usable.bottom - bottomMargin - height, width, height)
    }

    fun orb(usable: OverlayBounds, x: Int, y: Int, diameter: Int, snap: Boolean = false): OverlayBounds {
        require(diameter > 0)
        val size = minOf(diameter, usable.width, usable.height)
        var left = x.coerceIn(usable.left, usable.right - size)
        if (snap) left = if (left.toLong() * 2 + size > usable.left.toLong() * 2 + usable.width)
            usable.right - size else usable.left
        return OverlayBounds(left, y.coerceIn(usable.top, usable.bottom - size), size, size)
    }

    fun sideBySide(contentWidth: Int, density: Float, fontScale: Float): Boolean {
        require(density.isFinite() && density > 0 && fontScale.isFinite() && fontScale > 0)
        return contentWidth / density >= 2 * 136f * maxOf(1f, fontScale) + 8f
    }

    /** Null/non-finite means unavailable, not zero. Tied percentiles have identical sweeps. */
    fun arcSweep(percentile: Double?): Float? = percentile?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 100.0)?.let { (it * 1.8).toFloat() }
}
