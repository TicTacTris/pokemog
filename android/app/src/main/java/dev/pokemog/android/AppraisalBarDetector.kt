package dev.pokemog.android

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Upright pixel bounds, right and bottom exclusive. */
data class AppraisalBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class AppraisalDetection(val ivs: IVs, val bounds: AppraisalBounds, val bars: List<AppraisalBounds>)

/** Conservative raster heuristic, not a UI recognizer. Unknown/ambiguous bars return null.
 * Requires opaque fill, neutral tracks, equal thirds, prefix fills and stable aligned rows.
 * Identically shaped unrelated graphics cannot be distinguished without label anchors.
 */
object AppraisalBarDetector {
    private data class Run(val x: Int, var end: Int, var fill: Double, val valid: Boolean)
    private data class Bar(
        var x: Int, var end: Int, val y: Int, var bottom: Int,
        var stable: Boolean, val iv: Int, var gaps: List<Int>,
    )

    fun detect(pixels: IntArray, width: Int, height: Int): IVs? = detectWithBounds(pixels, width, height)?.ivs

    fun detectWithBounds(pixels: IntArray, width: Int, height: Int): AppraisalDetection? {
        if (width < 1 || height < 1 || width.toLong() * height != pixels.size.toLong()) return null
        fun color(x: Int, y: Int): Int {
            val pixel = pixels[y * width + x]
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            if (pixel ushr 24 < 240) return 0
            if (r >= 190 && g in 35..195 && b in 25..150 && r - g >= 40 && r - b >= 65) return 2
            if (r in 180..240 && maxOf(r, g, b) - minOf(r, g, b) <= 12) return 1
            return 0
        }
        val bars = mutableListOf<Bar>()
        for (y in 0 until height) {
            val runs = mutableListOf<Run>()
            var x = 0
            while (x < width) {
                var kind = color(x, y)
                if (kind == 0) {
                    x++
                    continue
                }
                val start = x
                var fill = 0
                var gray = false
                var valid = true
                while (kind != 0) {
                    if (kind == 2) {
                        if (gray) valid = false
                        fill++
                    } else gray = true
                    x++
                    kind = if (x < width) color(x, y) else 0
                }
                val previous = runs.lastOrNull()
                // JPEG chroma bleed is not a divider: bridge only narrow opaque fill-to-gray blends.
                var oldBridge = true
                if (previous != null && previous.fill == (previous.end - previous.x).toDouble() && fill == 0 &&
                    start - previous.end <= maxOf(1.0, (x - previous.x) / 30.0, minOf(4.0, (x - previous.x) / 15.0)) &&
                    (previous.end until start).all { col ->
                        val pixel = pixels[y * width + col]
                        val r = (pixel ushr 16) and 255
                        val g = (pixel ushr 8) and 255
                        val b = pixel and 255
                        oldBridge = oldBridge && r <= 245
                        pixel ushr 24 >= 240 && r >= 180 && (r <= 245 || r - b >= 16) &&
                            g >= 150 && b >= 100 && r >= g && g >= b
                    }
                ) {
                    oldBridge = oldBridge && start - previous.end <= maxOf(1.0, (x - previous.x) / 30.0)
                    // New blends straddle the endpoint; preserve half-pixels without loosening IV tolerance.
                    if (!oldBridge) previous.fill += (start - previous.end) / 2.0
                    previous.end = x
                } else runs.add(Run(start, x, fill.toDouble(), valid))
            }
            for (i in 0 until runs.size - 2) {
                val parts = runs.subList(i, i + 3)
                val first = parts[0]
                val second = parts[1]
                val third = parts[2]
                val lengths = parts.map { it.end - it.x }
                val length = lengths.sum()
                val gaps = listOf(second.x - first.end, third.x - second.end)
                if (length < 90 || lengths.max() - lengths.min() > maxOf(2, (length / 90.0).roundToInt()) ||
                    gaps.any { it < 1 || it > length / 30.0 } ||
                    abs(gaps[0] - gaps[1]) > maxOf(1.0, length / 150.0) || parts.any { !it.valid }
                ) continue
                var gray = false
                var valid = true
                for (part in parts) {
                    if (gray && part.fill > 0) valid = false
                    if (part.fill < part.end - part.x) gray = true
                }
                val raw = parts.sumOf { it.fill } / length * 15
                val iv = raw.roundToInt()
                if (!valid || abs(raw - iv) > 0.18) continue
                val previous = bars.find { bar ->
                    // JPEG rounding can erase two additional core rows between fragments.
                    y - bar.bottom <= maxOf(1.0, length / 50.0) + 2 &&
                        abs(bar.x - first.x) <= maxOf(1.0, ceil(length / 100.0)) &&
                        abs(bar.end - third.end) <= maxOf(1.0, ceil(length / 100.0)) && bar.iv == iv &&
                        bar.gaps.indices.all { abs(bar.gaps[it] - gaps[it]) <= maxOf(1.0, length / 150.0) }
                }
                if (previous != null) {
                    // Missing rows can join fragments, but only adjacent rows establish stability.
                    previous.stable = previous.stable || previous.bottom == y - 1
                    previous.bottom = y
                    if (third.end - first.x > previous.end - previous.x) {
                        previous.x = first.x
                        previous.end = third.end
                        previous.gaps = gaps
                    }
                } else bars.add(Bar(first.x, third.end, y, y, false, iv, gaps))
                if (bars.size > 200) return null
            }
        }
        val stable = bars.filter { it.stable && it.bottom - it.y + 1 <= (it.end - it.x) / 10.0 }
        var match: AppraisalDetection? = null
        for (a in stable.indices) for (b in a + 1 until stable.size) for (c in b + 1 until stable.size) {
            val triple = listOf(stable[a], stable[b], stable[c]).sortedBy { it.y }
            val (top, middle, bottom) = triple
            val w = top.end - top.x
            val h = triple.maxOf { it.bottom - it.y + 1 }
            if (triple.any { bar ->
                abs(bar.x - top.x) > maxOf(1.0, w / 150.0) ||
                    abs(bar.end - top.end) > maxOf(1.0, w / 150.0) ||
                    bar.gaps.indices.any { abs(bar.gaps[it] - top.gaps[it]) > ceil(w / 150.0) }
            }) continue
            // Resampled orange and red cores have different heights; compare their centers.
            val spacing = (middle.y + middle.bottom - top.y - top.bottom) / 2.0
            if (spacing < h * 2 || spacing > w * 0.6 ||
                abs((bottom.y + bottom.bottom - middle.y - middle.bottom) / 2.0 - spacing) > maxOf(2.0, h / 2.0)
            ) continue
            if (triple.all { it.iv == 0 }) continue
            if (match != null) return null
            val rects = triple.map { AppraisalBounds(it.x, it.y, it.end, it.bottom + 1) }
            match = AppraisalDetection(IVs(top.iv, middle.iv, bottom.iv),
                AppraisalBounds(rects.minOf { it.left }, rects.minOf { it.top },
                    rects.maxOf { it.right }, rects.maxOf { it.bottom }), rects)
        }
        return match
    }
}
