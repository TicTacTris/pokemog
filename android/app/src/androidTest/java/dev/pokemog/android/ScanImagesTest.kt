package dev.pokemog.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScanImagesTest {
    @Test fun importAndCopiedCaptureHaveIdenticalAnalyzedPixels() {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("s23-ultra-appraisal.png").use { BitmapFactory.decodeStream(it) }
        val raw = Bitmap.createBitmap(1440, 2963, Bitmap.Config.ARGB_8888)
        Canvas(raw).apply { drawColor(Color.WHITE); drawBitmap(fixture, 150f, 2160f, null) }
        fixture.recycle()
        val pixels = IntArray(raw.width * raw.height)
        raw.getPixels(pixels, 0, raw.width, 0, 0, raw.width, raw.height)
        val stride = raw.width * 4 + 16
        val bytes = ByteBuffer.allocate((raw.height - 1) * stride + raw.width * 4)
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val color = pixels[y * raw.width + x]; val i = y * stride + x * 4
            bytes.put(i, (color ushr 16).toByte()); bytes.put(i + 1, (color ushr 8).toByte())
            bytes.put(i + 2, color.toByte()); bytes.put(i + 3, (color ushr 24).toByte())
        }
        val snapshot = CaptureFramePixels.snapshot(bytes, raw.width, raw.height, 4, stride)
        val capture = Bitmap.createBitmap(snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(snapshot.width)
        for (y in 0 until snapshot.height) {
            snapshot.readArgbRow(y, row)
            capture.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        val png = ByteArrayOutputStream().use { output -> raw.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        val imported = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png))) { decoder, info, _ ->
            val dimensions = CaptureSizing.nativeSize(info.size.width, info.size.height)
            decoder.setTargetSize(dimensions.width, dimensions.height)
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val fromCapture = ScanImages.prepare(capture)
        val fromImport = ScanImages.prepare(imported)
        try {
            assertEquals(2000, fromCapture.height)
            assertTrue(fromCapture.sameAs(fromImport))
            assertFalse(fromCapture.hasAlpha())
            val analyzedPixels = IntArray(fromCapture.width * fromCapture.height)
            fromCapture.getPixels(analyzedPixels, 0, fromCapture.width, 0, 0, fromCapture.width, fromCapture.height)
            assertEquals(IVs(1, 15, 14), AppraisalBarDetector.detect(analyzedPixels, fromCapture.width, fromCapture.height))
        } finally {
            raw.recycle(); capture.recycle(); imported.recycle(); fromCapture.recycle(); fromImport.recycle()
        }
    }

    @Test fun diagnosticViewerLeaseSurvivesSessionStop() {
        val raw = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.RED)
        val analyzed = ScanImages.prepare(raw)
        val diagnostic = CaptureDiagnostic.snapshot(raw, analyzed, "test only")
        CaptureDiagnostics.set(diagnostic)
        val viewer = checkNotNull(CaptureDiagnostics.acquire())
        CaptureDiagnostics.clear()
        assertFalse(viewer.captured.isRecycled)
        assertEquals(Color.RED, viewer.analyzed.getPixel(0, 0))
        assertTrue(viewer.metadata.contains("captured=100x200"))
        viewer.release()
        assertTrue(diagnostic.captured.isRecycled)
        assertTrue(diagnostic.analyzed.isRecycled)
        raw.recycle(); analyzed.recycle()
    }
}
