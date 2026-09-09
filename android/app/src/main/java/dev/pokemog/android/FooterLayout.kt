package dev.pokemog.android

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.roundToInt

private fun spec(size: Int, mode: Int) = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), mode)

/** Insets consume only available space, including when even padding cannot fit. */
private fun Rect.insetWithin(left: Int, top: Int, right: Int, bottom: Int): Rect {
    val x = (this.left + left.coerceAtLeast(0)).coerceAtMost(this.right)
    val y = (this.top + top.coerceAtLeast(0)).coerceAtMost(this.bottom)
    return Rect(x, y, (this.right - right.coerceAtLeast(0)).coerceAtLeast(x),
        (this.bottom - bottom.coerceAtLeast(0)).coerceAtLeast(y))
}

private val View.margins: LinearLayout.LayoutParams
    get() = layoutParams as LinearLayout.LayoutParams
private val View.horizontalMargins get() = margins.leftMargin.coerceAtLeast(0) + margins.rightMargin.coerceAtLeast(0)
private val View.verticalMargins get() = margins.topMargin.coerceAtLeast(0) + margins.bottomMargin.coerceAtLeast(0)
private fun Rect.inside(view: View) = insetWithin(view.margins.leftMargin, view.margins.topMargin,
    view.margins.rightMargin, view.margins.bottomMargin)

/**
 * Add the scrolling body first and actions second. The footer gets first claim on height.
 * EXACTLY is a parent contract; maximumHeight caps AT_MOST and UNSPECIFIED requests.
 */
internal class OverlayCardLayout(context: Context, private val maximumHeight: Int) : LinearLayout(context) {
    private val positions = Array(2) { Rect() }

    init {
        orientation = VERTICAL
        isBaselineAligned = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(childCount == 2) { "OverlayCardLayout needs a scrolling body and a footer" }
        val body = getChildAt(0)
        val footer = getChildAt(1)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val bound = when (heightMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(maximumHeight, MeasureSpec.getSize(heightMeasureSpec))
            else -> maximumHeight
        }.coerceAtLeast(0)
        var desiredWidth = suggestedMinimumWidth
        for (child in listOf(body, footer)) {
            if (child.visibility == GONE) continue
            child.measure(getChildMeasureSpec(widthMeasureSpec,
                paddingLeft + paddingRight + child.horizontalMargins, child.margins.width),
                spec(bound, MeasureSpec.AT_MOST))
            desiredWidth = maxOf(desiredWidth, paddingLeft + paddingRight + child.horizontalMargins + child.measuredWidth)
        }
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val area = Rect(0, 0, width, bound).insetWithin(paddingLeft, paddingTop, paddingRight, paddingBottom)
        fun measureIn(child: View, slot: Rect): Int {
            if (child.visibility == GONE) return 0
            val inner = slot.inside(child)
            val requestedWidth = child.margins.width
            val childWidth = if (requestedWidth >= 0) minOf(requestedWidth, inner.width()) else inner.width()
            val requestedHeight = child.margins.height
            child.measure(spec(childWidth, if (requestedWidth == LayoutParams.WRAP_CONTENT) MeasureSpec.AT_MOST else MeasureSpec.EXACTLY),
                spec(if (requestedHeight >= 0) minOf(requestedHeight, inner.height()) else inner.height(),
                    if (requestedHeight >= 0) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
            return minOf(slot.height(), child.measuredHeight + child.verticalMargins)
        }
        val footerHeight = measureIn(footer, area)
        val bodyHeight = measureIn(body, Rect(area.left, area.top, area.right, area.bottom - footerHeight))
        val height = if (heightMode == MeasureSpec.EXACTLY) bound else
            minOf(bound, maxOf(suggestedMinimumHeight, paddingTop + bodyHeight + footerHeight + paddingBottom))
        setMeasuredDimension(width, height)
        val finalArea = Rect(0, 0, width, height).insetWithin(paddingLeft, paddingTop, paddingRight, paddingBottom)
        positions[0].set(Rect(finalArea.left, finalArea.top, finalArea.right,
            finalArea.top + bodyHeight).inside(body))
        positions[1].set(Rect(finalArea.left, finalArea.bottom - footerHeight, finalArea.right,
            finalArea.bottom).inside(footer))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val position = positions[i]
            child.layout(position.left, position.top, position.left + child.measuredWidth, position.top + child.measuredHeight)
        }
    }
}

/** Natural label widths choose row vs stack. No weights, ellipsis, or font shrinking. */
internal class OverlayActionFooter(context: Context, density: Float) : LinearLayout(context) {
    private val target = (48 * density).roundToInt()
    private val gap = (8 * density).roundToInt()
    private val positions = mutableMapOf<View, Rect>()

    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
        setPadding(0, gap, 0, gap)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        child.minimumWidth = maxOf(child.minimumWidth, target)
        child.minimumHeight = maxOf(child.minimumHeight, target)
        if (child is Button) {
            child.minWidth = maxOf(child.minWidth, target)
            child.minHeight = maxOf(child.minHeight, target)
            child.includeFontPadding = true
            child.setSingleLine(false)
            child.maxLines = Int.MAX_VALUE
            child.ellipsize = null
            child.setAutoSizeTextTypeWithDefaults(Button.AUTO_SIZE_TEXT_TYPE_NONE)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        positions.clear()
        val children = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val naturalWidths = children.map { child ->
            child.measure(spec(0, MeasureSpec.UNSPECIFIED), spec(0, MeasureSpec.UNSPECIFIED))
            maxOf(target, child.measuredWidth) + child.horizontalMargins
        }
        val gaps = gap * (children.size - 1).coerceAtLeast(0)
        val naturalWidth = naturalWidths.sum() + gaps + paddingLeft + paddingRight
        val width = resolveSize(maxOf(suggestedMinimumWidth, naturalWidth), widthMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val stacked = naturalWidths.sum() + gaps > availableWidth
        orientation = if (stacked) VERTICAL else HORIZONTAL
        val widths = if (stacked) List(children.size) { availableWidth } else {
            var extra = availableWidth - naturalWidths.sum() - gaps
            naturalWidths.mapIndexed { index, natural ->
                val share = extra / (children.size - index)
                extra -= share
                natural + share
            }
        }
        val naturalHeights = children.mapIndexed { index, child ->
            child.measure(spec((widths[index] - child.horizontalMargins).coerceAtLeast(0), MeasureSpec.EXACTLY),
                spec(0, MeasureSpec.UNSPECIFIED))
            maxOf(target, child.measuredHeight) + child.verticalMargins
        }
        val naturalHeight = if (stacked) naturalHeights.sum() + gaps else naturalHeights.maxOrNull() ?: 0
        val height = resolveSize(maxOf(suggestedMinimumHeight, naturalHeight + paddingTop + paddingBottom), heightMeasureSpec)
        setMeasuredDimension(width, height)
        val area = Rect(0, 0, width, height).insetWithin(paddingLeft, paddingTop, paddingRight, paddingBottom)
        // Under impossible constraints, partition real space instead of laying buttons over each other.
        val actualGap = if (children.size < 2) 0 else minOf(gap,
            (if (stacked) area.height() else area.width()) / (children.size - 1))
        val heights = if (stacked) {
            val budget = (area.height() - actualGap * (children.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
            val minima = children.mapIndexed { index, child -> minOf(naturalHeights[index], target + child.verticalMargins) }
            val baseline = if (minima.sum() <= budget) minima else List(children.size) { 0 }
            var remaining = (budget - baseline.sum()).coerceAtLeast(0)
            var demand = naturalHeights.indices.sumOf { naturalHeights[it] - baseline[it] }
            naturalHeights.mapIndexed { index, natural ->
                val need = natural - baseline[index]
                val share = if (demand == 0) 0 else minOf(need, (remaining.toLong() * need / demand).toInt())
                remaining -= share
                demand -= need
                baseline[index] + share
            }
        } else List(children.size) { minOf(naturalHeight, area.height()) }
        var cursor = if (stacked) area.top else area.left
        children.forEachIndexed { index, child ->
            val slot = if (stacked) Rect(area.left, cursor, area.right, cursor + heights[index]) else
                Rect(cursor, area.top, cursor + widths[index], area.top + heights[index])
            val inner = slot.inside(child)
            child.measure(spec(inner.width(), MeasureSpec.EXACTLY), spec(inner.height(), MeasureSpec.EXACTLY))
            positions[child] = inner
            cursor += (if (stacked) heights[index] else widths[index]) + actualGap
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        positions.forEach { (child, bounds) -> child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom) }
    }
}
