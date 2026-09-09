package dev.pokemog.android

import android.graphics.Canvas
import android.graphics.Paint

/** Original PokeMog pixels. The resource generator reads this grid and palette directly. */
internal object PixelPokeball {
    const val SIZE = 16
    val rows = listOf(
        "......HHHH......",
        "....HH####HH....",
        "...H##RRRR##H...",
        "..H#RRLLRRRR#H..",
        ".H#RRLLRRRRRR#H.",
        ".H#RRRRRRRRRR#H.",
        "H#RRRR####RRRR#H",
        "H#####WWWW#####H",
        "H#####WCCW#####H",
        "H#WWWW####WWWW#H",
        ".H#WWWWWWWWWW#H.",
        ".H#WWWWWWWWWW#H.",
        "..H#WWCCCCWW#H..",
        "...H##CCCC##H...",
        "....HH####HH....",
        "......HHHH......",
    )
    val palette = mapOf(
        'H' to 0xFFFFF2D9.toInt(),
        '#' to 0xFF302B32.toInt(),
        'R' to 0xFFEF6358.toInt(),
        'L' to 0xFFFFA18A.toInt(),
        'W' to 0xFFFFFAEF.toInt(),
        'C' to 0xFFE5D5BA.toInt(),
    )

    /** Call before any canvas scaling: both cell edges and the origin are physical pixels. */
    fun draw(canvas: Canvas, paint: Paint, width: Int, height: Int, artworkPixels: Int) {
        val cell = minOf(artworkPixels, width, height) / SIZE
        if (cell < 1) return
        val left = (width - SIZE * cell) / 2
        val top = (height - SIZE * cell) / 2
        paint.isAntiAlias = false
        paint.isFilterBitmap = false
        paint.style = Paint.Style.FILL
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, pixel ->
                palette[pixel]?.let { color ->
                    paint.color = color
                    canvas.drawRect(
                        (left + x * cell).toFloat(), (top + y * cell).toFloat(),
                        (left + (x + 1) * cell).toFloat(), (top + (y + 1) * cell).toFloat(),
                        paint,
                    )
                }
            }
        }
    }
}
