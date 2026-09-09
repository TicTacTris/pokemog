package dev.pokemog.android

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ScreenshotScanner internal constructor(private val recognizer: TextRecognizer) {
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) :
        this(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))

    private val lifecycle = Any()
    private val realScans = Semaphore(1)
    private val direct = Executor { it.run() }
    private var closed = false
    private var activeTasks = 0
    private var warmUpAttempted = false
    private var warmUpTask: Task<Text>? = null

    // Never hold this lock across await or CPU analysis. close cannot race task submission.
    private fun process(
        image: Bitmap,
        completed: AtomicLong,
        completionRecorded: CompletableDeferred<Unit> = CompletableDeferred(),
    ): Task<Text> = synchronized(lifecycle) {
        check(!closed) { "ScreenshotScanner is closed" }
        val task = recognizer.process(InputImage.fromBitmap(image, 0))
        activeTasks++
        task.addOnCompleteListener(direct) {
            completed.compareAndSet(0, System.nanoTime())
            completionRecorded.complete(Unit)
            synchronized(lifecycle) {
                activeTasks--
                if (closed && activeTasks == 0) recognizer.close()
            }
        }
        task
    }

    /** One best-effort blank-image initialization attempt. Cancellation does not cancel ML Kit.
     * Real scans do not await warm-up, but ML Kit may internally queue behind initialization.
     */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.Default) {
        ensureActive()
        try {
            val task = synchronized(lifecycle) {
                if (closed) return@withContext false
                if (!warmUpAttempted) {
                    warmUpAttempted = true
                    val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(android.graphics.Color.WHITE)
                        warmUpTask = process(image, AtomicLong()).also { task ->
                            task.addOnCompleteListener(direct) { image.recycle() }
                        }
                    } catch (error: Exception) {
                        image.recycle()
                        throw error
                    }
                }
                warmUpTask
            }
            if (task == null) false else { task.await(); true }
        } catch (_: CancellationException) {
            ensureActive()
            false
        } catch (_: Exception) {
            ensureActive()
            false
        }
    }

    /** The caller owns its bitmap and this scanner; decoded pixels must already be upright. */
    suspend fun scan(
        bitmap: Bitmap,
        repository: PokemonRepository,
        onAnalyzed: ((ScanResult, Bitmap) -> Unit)? = null,
        onFailure: ((Bitmap, Exception) -> Unit)? = null,
        collectAutoAnchors: Boolean = false,
    ): ScanResult = withContext(Dispatchers.Default) {
        ensureActive()
        val nameIndex = repository.nameIndex
        val started = System.nanoTime()
        // Cancelled awaits leave ML Kit running. Bound owned images, not just scan coroutines.
        realScans.acquire()
        // Own the same opaque sRGB, software-resized image for either input route.
        // The optional callback may clone this image, but must not recycle or modify it.
        val image: Bitmap
        val prepareStarted: Long
        try {
            ensureActive()
            synchronized(lifecycle) { check(!closed) { "ScreenshotScanner is closed" } }
            prepareStarted = System.nanoTime()
            image = ScanImages.prepare(bitmap)
        } catch (error: Throwable) {
            realScans.release()
            throw error
        }
        val prepared = System.nanoTime()
        var task: Task<Text>? = null
        try {
            ensureActive()
            val ocrCompleted = AtomicLong()
            val completionRecorded = CompletableDeferred<Unit>()
            val ocrStarted = System.nanoTime()
            task = process(image, ocrCompleted, completionRecorded)
            val barsStarted = System.nanoTime()
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val detection = AppraisalBarDetector.detectWithBounds(pixels, image.width, image.height)
            val barsCompleted = System.nanoTime()
            ensureActive()
            val recognized = task.await()
            // Task.isComplete can become visible before its completion listeners finish.
            completionRecorded.await()
            ensureActive()
            val parseStarted = System.nanoTime()
            val regions = recognized.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    buildList {
                        line.boundingBox?.let { add(OcrRegion(line.text, it.left, it.top, it.right, it.bottom)) }
                        for (element in line.elements) element.boundingBox?.let {
                            add(OcrRegion(element.text, it.left, it.top, it.right, it.bottom))
                        }
                    }
                }
            }
            val parsedText = parseScreenshotText(recognized.text, repository.pokemon, regions, nameIndex).copy(ivs = detection?.ivs)
            val parsed = parsedText.copy(autoAnchors = if (collectAutoAnchors) AutoScanAnchors.from(
                parsedText, regions, nameIndex, detection, image.width, image.height,
                bitmap.width, bitmap.height, pixels,
            ) else null)
            val parsedAt = System.nanoTime()
            val result = parsed.copy(timings = ScanTimings(
                prepareMs = (prepared - prepareStarted) / 1_000_000,
                barsMs = (barsCompleted - barsStarted) / 1_000_000,
                ocrMs = (ocrCompleted.get() - ocrStarted) / 1_000_000,
                parseMs = (parsedAt - parseStarted) / 1_000_000,
                totalMs = (parsedAt - started) / 1_000_000,
            ))
            onAnalyzed?.invoke(result, image)
            ensureActive()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ensureActive()
            try { onFailure?.invoke(image, error) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (diagnosticError: Exception) { error.addSuppressed(diagnosticError) }
            throw error
        } finally {
            // Release only after BOTH scan cleanup/callbacks and the native task have finished.
            val pending = task
            if (pending == null) {
                try { image.recycle() } finally { realScans.release() }
            } else pending.addOnCompleteListener(direct) {
                try { image.recycle() } finally { realScans.release() }
            }
        }
    }

    fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            if (activeTasks == 0) recognizer.close()
        }
    }

    internal suspend fun awaitIdle() = withContext(kotlinx.coroutines.NonCancellable) {
        realScans.acquire()
        realScans.release()
    }
}
