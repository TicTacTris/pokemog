package dev.pokemog.android

import kotlin.math.abs

/** Normalized to the upright source crop, never to display/overlay coordinates. */
data class AutoAnchorRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    internal val valid: Boolean get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top
}

/** Geometry and bounded foreground masks only: no OCR strings or retained screenshots. */
class AutoScanAnchors internal constructor(
    val name: AutoAnchorRect,
    val cp: AutoAnchorRect,
    val hp: AutoAnchorRect,
    val bars: List<AutoAnchorRect>,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    internal val baseline: AutoSample,
) {
    companion object {
        internal fun from(
            scan: ScanResult, regions: List<OcrRegion>, names: PokemonNameIndex,
            detection: AppraisalDetection?, width: Int, height: Int,
            sourceWidth: Int, sourceHeight: Int, pixels: IntArray,
        ): AutoScanAnchors? {
            if (detection == null || scan.cp == null || scan.hp == null || scan.candidates.isEmpty()) return null
            val boxes = regions.filter { it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height &&
                it.right > it.left && it.bottom > it.top }
            fun rect(b: OcrRegion) = AutoAnchorRect(b.left.toDouble() / width, b.top.toDouble() / height,
                b.right.toDouble() / width, b.bottom.toDouble() / height)
            fun area(b: OcrRegion) = (b.right - b.left).toLong() * (b.bottom - b.top)
            val matchesName = names.matcher(scan.candidates.toSet())
            val matches = boxes.filter { matchesName(it.text) }
            // Prefer an actual large header. A nickname can leave only a species word in dialogue.
            val header = matches.filter { it.bottom < detection.bounds.top && it.top < height * .6 }
                .sortedWith(compareByDescending<OcrRegion> { it.bottom - it.top }.thenBy { it.top }.thenBy { area(it) })
                .firstOrNull()
            val name = header ?: matches.filter { it.text.trim().split(Regex("\\s+")).size <= 3 }
                .minByOrNull(::area) ?: return null
            fun field(label: String, value: Int): OcrRegion? {
                val labelPattern = Regex("(?<![A-Za-z])$label(?![A-Za-z])", RegexOption.IGNORE_CASE)
                val numberPattern = Regex("[0-9]+(?:,[0-9]{3})*")
                fun containsValue(b: OcrRegion) = numberPattern.findAll(b.text).any {
                    it.value.replace(",", "").toIntOrNull() == value
                }
                val labels = boxes.filter { labelPattern.containsMatchIn(it.text) && it.bottom < detection.bounds.top }
                val joined = labels.filter(::containsValue).toMutableList()
                for (l in labels.filter { it.text.trim().removeSuffix(":").equals(label, true) }) {
                    for (v in boxes.filter { containsValue(it) && it.bottom < detection.bounds.top }) {
                        val h = minOf(l.bottom - l.top, v.bottom - v.top)
                        val overlapY = minOf(l.bottom, v.bottom) - maxOf(l.top, v.top)
                        val overlapX = minOf(l.right, v.right) - maxOf(l.left, v.left)
                        val horizontal = overlapY * 2 >= h &&
                            (v.left - l.right in 0..h || (label == "HP" && l.left - v.right in 0..h))
                        val vertical = (v.top - l.bottom in 0..h || (label == "HP" && l.top - v.bottom in 0..h)) &&
                            overlapX * 2 >= minOf(l.right - l.left, v.right - v.left)
                        if (horizontal || vertical) joined.add(OcrRegion("", minOf(l.left, v.left), minOf(l.top, v.top),
                            maxOf(l.right, v.right), maxOf(l.bottom, v.bottom)))
                    }
                }
                return joined.minByOrNull(::area)
            }
            val cp = field("CP", scan.cp) ?: return null
            val hp = field("HP", scan.hp) ?: return null
            // Calibrate a detail header, not merely any three bars elsewhere on the screen.
            if (cp.top >= hp.top || cp.top >= height * .4 || hp.bottom >= height * .7 ||
                detection.bounds.top < height * .4 || detection.bounds.right > width * .75) return null
            val fields = listOf(rect(name), rect(cp), rect(hp))
            val masks = fieldMasks(fields, width, height) { x, y -> pixels[y * width + x] } ?: return null
            val bars = detection.bars.map { AutoAnchorRect(it.left.toDouble() / width, it.top.toDouble() / height,
                it.right.toDouble() / width, it.bottom.toDouble() / height) }
            return AutoScanAnchors(fields[0], fields[1], fields[2], bars, width, height, sourceWidth, sourceHeight,
                AutoSample(sourceWidth, sourceHeight, detection.ivs, masks))
        }
    }
}

/** A snapshot owns only its small thumbnail and text masks. Release it after analyze; never queue frames. */
class AutoProbeSnapshot internal constructor(
    internal val owner: AutoScanProbe,
    internal val pixels: IntArray,
    internal val width: Int,
    internal val height: Int,
    internal val masks: List<ByteArray>,
)

/** Recognition evidence, not a scan result. Values here must never enter calculations. */
class AutoSample internal constructor(
    val width: Int, val height: Int, internal val ivs: IVs, internal val masks: List<ByteArray>,
) {
    internal fun same(other: AutoSample): Boolean = width == other.width && height == other.height && ivs == other.ivs &&
        masks.size == other.masks.size && masks.indices.all { field ->
            val a = masks[field]
            val b = other.masks[field]
            // Compare binary foreground, allowing a few antialiasing/one-pixel sampling differences.
            a.size == b.size && a.indices.count { a[it] != b[it] } <= maxOf(3, a.size * 3 / 100)
        }
}

/**
 * Parent opt-in must default off. Create only following a successful manual scan, then calibrate on
 * that exact raw captured frame and seed with the calibrated sample (or null to disarm).
 * Capture at 3-4 Hz while the Image is open. pixel supplies opaque crop-local RGB;
 * add Image.cropRect offsets/rowStride/pixelStride in that reader. No Image escapes capture.
 * Analyze on Default, serially with tracker calls. App-only capture is recommended: occluded bars
 * pause the gate. Reset on stop/toggle-off/resize and discard pending work using a parent session ID.
 * Identical visible fields cannot prove a different Pokemon; use manual scan for those clones.
 */
class AutoScanProbe private constructor(private val anchors: AutoScanAnchors) {
    /** Analyzed-image evidence only. Resampling can change text masks: never seed a raw observer with this. */
    val baseline: AutoSample get() = anchors.baseline
    private val fields = listOf(anchors.name, anchors.cp, anchors.hp)
    // A calibrated lower-left search strip permits vertical panel motion, excluding the sprite/header.
    private val search = AutoAnchorRect(0.0, .4, (anchors.bars.maxOf { it.right } + .03).coerceAtMost(.78), 1.0)

    fun capture(width: Int, height: Int, pixel: (Int, Int) -> Int): AutoProbeSnapshot? {
        if (width != anchors.sourceWidth || height != anchors.sourceHeight || width <= 0 || height <= 0) return null
        val masks = fieldMasks(fields, width, height, pixel) ?: return null
        val scale = minOf(192.0 / (width * search.right), 400.0 / (height * (1 - search.top)), 1.0)
        val w = (width * search.right * scale).toInt().coerceAtLeast(1)
        val h = (height * (1 - search.top) * scale).toInt().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = ((x + .5) / w * search.right * width).toInt().coerceIn(0, width - 1)
            val sy = ((search.top + (y + .5) / h * (1 - search.top)) * height).toInt().coerceIn(0, height - 1)
            pixels[y * w + x] = pixel(sx, sy) or (255 shl 24)
        }
        return AutoProbeSnapshot(this, pixels, w, h, masks)
    }

    fun analyze(snapshot: AutoProbeSnapshot): AutoSample? {
        if (snapshot.owner !== this) return null
        val detection = AppraisalBarDetector.detectWithBounds(snapshot.pixels, snapshot.width, snapshot.height) ?: return null
        val expectedWidth = anchors.bars[0].right - anchors.bars[0].left
        val actualWidth = (detection.bounds.right - detection.bounds.left).toDouble() / snapshot.width * search.right
        val actualLeft = detection.bounds.left.toDouble() / snapshot.width * search.right
        if (abs(actualWidth - expectedWidth) > expectedWidth * .08 || abs(actualLeft - anchors.bars[0].left) > .025) return null
        return AutoSample(anchors.sourceWidth, anchors.sourceHeight, detection.ivs, snapshot.masks)
    }

    /** Convenience for callers already on a worker with valid pixel ownership. */
    fun sample(width: Int, height: Int, pixel: (Int, Int) -> Int): AutoSample? = capture(width, height, pixel)?.let(::analyze)

    /**
     * Run on a worker against the exact raw frame used by the successful scan, initially and after
     * every successful auto attempt. Requires the cheap gate to recover the validated IVs; retains
     * only sampled evidence, never the reader or image. A different live frame is not calibration.
     */
    fun calibrate(width: Int, height: Int, pixel: (Int, Int) -> Int): AutoSample? =
        sample(width, height, pixel)?.takeIf { it.ivs == anchors.baseline.ivs }

    companion object {
        fun create(scan: ScanResult, summary: ScanSummary): AutoScanProbe? {
            val a = scan.autoAnchors ?: return null
            if (scan.ivs == null || scan.cp == null || scan.hp == null || scan.cp < 10 || scan.hp < 10 ||
                (summary.pokemon == null && !summary.formUnresolved) || summary.ivs != scan.ivs || summary.effectiveLevels.isEmpty() ||
                a.baseline.ivs != scan.ivs ||
                a.bars.size != 3 || !(listOf(a.name, a.cp, a.hp) + a.bars).all { it.valid } ||
                a.sourceWidth <= 0 || a.sourceHeight <= 0) return null
            return AutoScanProbe(a)
        }
    }
}

private fun fieldMasks(rects: List<AutoAnchorRect>, width: Int, height: Int, pixel: (Int, Int) -> Int): List<ByteArray>? {
    if (width <= 0 || height <= 0 || rects.size != 3 || rects.any { !it.valid }) return null
    val masks = rects.mapIndexed { index, rect ->
        if ((rect.right - rect.left) * width < 12 || (rect.bottom - rect.top) * height < 6) return null
        val mask = ByteArray(48 * 12)
        for (y in 0 until 12) for (x in 0 until 48) {
            val sx = ((rect.left + (x + .5) / 48 * (rect.right - rect.left)) * width).toInt().coerceIn(0, width - 1)
            val sy = ((rect.top + (y + .5) / 12 * (rect.bottom - rect.top)) * height).toInt().coerceIn(0, height - 1)
            val c = pixel(sx, sy)
            val r = c ushr 16 and 255
            val g = c ushr 8 and 255
            val b = c and 255
            val foreground = if (index == 1) minOf(r, g, b) >= 215 && maxOf(r, g, b) - minOf(r, g, b) < 35
                else maxOf(r, g, b) < 155
            mask[y * 48 + x] = if (foreground) 1 else 0
        }
        // Blank, covered and nearly solid fields are not evidence of an appraisal header.
        if (mask.count { it.toInt() == 1 } !in 12..400) return null
        mask
    }
    return masks
}

/**
 * Pure, disabled until seed. observe returns true once to request OCR, never OCR itself.
 * Call completeAttempt on every outcome (including failure); failed signatures remain blocked until
 * a different settled state. Successful OCR may supply a raw-calibrated baseline and replace probe.
 * Missing bars/fields pause, not re-entry triggers. reset clears all evidence, including in-flight state.
 */
class AutoScanTracker(private val stableMs: Long = 500, private val minimumIntervalMs: Long = 1200) {
    init { require(stableMs >= 500 && minimumIntervalMs >= 1200) }
    private var baseline: AutoSample? = null
    private var candidate: AutoSample? = null
    private var blocked: AutoSample? = null
    private var pending: AutoSample? = null
    private var since = 0L
    private var lastObserved: Long? = null
    private var lastAttempt: Long? = null

    fun reset() {
        baseline = null; candidate = null; blocked = null; pending = null
        lastObserved = null; lastAttempt = null; since = 0
    }

    /** Pass raw-frame calibrated evidence; failed manual scans/calibration clear the old armed state. */
    fun seed(sample: AutoSample?, nowMs: Long) {
        reset()
        if (sample == null) return
        baseline = sample
        blocked = sample
        lastObserved = nowMs
        lastAttempt = nowMs
    }

    fun observe(sample: AutoSample?, nowMs: Long): Boolean {
        val base = baseline ?: return false
        val previousTime = lastObserved
        if (previousTime != null && nowMs < previousTime) { reset(); return false }
        lastObserved = nowMs
        if (sample != null && (sample.width != base.width || sample.height != base.height)) { reset(); return false }
        if (sample == null || pending != null) { candidate = null; return false }
        // A sampling pause must not count as observed stability.
        if (previousTime != null && nowMs - previousTime > 750) candidate = null
        val old = candidate
        if (old == null || !old.same(sample)) { candidate = sample; since = nowMs; return false }
        if (nowMs - since < stableMs) return false
        if (blocked?.same(sample) == true) return false
        // Even a return to the old baseline needs a new result after another attempt replaced the UI.
        if (lastAttempt?.let { nowMs - it < minimumIntervalMs } == true) return false
        pending = sample
        blocked = sample
        lastAttempt = nowMs
        candidate = null
        return true
    }

    /** Pure stale-attempt check. False for absent evidence or no pending attempt; does not unblock it. */
    fun pendingMatches(sample: AutoSample?): Boolean = sample != null && pending?.same(sample) == true

    /** Supply calibrate() evidence from the successful scan's raw frame, not probe.baseline. */
    fun completeAttempt(successfulBaseline: AutoSample? = null) {
        if (pending == null) return
        if (successfulBaseline != null) {
            if (successfulBaseline.width != baseline?.width || successfulBaseline.height != baseline?.height) { reset(); return }
            baseline = successfulBaseline
            blocked = successfulBaseline
        }
        pending = null
        candidate = null
    }

    /** Visibility/navigation interruption is not a failed OCR reading; allow fresh settling after resume. */
    fun abortAttempt() {
        if (pending == null) return
        // The published result was cleared at attempt start, so returning to the old baseline must recover too.
        blocked = null
        pending = null
        candidate = null
    }
}

internal fun freshAutoEvidence(observedAt: Long, capturedAt: Long, nowMs: Long): Boolean =
    observedAt >= capturedAt && nowMs >= observedAt && nowMs - observedAt <= 750
