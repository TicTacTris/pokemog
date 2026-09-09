package dev.pokemog.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AppraisalBarDetectorTest {
    private val orange = 0xfff09141.toInt()
    private val red = 0xffeb4b50.toInt()
    private val gray = 0xffdcdedd.toInt()
    private val white = -1
    private class Raster(val width: Int, val height: Int) {
        val pixels = IntArray(width * height) { -1 }
        fun detect() = AppraisalBarDetector.detect(pixels, width, height)
        fun rectangle(x: Int, y: Int, w: Int, h: Int, color: Int) {
            for (row in y until y + h) for (col in x until x + w) pixels[row * width + col] = color
        }
    }
    private fun raster(scale: Int = 1) = Raster(300 * scale, 260 * scale)
    private fun bar(image: Raster, iv: Int, y: Int, scale: Int = 1, x: Int = 35, color: Int = orange) {
        for (segment in 0..2) {
            image.rectangle((x + segment * 53) * scale, y * scale, 50 * scale, 6 * scale, gray)
            image.rectangle((x + segment * 53) * scale, y * scale,
                (iv - segment * 5).coerceIn(0, 5) * 10 * scale, 6 * scale, color)
        }
    }
    private fun fixture(name: String): JSONObject = checkNotNull(javaClass.getResourceAsStream("/$name")) {
        "Missing appraisal fixture $name"
    }.bufferedReader().use { JSONObject(it.readText()) }

    @Test fun realGiratinaJpegDecodersAndFaults() {
        for (decoder in listOf("ffmpeg", "imageio")) {
            val json = fixture("appraisal-giratina-$decoder.json")
            val source = Raster(json.getInt("width"), json.getInt("height"))
            val runs = json.getJSONArray("runs")
            var offset = 0
            for (i in 0 until runs.length() step 2) {
                val count = runs.getInt(i)
                source.pixels.fill(runs.getInt(i + 1), offset, offset + count)
                offset += count
            }
            assertEquals(source.pixels.size, offset)
            for (fault in listOf("none", "duplicate", "missing HP", "shifted HP")) {
                val image = Raster(source.width, source.height * if (fault == "duplicate") 2 else 1)
                source.pixels.copyInto(image.pixels)
                if (fault == "duplicate") source.pixels.copyInto(image.pixels, source.pixels.size)
                if (fault == "missing HP" || fault == "shifted HP") {
                    image.rectangle(0, 120, image.width, image.height - 120, white)
                    if (fault == "shifted HP") for (y in 120 until image.height) {
                        source.pixels.copyInto(image.pixels, y * image.width + 10,
                            y * image.width, y * image.width + image.width - 10)
                    }
                }
                assertEquals("$decoder $fault", if (fault == "none") IVs(8, 11, 6) else null, image.detect())
            }
        }
    }

    @Test fun boundsWarmJpegTransitions() {
        for (width in 1..5) for (kind in listOf("warm", "white", "near-white", "transparent", "endpoint", "non-prefix")) {
            val image = raster()
            for (row in 0..2) for (segment in 0..2) {
                val y = 70 + row * 30
                val x = 35 + segment * 63
                image.rectangle(x, y, 60, 2, gray)
                if (segment == 0) image.rectangle(x, y, 60, 2, orange)
                if (segment == 1) {
                    val fill = 36 - (width + 1) / 2 + if (kind == "endpoint") 5 else 0
                    image.rectangle(x, y, fill, 2, orange)
                    val transition = when (kind) {
                        "white" -> white
                        "near-white" -> 0xfffff8f0.toInt()
                        "transparent" -> 0xeffcdcb9.toInt()
                        else -> 0xfffcdcb9.toInt()
                    }
                    image.rectangle(x + fill, y, width, 2, transition)
                    if (kind == "non-prefix") image.rectangle(x + 55, y, 5, 2, orange)
                }
            }
            assertEquals("${width}px $kind", if (width <= 4 && kind == "warm") IVs(8, 8, 8) else null, image.detect())
        }
    }

    @Test fun fractionalOddTransitionsAndCoreSpacing() {
        for (width in listOf(1, 3)) for (spacingError in 1..3) {
            val image = raster()
            for (row in 0..2) for (segment in 0..2) {
                val y = 70 + row * 30 + if (row == 2) spacingError else 0
                val x = 35 + segment * 62
                image.rectangle(x, y, 59, 2, gray)
                if (segment == 0) image.rectangle(x, y, 59, 2, orange)
                if (segment == 1) {
                    // Effective total 92.5/177*15 = 7.839; truncating to 92 fails.
                    val fill = 33 - width / 2
                    image.rectangle(x, y, fill, 2, orange)
                    image.rectangle(x + fill, y, width, 2, 0xfffcdcb9.toInt())
                }
            }
            assertEquals("${width}px spacing $spacingError", if (spacingError <= 2) IVs(8, 8, 8) else null, image.detect())
        }
    }

    @Test fun preservesOriginalBridgeEndpointConvention() {
        for (r in listOf(237, 252)) {
            val image = raster()
            for (y in listOf(70, 100, 130)) {
                for (segment in 0..2) image.rectangle(35 + segment * 63, y, 60, 2, gray)
                image.rectangle(35, y, 14, 2, orange)
                image.rectangle(49, y, 2, 2, (255 shl 24) or (r shl 16) or (220 shl 8) or 192)
            }
            // Old 1.167 passes; globally adding half the blend would produce 1.25 and reject it.
            assertEquals(if (r == 237) IVs(1, 1, 1) else null, image.detect())
        }
    }

    @Test fun realShinxNativeAndAnalyzed() {
        for (sampling in listOf("native", "analyzed")) {
            val image = shinxFixture(sampling)
            assertEquals(sampling, IVs(4, 12, 15), image.detect())
        }
    }

    private fun shinxFixture(sampling: String): Raster {
        val json = fixture("appraisal-shinx-$sampling.json")
        val image = Raster(json.getInt("width"), json.getInt("height"))
        val runs = json.getJSONArray("runs")
        var offset = 0
        for (i in 0 until runs.length() step 2) {
            val count = runs.getInt(i)
            image.pixels.fill(runs.getInt(i + 1), offset, offset + count)
            offset += count
        }
        assertEquals(image.pixels.size, offset)
        return image
    }

    @Test fun realShinxRejectsDuplicateMissingAndShiftedHP() {
        for (sampling in listOf("native", "analyzed")) {
            for (fault in listOf("duplicate", "missing HP", "shifted HP")) {
                val source = shinxFixture(sampling)
                val image = Raster(source.width, source.height * if (fault == "duplicate") 2 else 1)
                source.pixels.copyInto(image.pixels)
                if (fault == "duplicate") source.pixels.copyInto(image.pixels, source.pixels.size)
                else {
                    // Only the HP bar band, below the label; preserve Attack and Defense.
                    val top = if (sampling == "native") 280 else 180
                    image.rectangle(0, top, image.width, image.height - top, white)
                    if (fault == "shifted HP") {
                        for (y in top until image.height) {
                            source.pixels.copyInto(image.pixels, y * image.width + 10,
                                y * image.width, y * image.width + image.width - 10)
                        }
                    }
                }
                assertNull("$sampling $fault", image.detect())
            }
        }
    }

    @Test fun realS23CropNativeAndResampled() {
        for (sampling in listOf("native", "nearest", "bilinear")) {
            val json = fixture("appraisal-s23-$sampling.json")
            val image = Raster(json.getInt("width"), json.getInt("height"))
            // Lossless ARGB run-length encoding of the PNG's original JPEG artifacts.
            val runs = json.getJSONArray("runs")
            var offset = 0
            for (i in 0 until runs.length() step 2) {
                val count = runs.getInt(i)
                val pixel = runs.getInt(i + 1)
                image.pixels.fill(pixel, offset, offset + count)
                offset += count
            }
            assertEquals(image.pixels.size, offset)
            assertEquals(sampling, IVs(1, 15, 14), image.detect())
        }
    }

    @Test fun fullBarsAtMultipleScales() {
        for (scale in 1..3) {
            val image = raster(scale)
            for (y in listOf(70, 100, 130)) bar(image, 15, y, scale, color = red)
            assertEquals(IVs(15, 15, 15), image.detect())
        }
    }

    @Test fun captureBufferRoundTripPreservesRealAppraisalPixels() {
        val json = fixture("appraisal-s23-native.json")
        val width = json.getInt("width"); val height = json.getInt("height")
        val expected = IntArray(width * height)
        val runs = json.getJSONArray("runs")
        var offset = 0
        for (i in 0 until runs.length() step 2) {
            expected.fill(runs.getInt(i + 1), offset, offset + runs.getInt(i))
            offset += runs.getInt(i)
        }
        val stride = width * 4 + 32
        val buffer = ByteBuffer.allocate((height - 1) * stride + width * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val color = expected[y * width + x]
            val start = y * stride + x * 4
            buffer.put(start, (color ushr 16).toByte()); buffer.put(start + 1, (color ushr 8).toByte())
            buffer.put(start + 2, color.toByte()); buffer.put(start + 3, (color ushr 24).toByte())
        }
        val snapshot = CaptureFramePixels.snapshot(buffer, width, height, 4, stride)
        val result = IntArray(expected.size)
        val row = IntArray(width)
        for (y in 0 until height) { snapshot.readArgbRow(y, row); row.copyInto(result, y * width) }
        assertArrayEquals(expected, result)
        assertEquals(IVs(1, 15, 14), AppraisalBarDetector.detect(result, width, height))
    }

    @Test fun reportedFailingSpreadsRemainDetectableInSyntheticCaptures() {
        // These are bar-pattern cases, not the unavailable live frames from the user's phone.
        for (ivs in listOf(IVs(15, 13, 13), IVs(15, 14, 12))) {
            for (scale in 1..3) {
                val image = raster(scale)
                for ((i, value) in listOf(ivs.attack, ivs.defense, ivs.stamina).withIndex()) {
                    bar(image, value, 70 + i * 30, scale, color = if (value == 15) red else orange)
                }
                assertEquals(ivs, image.detect())
            }
        }
    }

    @Test fun everyEndpointWithRoundedCornersAndZeroTracks() {
        for (iv in 0..15) {
            val image = raster(2)
            for ((i, value) in listOf(iv, 15 - iv, 7).withIndex()) {
                val y = 70 + i * 30
                bar(image, value, y, 2)
                for (edge in listOf(y * 2, y * 2 + 11)) {
                    image.rectangle(70, edge, 2, 1, white)
                    image.rectangle(380, edge, 2, 1, white)
                }
            }
            assertEquals("endpoint $iv", IVs(iv, 15 - iv, 7), image.detect())
        }
        for (ivs in listOf(IVs(0, 7, 13), IVs(1, 0, 15), IVs(5, 10, 0), IVs(14, 2, 9))) {
            val image = raster()
            image.rectangle(15, 10, 80, 18, orange)
            image.rectangle(220, 80, 25, 60, red)
            for ((i, value) in listOf(ivs.attack, ivs.defense, ivs.stamina).withIndex()) bar(image, value, 70 + 30 * i)
            assertEquals(ivs, image.detect())
        }
    }

    @Test fun pixelQuantizedSegmentWidths() {
        for (extra in listOf(0, 2)) {
            val image = raster(2)
            val lengths = listOf(109, 106, 110 + extra)
            for ((row, fills) in listOf(listOf(22, 0, 0), listOf(109, 106, 110), listOf(109, 106, 87)).withIndex()) {
                var x = 35
                for ((segment, length) in lengths.withIndex()) {
                    image.rectangle(x, 140 + row * 60, length, 12, gray)
                    image.rectangle(x, 140 + row * 60, fills[segment], 12, if (row == 1) red else orange)
                    x += length + 6
                }
            }
            assertEquals(if (extra == 0) IVs(1, 15, 14) else null, image.detect())
        }
    }

    @Test fun rejectsEmptyTransparentGrayAndNoise() {
        val image = raster()
        assertNull(image.detect())
        for (y in listOf(70, 100, 130)) bar(image, 0, y)
        assertNull(image.detect())
        image.pixels.fill(0)
        assertNull(image.detect())
        for (y in listOf(70, 100, 130)) bar(image, 15, y, color = red and 0x00ffffff)
        assertNull(image.detect())
        var seed = 12345
        val colors = intArrayOf(orange, red, gray, white)
        for (i in image.pixels.indices) {
            seed = seed * 1664525 + 1013904223
            image.pixels[i] = colors[seed ushr 30]
        }
        assertNull(image.detect())
    }

    @Test fun rejectsGeometryFaults() {
        for (fault in listOf("alignment", "spacing", "width", "missing")) {
            val image = raster()
            bar(image, 8, 70)
            bar(image, 10, 100)
            if (fault != "missing") bar(image, 12, if (fault == "spacing") 145 else 130,
                x = if (fault == "alignment") 45 else 35)
            if (fault == "width") image.rectangle(185, 130, 6, 6, white)
            assertNull(fault, image.detect())
        }
    }

    @Test fun rejectsGaplessNonPrefixAndUncertainFills() {
        val stripes = raster()
        for (y in listOf(70, 100, 130)) stripes.rectangle(35, y, 156, 6, red)
        assertNull(stripes.detect())
        for (fault in listOf("hole", "endpoint", "segment")) {
            val image = raster()
            for (y in listOf(70, 100, 130)) bar(image, 8, y)
            when (fault) {
                "hole" -> image.rectangle(45, 100, 10, 6, gray)
                "endpoint" -> image.rectangle(118, 100, 5, 6, orange)
                "segment" -> image.rectangle(35, 100, 50, 6, gray)
            }
            assertNull(fault, image.detect())
        }
    }

    @Test fun rejectsMultipleTriplesEvenWithIdenticalIVs() {
        val image = raster()
        for (y in listOf(40, 70, 100, 160, 190, 220)) bar(image, 15, y)
        assertNull(image.detect())
    }

    @Test fun bridgesOnlyNarrowOpaqueBlends() {
        for (kind in listOf("blend", "white", "transparent", "wide")) {
            val image = raster(2)
            for ((i, iv) in listOf(1, 15, 14).withIndex()) bar(image, iv, 70 + i * 30, 2)
            val transition = when (kind) {
                "white" -> white
                "transparent" -> 0x00eddcc0
                else -> 0xffeddcc0.toInt()
            }
            image.rectangle(90, 140, if (kind == "wide") 8 else 2, 12, transition)
            assertEquals(kind, if (kind == "blend") IVs(1, 15, 14) else null, image.detect())
        }
    }

    @Test fun requiresAdjacentStableRows() {
        for (isolated in listOf(false, true)) {
            val image = raster()
            for (y in listOf(70, 100, 130)) {
                bar(image, 8, y)
                for (row in if (isolated) listOf(1, 2, 4, 5) else listOf(2, 4)) {
                    image.rectangle(35, y + row, 156, 1, white)
                }
            }
            assertEquals(if (isolated) null else IVs(8, 8, 8), image.detect())
        }
    }

    @Test fun rejectsMalformedDimensionsAndBuffers() {
        for ((w, h) in listOf(0 to 0, -1 to 10, 10 to -1, 10 to 10, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertNull(AppraisalBarDetector.detect(intArrayOf(), w, h))
        }
        assertNull(AppraisalBarDetector.detect(IntArray(99), 10, 10))
        assertNull(AppraisalBarDetector.detect(IntArray(101), 10, 10))
    }

    @Test fun boundsJpegCoreFragmentGrouping() {
        for (fault in listOf("joined", "far", "different IV", "thick")) {
            val image = raster()
            for (y in listOf(70, 100, 130)) {
                bar(image, 8, y)
                image.rectangle(35, y + 2, 156, 4, white)
                val lower = y + if (fault == "far") 8 else 6
                bar(image, if (fault == "different IV") 9 else 8, lower)
                image.rectangle(35, lower + 2, 156, 4, white)
                if (fault == "thick") for (row in y until y + 22) bar(image, 8, row)
            }
            assertEquals(fault, if (fault == "joined") IVs(8, 8, 8) else null, image.detect())
        }
    }

    @Test fun exactSpeciesParsingAndAllBundledForms() {
        val json = fixture("appraisal-text.json")
        val catalog = json.getJSONArray("pokemon")
        val pokemon = List(catalog.length()) { i ->
            val p = catalog.getJSONObject(i)
            Pokemon(p.getString("id"), p.getString("name"), 1, 1, 1)
        }
        val cases = json.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            val candidates = case.getJSONArray("candidates")
            val expected = ScanResult(text, List(candidates.length()) { candidates.getString(it) }, null,
                if (case.isNull("cp")) null else case.getInt("cp"),
                if (case.isNull("hp")) null else case.getInt("hp"))
            assertEquals(text, expected, parseScreenshotText(text, pokemon))
        }
    }

    @Test fun rejectsIntegersOutsideAndroidApiRange() {
        for (number in listOf("2147483648", "999999999999999999999999999999")) {
            val result = parseScreenshotText("CP $number\nHP $number", emptyList())
            assertNull(result.cp)
            assertNull(result.hp)
        }
    }
}
