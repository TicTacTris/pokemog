package dev.pokemog.android

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ToggleButton
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Two square 2dp steps at each corner; shared by native and Compose frames. */
private fun framePoints(w: Float, h: Float, corner: Float): FloatArray {
    val c = minOf(corner, w / 2, h / 2)
    val s = c / 2
    return floatArrayOf(c, 0f, w-c, 0f, w-c, s, w-s, s, w-s, c, w, c,
        w, h-c, w-s, h-c, w-s, h-s, w-c, h-s, w-c, h,
        c, h, c, h-s, s, h-s, s, h-c, 0f, h-c,
        0f, c, s, c, s, s, c, s)
}

object RetroShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = framePoints(size.width, size.height, 4f * density.density)
        return Outline.Generic(Path().apply {
            moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            close()
        })
    }
}

val PixelShape: Shape = RetroShape

/** Flat fill and an inset 2dp outline. No shadows, blur, or per-frame allocation. */
class PixelFrameDrawable(private val fill: Int, private val outline: Int, private val density: Float) : Drawable() {
    private val paint = Paint()
    private val outer = android.graphics.Path()
    private val inner = android.graphics.Path()
    private var frameAlpha = 255

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        fun path(target: android.graphics.Path, inset: Float) {
            target.reset()
            val points = framePoints((bounds.width() - inset * 2).coerceAtLeast(0f),
                (bounds.height() - inset * 2).coerceAtLeast(0f), (4 * density - inset).coerceAtLeast(0f))
            target.moveTo(bounds.left + inset + points[0], bounds.top + inset + points[1])
            for (i in 2 until points.size step 2) target.lineTo(bounds.left + inset + points[i], bounds.top + inset + points[i + 1])
            target.close()
        }
        path(outer, 0f)
        path(inner, 2 * density)
    }

    override fun draw(canvas: Canvas) {
        paint.color = outline
        paint.alpha = frameAlpha
        canvas.drawPath(outer, paint)
        paint.color = fill
        paint.alpha = frameAlpha
        canvas.drawPath(inner, paint)
    }
    override fun setAlpha(alpha: Int) { frameAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun retroBackground(fill: Int, outline: Int, density: Float): Drawable = PixelFrameDrawable(fill, outline, density)

@Volatile private var pixelTypeface: Typeface? = null
fun retroTypeface(context: Context): Typeface = pixelTypeface ?: synchronized(RetroShape) {
    pixelTypeface ?: Typeface.createFromAsset(context.assets, "Silkscreen-Regular.ttf").also { pixelTypeface = it }
}

data class ShadowPalette(val fill: Int, val text: Int, val outline: Int)
object ShadowStyle {
    val on = ShadowPalette(0xFF352047.toInt(), 0xFFF5E9FF.toInt(), 0xFFC9A2EF.toInt())
    fun colors(checked: Boolean, palette: PokeMogPalette) = if (checked) on
        else ShadowPalette(palette.elevated, palette.text, palette.outline)
    // Original 7x7 pixel ghost. Zeroes are transparent; eyes use the surface color.
    val ghost = listOf("0011100", "0111110", "1111111", "1010101", "1111111", "1111111", "1010101")
}

class ShadowGhostDrawable(private val color: Int) : Drawable() {
    private val paint = Paint().apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val unit = minOf(bounds.width(), bounds.height()) / 7f
        ShadowStyle.ghost.forEachIndexed { y, row -> row.forEachIndexed { x, pixel ->
            if (pixel == '1') canvas.drawRect(bounds.left + x * unit, bounds.top + y * unit,
                bounds.left + (x + 1) * unit, bounds.top + (y + 1) * unit, paint)
        } }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Initial binding is silent. Only a whole-button activation invokes the callback. */
// The overlay uses a platform theme on API 29+; all tinting and backgrounds are supplied explicitly.
@SuppressLint("AppCompatCustomView")
class RetroShadowButton(context: Context, checked: Boolean, enabled: Boolean,
    palette: PokeMogPalette, onCheckedChange: (Boolean) -> Unit) : ToggleButton(context) {
    init {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        textOn = "Shadow ON"
        textOff = "Shadow OFF"
        isAllCaps = false
        textSize = 14f
        typeface = retroTypeface(context)
        contentDescription = "Shadow"
        stateListAnimator = null
        backgroundTintList = null
        buttonDrawable = null
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        fun style() {
            val colors = ShadowStyle.colors(isChecked, palette)
            background = retroBackground(colors.fill, colors.outline, density)
            setTextColor(colors.text)
            setCompoundDrawablesRelative(ShadowGhostDrawable(colors.text).apply { setBounds(0, 0, dp(21), dp(21)) }, null, null, null)
            compoundDrawablePadding = dp(8)
            if (Build.VERSION.SDK_INT >= 30) stateDescription = if (isChecked) "On" else "Off"
        }
        isChecked = checked
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.6f
        style()
        setOnCheckedChangeListener { _, _ -> style() }
        setOnClickListener { if (isEnabled) onCheckedChange(isChecked) }
    }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun performClick(): Boolean = if (isEnabled) super.performClick() else false
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
        info.isCheckable = true
        info.isChecked = isChecked
    }
}
