package dev.pokemog.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/** Import and MediaProjection both pass native pixels through this exact preparation step. */
object ScanImages {
    fun prepare(source: Bitmap): Bitmap {
        require(!source.isRecycled && source.width > 0 && source.height > 0)
        val scale = minOf(1.0, 2000.0 / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) { "Could not read hardware-backed image" }
        } else source
        try {
            val prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false,
                ColorSpace.get(ColorSpace.Named.SRGB))
            try {
                Canvas(prepared).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(software, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                return prepared
            } catch (error: Throwable) {
                prepared.recycle()
                throw error
            }
        } finally {
            if (software !== source) software.recycle()
        }
    }
}
