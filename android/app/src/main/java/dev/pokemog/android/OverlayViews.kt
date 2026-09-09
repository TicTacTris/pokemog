package dev.pokemog.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button

/** 48dp artwork inside a 56dp touch target. Gestures never turn a drag into a scan. */
internal class ScanOrbView(
    context: Context,
    private val palette: PokeMogPalette,
    private val busy: Boolean,
    private val beginDrag: () -> Unit,
    private val move: (Float, Float, Boolean) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var held = false
    private var cancelled = false
    private val hold = Runnable {
        if (!dragging && !cancelled && isPressed) held = performLongClick()
    }

    init {
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = if (busy) "Scanning. Tap to cancel. Long press for Stop."
            else "Scan screen. Tap to scan. Drag to move. Long press for Stop and last result."
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun performLongClick(): Boolean = super.performLongClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        PixelPokeball.draw(canvas, paint, width, height, (48f * density).toInt())
        val save = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(density, density)
        paint.isAntiAlias = true
        paint.strokeCap = Paint.Cap.ROUND
        if (busy) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = palette.outline
            canvas.drawCircle(0f, 0f, 26f, paint)
            paint.color = palette.gold
            val angle = (SystemClock.uptimeMillis() % 1400L) * 360f / 1400f
            canvas.drawArc(-26f, -26f, 26f, 26f, angle, 85f, false, paint)
            if (isShown) postInvalidateDelayed(32)
        }
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                held = false
                cancelled = false
                isPressed = true
                beginDrag()
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!held && !cancelled && (dragging || dx * dx + dy * dy > slop * slop)) {
                    dragging = true
                    isPressed = false
                    removeCallbacks(hold)
                    move(dx, dy, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                isPressed = false
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!cancelled && !held) {
                    if (dragging || dx * dx + dy * dy > slop * slop) move(dx, dy, true)
                    else performClick()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                isPressed = false
                if (dragging && !cancelled) move(event.rawX - downX, event.rawY - downY, true)
                cancelled = true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        cancelled = true
        super.onDetachedFromWindow()
    }
}

/** A CP-style upper semicircle. Missing builds have an empty track and no progress dot. */
internal class LeagueArcView(
    context: Context,
    private val palette: PokeMogPalette,
    percentile: Double?,
    private val accent: Int,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweep = OverlayGeometry.arcSweep(percentile)
    private val pixelTypeface = retroTypeface(context)
    private val value = if (sweep == null) "Not eligible" else "${percent(percentile!!.coerceIn(0.0, 100.0))}%"
    private val density = resources.displayMetrics.density
    private val preferredTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
        if (sweep == null) 13f else 22f, resources.displayMetrics)

    init {
        contentDescription = if (sweep == null) "No eligible league build" else "PvP IV percentile $value"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((144 * density).toInt(), widthMeasureSpec)
        val radius = minOf((width - 16 * density).coerceAtLeast(0f) / 2, maxOf(64 * density, preferredTextSize + 8 * density))
        setMeasuredDimension(width, resolveSize((radius + 18 * density).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf((width - 16 * density).coerceAtLeast(0f) / 2, (height - 18 * density).coerceAtLeast(0f))
        val cx = width / 2f
        val cy = height - 8 * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.outline
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 180f, 180f, false, paint)
        if (sweep != null) {
            paint.color = accent
            if (sweep > 0) canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 180f, sweep, false, paint)
            val angle = Math.toRadians(180.0 + sweep)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx + radius * kotlin.math.cos(angle).toFloat(),
                cy + radius * kotlin.math.sin(angle).toFloat(), 4 * density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.typeface = pixelTypeface
        paint.textSize = preferredTextSize
        val available = (width - 16 * density).coerceAtLeast(1f)
        if (paint.measureText(value) > available) paint.textSize *= available / paint.measureText(value)
        paint.textAlign = Paint.Align.CENTER
        paint.color = if (sweep == null) palette.muted else palette.text
        canvas.drawText(value, cx, cy - 5 * density, paint)
    }
}
