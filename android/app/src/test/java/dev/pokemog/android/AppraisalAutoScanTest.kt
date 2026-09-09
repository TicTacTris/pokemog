package dev.pokemog.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class AppraisalAutoScanTest {
    private val pokemon = Pokemon("example", "Example", 100, 100, 100)
    private val ivs = IVs(10, 10, 10)
    private val regions = listOf(
        OcrRegion("CP500", 100, 40, 196, 64),
        OcrRegion("Example", 100, 190, 196, 214),
        OcrRegion("100/100 HP", 100, 230, 196, 254),
        OcrRegion("Your Example is small", 20, 500, 280, 520),
        OcrRegion("Example", 65, 500, 125, 520),
    )

    private class Frame {
        val width = 300
        val height = 600
        val pixels = IntArray(width * height) { -1 }
        fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
        }
        fun fields(variant: Int = 0, changedField: Int = -1) {
            for ((index, top) in listOf(190, 40, 230).withIndex()) {
                val background = if (index == 1) 0xff304050.toInt() else -1
                val ink = if (index == 1) -1 else 0xff303040.toInt()
                rect(100, top, 196, top + 24, background)
                for (x in 0..7) {
                    val shift = if (index == changedField) variant * 4 else 0
                    rect(102 + x * 11, top + 4 + shift, 106 + x * 11, top + 12 + shift, ink)
                }
            }
            rect(65, 502, 90, 510, 0xff303040.toInt())
        }
        fun bars(iv: Int = 10, shift: Int = 0) {
            rect(0, 340, 200, 490, -1)
            for (row in 0..2) for (segment in 0..2) {
                val x = 35 + segment * 53
                val y = 360 + row * 30 + shift
                rect(x, y, x + 50, y + 6, 0xffdcdedd.toInt())
                rect(x, y, x + (iv - segment * 5).coerceIn(0, 5) * 10, y + 6, 0xfff09141.toInt())
            }
        }
        fun pixel(x: Int, y: Int): Int {
            check(x in 0 until width && y in 0 until height)
            return pixels[y * width + x]
        }
    }

    private fun frame() = Frame().apply { fields(); bars() }
    private fun scan(frame: Frame, boxes: List<OcrRegion> = regions, sourceScale: Int = 1): ScanResult {
        val scan = ScanResult("", listOf(pokemon.id), ivs, 500, 100)
        return scan.copy(autoAnchors = AutoScanAnchors.from(scan, boxes, PokemonNameIndex(listOf(pokemon)),
            AppraisalBarDetector.detectWithBounds(frame.pixels, frame.width, frame.height),
            frame.width, frame.height, frame.width * sourceScale, frame.height * sourceScale, frame.pixels))
    }
    private fun summary(): ScanSummary {
        val stats = Stats(500, 100, 80.0, 80.0, 640000.0)
        val evolution = EvolutionProjection(pokemon, listOf(20.0 to stats), null, 0.0, 50.0, stats, "", 1)
        return ScanSummary(pokemon, ivs, false, true, "", listOf(
            ScanLeague(1500, "Great", Assessment(listOf(20.0), "", listOf(evolution)))))
    }
    private fun probe(frame: Frame) = checkNotNull(AutoScanProbe.create(scan(frame), summary()))

    @Test fun sharedFormsCalibrateFromOriginalScanWithoutReplacingCandidateBaseline() {
        val frame = frame()
        val forms = listOf(pokemon, pokemon.copy(id = "example_other", name = "Example (Other)"))
        val original = scan(frame).copy(candidates = forms.map { it.id })
        val shared = summary().copy(pokemon = null, unresolvedCandidateIdentities = forms)
        val probe = AutoScanProbe.create(original, shared)
        assertNotNull(probe)
        assertNotNull(probe!!.calibrate(frame.width, frame.height, frame::pixel))
        assertEquals(forms.map { it.id }, original.candidates)
        assertEquals(ivs, original.autoAnchors!!.baseline.ivs)
        assertNull(AutoScanProbe.create(original, shared.copy(leagues = emptyList())))
    }
    private fun sample(probe: AutoScanProbe, frame: Frame) = checkNotNull(probe.sample(frame.width, frame.height, frame::pixel))
    private fun evidence(iv: Int = 10, field: Int = -1, width: Int = 300): AutoSample =
        AutoSample(width, 600, IVs(iv, 10, 10), List(3) { index ->
            ByteArray(576) { if (index == field && it < 50) 1 else 0 }
        })

    @Test fun detailedDetectorPreservesRealCroppedFixtureParity() {
        for (name in listOf("giratina-ffmpeg", "giratina-imageio", "shinx-native", "shinx-analyzed", "s23-native")) {
            val json = javaClass.getResourceAsStream("/appraisal-$name.json")!!.bufferedReader().use { JSONObject(it.readText()) }
            val width = json.getInt("width")
            val height = json.getInt("height")
            val pixels = IntArray(width * height)
            val runs = json.getJSONArray("runs")
            var offset = 0
            for (i in 0 until runs.length() step 2) {
                pixels.fill(runs.getInt(i + 1), offset, offset + runs.getInt(i))
                offset += runs.getInt(i)
            }
            val detailed = checkNotNull(AppraisalBarDetector.detectWithBounds(pixels, width, height))
            assertEquals(AppraisalBarDetector.detect(pixels, width, height), detailed.ivs)
            assertEquals(3, detailed.bars.size)
            assertTrue(detailed.bars.all { it.left >= detailed.bounds.left && it.right <= detailed.bounds.right &&
                it.top >= detailed.bounds.top && it.bottom <= detailed.bounds.bottom && it.bottom > it.top })
        }
    }

    @Test fun metadataUsesHeaderOrDialogueWordAndRequiresObservedLabels() {
        val frame = frame()
        val scan = scan(frame)
        assertEquals(190.0 / 600, scan.autoAnchors!!.name.top, 0.0001)
        assertEquals(500.0 / 600, scan(frame, regions.filterNot { it.top == 190 }).autoAnchors!!.name.top, 0.0001)
        assertNull(scan(frame, regions.filterNot { it.text.contains("CP") }).autoAnchors)
        assertNull(AutoScanProbe.create(scan.copy(cp = null), summary()))
        assertNull(AutoScanProbe.create(scan.copy(autoAnchors = null), summary()))
        assertNull(AutoScanProbe.create(scan, summary().copy(pokemon = null)))
        assertNull(AutoScanProbe.create(scan, summary().copy(leagues = emptyList())))
    }

    @Test fun repeatedFramesSpriteAndSmallColorNoiseDoNotTrigger() {
        val frame = frame()
        val probe = probe(frame)
        val tracker = AutoScanTracker()
        tracker.seed(probe.calibrate(frame.width, frame.height, frame::pixel), 0)
        for (time in 250L..5000L step 250) {
            frame.rect(230, 100, 290, 330, time.toInt() or (255 shl 24))
            // Ink remains in the same binary class; no raw RGB hashing.
            frame.rect(102, 194, 106, 202, 0xff343542.toInt())
            val sample = sample(probe, frame)
            assertTrue(probe.baseline.same(sample))
            assertFalse(tracker.observe(sample, time))
        }
    }

    @Test fun calibrationUsesNativeMasksInsteadOfResizedEvidence() {
        val frame = frame()
        val sourceWidth = 1440
        val sourceHeight = 3088
        val source = IntArray(sourceWidth * sourceHeight) { index ->
            val x = index % sourceWidth
            val y = index / sourceWidth
            val fx = x * frame.width / sourceWidth
            val fy = y * frame.height / sourceHeight
            // Fine synthetic HP glyph strokes alias under the same downscale as the real capture.
            if (fx in 100 until 196 && fy in 230 until 254)
                if (x % 6 < 2) 0xff303040.toInt() else -1
                else frame.pixel(fx, fy)
        }
        fun sourcePixel(x: Int, y: Int): Int {
            check(x in 0 until sourceWidth && y in 0 until sourceHeight)
            return source[y * sourceWidth + x]
        }
        val analysisWidth = 933
        val analysisHeight = 2000
        // Pixel-center bilinear RGB resampling, without desktop or Android graphics dependencies.
        val pixels = IntArray(analysisWidth * analysisHeight) { index ->
            val sx = ((index % analysisWidth + .5) * sourceWidth / analysisWidth - .5).coerceIn(0.0, sourceWidth - 1.0)
            val sy = ((index / analysisWidth + .5) * sourceHeight / analysisHeight - .5).coerceIn(0.0, sourceHeight - 1.0)
            val x = sx.toInt()
            val y = sy.toInt()
            val dx = sx - x
            val dy = sy - y
            val topLeft = sourcePixel(x, y)
            val topRight = sourcePixel(minOf(x + 1, sourceWidth - 1), y)
            val bottomLeft = sourcePixel(x, minOf(y + 1, sourceHeight - 1))
            val bottomRight = sourcePixel(minOf(x + 1, sourceWidth - 1), minOf(y + 1, sourceHeight - 1))
            var color = 255 shl 24
            for (shift in 0..16 step 8) {
                val top = (topLeft ushr shift and 255) * (1 - dx) + (topRight ushr shift and 255) * dx
                val bottom = (bottomLeft ushr shift and 255) * (1 - dx) + (bottomRight ushr shift and 255) * dx
                color = color or (((top * (1 - dy) + bottom * dy).roundToInt()) shl shift)
            }
            color
        }
        val boxes = regions.map { box -> OcrRegion(box.text,
            (box.left.toDouble() / frame.width * analysisWidth).roundToInt(),
            (box.top.toDouble() / frame.height * analysisHeight).roundToInt(),
            (box.right.toDouble() / frame.width * analysisWidth).roundToInt(),
            (box.bottom.toDouble() / frame.height * analysisHeight).roundToInt()) }
        val scan = ScanResult("", listOf(pokemon.id), ivs, 500, 100)
        val anchors = checkNotNull(AutoScanAnchors.from(scan, boxes, PokemonNameIndex(listOf(pokemon)),
            AppraisalBarDetector.detectWithBounds(pixels, analysisWidth, analysisHeight),
            analysisWidth, analysisHeight, sourceWidth, sourceHeight, pixels))
        val probe = checkNotNull(AutoScanProbe.create(scan.copy(autoAnchors = anchors), summary()))
        val calibrated = checkNotNull(probe.calibrate(sourceWidth, sourceHeight, ::sourcePixel))
        assertEquals(ivs, calibrated.ivs)
        assertFalse("The analyzed masks must not be reused for this raw source", probe.baseline.same(calibrated))
        val tracker = AutoScanTracker()
        tracker.seed(calibrated, 0)
        for (time in 250L..5000L step 250) {
            val current = checkNotNull(probe.sample(sourceWidth, sourceHeight, ::sourcePixel))
            assertTrue(calibrated.same(current))
            assertFalse(tracker.observe(current, time))
        }
    }

    @Test fun calibrationRejectsOtherIvsAbsenceAndResizeWithoutRetainingReader() {
        val frame = frame()
        val probe = probe(frame)
        assertNotNull(probe.calibrate(300, 600, frame::pixel))
        frame.bars(15)
        assertNotNull(probe.sample(300, 600, frame::pixel))
        assertNull(probe.calibrate(300, 600, frame::pixel))
        assertNull(probe.calibrate(600, 300) { _, _ -> error("Resize cannot read pixels") })
        frame.rect(0, 340, 200, 490, -1)
        assertNull(probe.calibrate(300, 600, frame::pixel))
    }

    @Test fun standaloneHpCanBeAboveOrBelowValueButNotDistantOrMisaligned() {
        val frame = frame()
        for (labelAbove in listOf(true, false)) {
            val value = OcrRegion("100/100", 100, 230, 196, 254)
            val label = OcrRegion("HP", 128, if (labelAbove) 214 else 256, 166, if (labelAbove) 228 else 270)
            frame.rect(label.left, label.top, label.right, label.bottom, -1)
            frame.rect(label.left + 2, label.top + 2, label.left + 12, label.bottom - 2, 0xff303040.toInt())
            val boxes = regions.filterNot { it.text.contains("HP") } + listOf(value, label)
            val anchors = checkNotNull(scan(frame, boxes).autoAnchors)
            assertEquals(minOf(label.top, value.top) / 600.0, anchors.hp.top, .0001)
            assertEquals(maxOf(label.bottom, value.bottom) / 600.0, anchors.hp.bottom, .0001)
            assertNull(scan(frame, boxes.filterNot { it == label } + label.copy(left = 250, right = 288)).autoAnchors)
            assertNull(scan(frame, boxes.filterNot { it == label } + label.copy(top = 300, bottom = 314)).autoAnchors)
        }
        val cpAboveValue = regions.filterNot { it.text.startsWith("CP") } + listOf(
            OcrRegion("500", 100, 40, 196, 64), OcrRegion("CP", 128, 66, 166, 80))
        assertNull(scan(frame, cpAboveValue).autoAnchors) // Only HP permits preceding numeric geometry.
    }

    @Test fun separateLabelValueGeometryAndNormalizedNativeSampling() {
        val frame = frame()
        val split = regions.filterNot { it.text.startsWith("CP") } + listOf(
            OcrRegion("CP", 100, 40, 126, 64), OcrRegion("500", 128, 40, 196, 64))
        val scan = scan(frame, split, sourceScale = 10)
        val probe = checkNotNull(AutoScanProbe.create(scan, summary()))
        assertEquals(100.0 / 300, scan.autoAnchors!!.cp.left, .0001)
        assertEquals(196.0 / 300, scan.autoAnchors.cp.right, .0001)
        var calls = 0
        val sample = checkNotNull(probe.sample(3000, 6000) { x, y ->
            assertTrue(x in 0 until 3000 && y in 0 until 6000)
            calls++
            frame.pixel(x / 10, y / 10)
        })
        assertTrue(calls <= 80000)
        assertTrue(probe.baseline.same(sample))
        assertNull(scan(frame, split.map { if (it.text == "500") it.copy(top = 100, bottom = 124) else it }).autoAnchors)
    }

    @Test fun sparseMaskNoiseDoesNotAccumulateIntoChange() {
        val tracker = AutoScanTracker()
        val base = evidence()
        tracker.seed(base, 0)
        for (time in 250L..5000L step 250) {
            val masks = base.masks.map { it.copyOf() }
            masks[1][(time / 250).toInt()] = 1
            assertFalse(tracker.observe(AutoSample(300, 600, ivs, masks), time))
        }
    }

    @Test fun eachHeaderFieldAndBarsCanTriggerOnceAfterSettling() {
        for (field in -1..2) {
            val frame = frame()
            val probe = probe(frame)
            val tracker = AutoScanTracker()
            tracker.seed(probe.calibrate(frame.width, frame.height, frame::pixel), 0)
            if (field == -1) frame.bars(15) else frame.fields(1, field)
            val changed = sample(probe, frame)
            assertFalse(tracker.observe(changed, 750))
            assertFalse(tracker.observe(changed, 1000))
            assertTrue("field $field", tracker.observe(changed, 1250))
            tracker.completeAttempt()
            for (time in 1500L..4000L step 250) assertFalse(tracker.observe(changed, time))
        }
    }

    @Test fun shiftedPanelIsFoundButMissingBarsAndBlankFieldsPause() {
        val frame = frame()
        val probe = probe(frame)
        frame.bars(10, 30)
        assertTrue(probe.baseline.same(sample(probe, frame)))
        frame.rect(0, 340, 200, 490, -1)
        assertNull(probe.sample(300, 600, frame::pixel))
        frame.bars()
        frame.rect(100, 40, 196, 64, -1)
        assertNull(probe.capture(300, 600, frame::pixel))
    }

    @Test fun captureOwnsPixelsAndRespectsBoundsAndAllocationCap() {
        val frame = frame()
        val probe = probe(frame)
        var calls = 0
        val snapshot = checkNotNull(probe.capture(300, 600) { x, y -> calls++; frame.pixel(x, y) })
        assertTrue(calls <= 80000)
        assertTrue(snapshot.pixels.size <= 192 * 400)
        frame.pixels.fill(0)
        assertNotNull(probe.analyze(snapshot))
        assertNull(probe.capture(600, 300) { _, _ -> error("No reads on resize") })
        assertNull(probe.capture(Int.MAX_VALUE, Int.MAX_VALUE) { _, _ -> error("No allocation on invalid dimensions") })
        assertNull(probe.capture(0, 0) { _, _ -> error("No reads on empty frame") })
        assertNull(probe(frame()).analyze(snapshot))
    }

    @Test fun trackerHandlesAnimationCooldownFailureAndSuccessfulCompletion() {
        val tracker = AutoScanTracker()
        tracker.seed(evidence(), 0)
        for (i in 1..6) assertFalse(tracker.observe(evidence(iv = i), i * 250L))
        val settled = evidence(iv = 6)
        assertFalse(tracker.observe(settled, 1750))
        assertTrue(tracker.observe(settled, 2000))
        assertFalse(tracker.observe(evidence(iv = 7), 2250)) // in flight
        tracker.completeAttempt()
        for (time in 2500L..3500L step 250) assertFalse(tracker.observe(settled, time))
        val next = evidence(iv = 8)
        assertFalse(tracker.observe(next, 3750))
        assertFalse(tracker.observe(next, 4000))
        assertTrue(tracker.observe(next, 4250))
        tracker.completeAttempt(next)
        for (time in 4500L..6000L step 250) assertFalse(tracker.observe(next, time))
    }

    @Test fun cooldownAndAbsenceDoNotCreateRetriesOrCountAsStability() {
        val tracker = AutoScanTracker()
        tracker.seed(evidence(), 0)
        val changed = evidence(field = 1)
        for (time in 250L..1000L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 1250))
        tracker.completeAttempt()
        assertFalse(tracker.observe(null, 1500))
        assertFalse(tracker.observe(null, 2000))
        for (time in 2250L..3250L step 250) assertFalse(tracker.observe(changed, time))
        assertFalse(tracker.observe(evidence(field = 2), 3500))
        assertFalse(tracker.observe(evidence(field = 2), 5000)) // unobserved gap
        assertFalse(tracker.observe(null, 5250))
        assertFalse(tracker.observe(evidence(field = 2), 5500))
        assertFalse(tracker.observe(evidence(field = 2), 5750))
        assertTrue(tracker.observe(evidence(field = 2), 6000))
    }

    @Test fun failedSignatureCanRetryOnlyAfterDifferentSettledState() {
        val tracker = AutoScanTracker()
        val base = evidence()
        val changed = evidence(field = 0)
        tracker.seed(base, 0)
        for (time in 250L..1000L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 1250))
        tracker.completeAttempt()
        for (time in 1500L..2250L step 250) assertFalse(tracker.observe(base, time))
        assertTrue(tracker.observe(base, 2500))
        tracker.completeAttempt(base)
        for (time in 2750L..3500L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 3750))
    }

    @Test fun pendingMatchesIsPureAndDoesNotUnblockFailedSignature() {
        val tracker = AutoScanTracker()
        val changed = evidence(field = 0)
        assertFalse(tracker.pendingMatches(changed))
        tracker.seed(evidence(), 0)
        for (time in 250L..1000L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 1250))
        assertTrue(tracker.pendingMatches(changed))
        assertFalse(tracker.pendingMatches(null))
        assertFalse(tracker.pendingMatches(evidence(field = 1)))
        assertFalse(tracker.pendingMatches(evidence(width = 600)))
        assertTrue(tracker.pendingMatches(changed))
        tracker.completeAttempt()
        assertFalse(tracker.pendingMatches(changed))
        for (time in 1500L..3000L step 250) assertFalse(tracker.observe(changed, time))
        tracker.reset()
        assertFalse(tracker.pendingMatches(changed))
    }

    @Test fun disabledResetResizeAndClockReversalDisarm() {
        val tracker = AutoScanTracker()
        val changed = evidence(field = 0)
        assertFalse(tracker.observe(changed, 5000))
        for (mode in 0..3) {
            tracker.seed(evidence(), 0)
            assertFalse(tracker.observe(changed, 250))
            when (mode) {
                0 -> tracker.reset()
                1 -> assertFalse(tracker.observe(evidence(width = 301), 500))
                2 -> assertFalse(tracker.observe(changed, 200))
                3 -> tracker.seed(null, 500)
            }
            tracker.completeAttempt(changed) // late completion cannot rearm
            for (time in 500L..3000L step 250) assertFalse(tracker.observe(changed, time))
        }
    }

    @Test fun interruptedAttemptCanResumeOnTheSameAppraisalWithoutLosingCooldown() {
        val tracker = AutoScanTracker()
        val changed = evidence(field = 0)
        tracker.seed(evidence(), 0)
        for (time in 250L..1000L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 1250))
        tracker.abortAttempt()
        assertFalse(tracker.pendingMatches(changed))
        assertFalse(tracker.observe(null, 1500))
        for (time in 1750L..2250L step 250) assertFalse(tracker.observe(changed, time))
        assertTrue(tracker.observe(changed, 2500))
        tracker.completeAttempt(changed)
        for (time in 2750L..4000L step 250) assertFalse(tracker.observe(changed, time))
    }

    @Test fun blankEvidenceBreaksStabilityAndOldPostCaptureEvidenceExpires() {
        val tracker = AutoScanTracker()
        val changed = evidence(field = 0)
        tracker.seed(evidence(), 0)
        assertFalse(tracker.observe(changed, 1250))
        assertFalse(tracker.observe(null, 1500))
        assertFalse(tracker.observe(changed, 1750))
        assertFalse(tracker.observe(changed, 2000))
        assertTrue(tracker.observe(changed, 2250))
        assertFalse(tracker.pendingMatches(null))
        assertTrue(freshAutoEvidence(500, 280, 750))
        assertFalse(freshAutoEvidence(500, 280, 2000))
        assertFalse(freshAutoEvidence(250, 280, 500))
        assertFalse(freshAutoEvidence(600, 280, 500))
    }

    @Test fun returningToBaselineAfterInterruptedAttemptRecoversItsClearedResult() {
        val tracker = AutoScanTracker()
        val first = evidence()
        val next = evidence(field = 0)
        tracker.seed(first, 0)
        for (time in 250L..1000L step 250) assertFalse(tracker.observe(next, time))
        assertTrue(tracker.observe(next, 1250))
        assertFalse(tracker.pendingMatches(first))
        tracker.abortAttempt()
        for (time in 1500L..2250L step 250) assertFalse(tracker.observe(first, time))
        assertTrue(tracker.observe(first, 2500))
        tracker.completeAttempt(first)
        for (time in 2750L..4000L step 250) assertFalse(tracker.observe(first, time))
    }
}
