package dev.pokemog.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ScreenshotScannerTest {
    private class Worker(val immediate: Boolean = false) {
        data class Request(val image: Bitmap, val completion: TaskCompletionSource<Text>)
        val requests = Channel<Request>(Channel.UNLIMITED)
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val recognizer = Proxy.newProxyInstance(TextRecognizer::class.java.classLoader,
            arrayOf(TextRecognizer::class.java)) { _, method, args ->
            when (method.name) {
                "process" -> {
                    calls.incrementAndGet()
                    val image = checkNotNull((args!![0] as InputImage).bitmapInternal)
                    val completion = TaskCompletionSource<Text>()
                    check(requests.trySend(Request(image, completion)).isSuccess)
                    if (immediate) Tasks.forResult(Text("Giratina CP1820\n120/173 HP", emptyList<Text.TextBlock>()))
                    else completion.task
                }
                "close" -> { closes.incrementAndGet(); null }
                else -> error("Unexpected recognizer call: ${method.name}")
            }
        } as TextRecognizer
    }

    private val repo by lazy { PokemonRepository(InstrumentationRegistry.getInstrumentation().targetContext) }
    private fun scanner(worker: Worker) = ScreenshotScanner(worker.recognizer)

    private fun blank() = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun recognized() = Text("Giratina CP1820\n120/173 HP", emptyList<Text.TextBlock>())

    @Test fun catalogUpdateKeepsWarmedRecognizerAndFreezesInFlightNameIndex() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            val source = blank()
            val cpms = InstrumentationRegistry.getInstrumentation().targetContext.assets.open("cp-multipliers.json")
                .bufferedReader().use { org.json.JSONArray(it.readText()) }.let { array -> List(101) { array.getDouble(it) } }
            val next = PokemonRepository(ValidatedPack(PackManifest(repo.identity.copy(version = repo.identity.version + 1), emptyMap()),
                repo.pokemon.map { if (it.name.startsWith("Giratina")) it.copy(name = "Different catalog name") else it }, cpms, repo.license))
            try {
                val warming = async { scanner.warmUp() }
                worker.requests.receive().completion.setResult(Text("", emptyList<Text.TextBlock>()))
                assertTrue(warming.await())
                val inFlight = async { scanner.scan(source, repo) }
                val oldTask = worker.requests.receive()
                val newer = async { scanner.scan(source, next) }
                oldTask.completion.setResult(recognized())
                assertTrue(inFlight.await().candidates.any { it.startsWith("giratina") })
                worker.requests.receive().completion.setResult(recognized())
                assertTrue(newer.await().candidates.none { it.startsWith("giratina") })
                assertTrue(scanner.warmUp())
                assertEquals(3, worker.calls.get())
                assertEquals(0, worker.closes.get())
            } finally { scanner.close(); source.recycle() }
            assertEquals(1, worker.closes.get())
        }
    }

    @Test fun queuedCancellationDoesNotPrepareOrSubmitUntilCancelledScansNativeTaskCompletes() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            val source = blank()
            val queuedSource = blank()
            val invalid = blank().apply { recycle() }
            try {
                val first = async { scanner.scan(source, repo) }
                val native = worker.requests.receive()
                first.cancelAndJoin()
                repeat(3) {
                    // Same dispatcher as scan, undispatched: runs synchronously up to gate.acquire.
                    val cancelled = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        // Even validation of this invalid source must wait for the permit.
                        scanner.scan(invalid, repo)
                    }
                    assertFalse(cancelled.isCompleted)
                    cancelled.cancelAndJoin()
                    assertEquals(1, worker.calls.get())
                    assertTrue(worker.requests.tryReceive().isFailure)
                    assertFalse(native.image.isRecycled)
                }
                val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    scanner.scan(queuedSource, repo)
                }
                val waitingSince = System.nanoTime()
                assertFalse(queued.isCompleted)
                delay(100)
                assertEquals(1, worker.calls.get())
                // Test-only mutation proves preparation has not copied this source while queued.
                queuedSource.eraseColor(Color.RED)
                val waitMs = (System.nanoTime() - waitingSince) / 1_000_000
                native.completion.setResult(recognized())
                val next = worker.requests.receive()
                assertTrue(native.image.isRecycled)
                assertEquals(Color.RED, next.image.getPixel(0, 0))
                next.completion.setResult(recognized())
                val result = queued.await()
                assertTrue(checkNotNull(result.timings).totalMs >= waitMs)
                assertTrue(next.image.isRecycled)
                assertEquals(2, worker.calls.get())
            } finally { scanner.close(); source.recycle(); queuedSource.recycle() }
        }
    }

    @Test fun nativeCompletionAloneDoesNotReleasePermitDuringAssessment() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker(immediate = true)
            val scanner = scanner(worker)
            val source = blank()
            val queuedSource = blank()
            val entered = Channel<Bitmap>(1)
            val leave = CountDownLatch(1)
            try {
                val first = async { scanner.scan(source, repo, { _, image ->
                    check(entered.trySend(image).isSuccess)
                    check(leave.await(5, TimeUnit.SECONDS))
                }) }
                val image = entered.receive()
                worker.requests.receive()
                val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    scanner.scan(queuedSource, repo, { _, prepared ->
                        assertEquals(Color.BLUE, prepared.getPixel(0, 0))
                    })
                }
                assertFalse(queued.isCompleted)
                assertFalse(image.isRecycled)
                assertEquals(1, worker.calls.get())
                queuedSource.eraseColor(Color.BLUE)
                leave.countDown()
                first.await()
                val next = worker.requests.receive()
                // The image can already be recycled when the immediate second task finishes.
                queued.await()
                assertTrue(image.isRecycled)
                assertTrue(next.image.isRecycled)
                assertEquals(2, worker.calls.get())
            } finally { leave.countDown(); scanner.close(); source.recycle(); queuedSource.recycle() }
        }
    }

    @Test fun failedPrepareReleasesPermitWithoutInvokingPreparedImageCallbacks() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker(immediate = true)
            val scanner = scanner(worker)
            val invalid = blank().apply { recycle() }
            val source = blank()
            var callbacks = 0
            try {
                repeat(2) {
                    try {
                        scanner.scan(invalid, repo, { _, _ -> callbacks++ }, { _, _ -> callbacks++ })
                        fail("Expected preparation failure")
                    } catch (_: IllegalArgumentException) { }
                }
                assertEquals(0, callbacks)
                assertEquals(0, worker.calls.get())
                assertEquals(1820, scanner.scan(source, repo).cp)
                assertEquals(1, worker.calls.get())
            } finally { scanner.close(); source.recycle() }
        }
    }

    @Test fun closeFailsQueuedScansBeforePreparationWithoutLeakingPermits() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            val source = blank()
            val invalid = blank().apply { recycle() }
            try {
                val first = async { scanner.scan(source, repo) }
                val native = worker.requests.receive()
                first.cancelAndJoin()
                val queued = List(3) {
                    async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            scanner.scan(invalid, repo)
                            fail("Expected closed scanner")
                        } catch (error: IllegalStateException) {
                            assertEquals("ScreenshotScanner is closed", error.message)
                        }
                    }
                }
                assertTrue(queued.none { it.isCompleted })
                scanner.close()
                assertEquals(0, worker.closes.get())
                native.completion.setResult(recognized())
                queued.forEach { it.await() }
                assertTrue(native.image.isRecycled)
                assertEquals(1, worker.calls.get())
                assertEquals(1, worker.closes.get())
            } finally { scanner.close(); source.recycle() }
        }
    }

    @Test fun warmUpIsSharedAndDoesNotBlockRealScanOrPublishDummyResults() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            val source = blank()
            try {
                val first = async { scanner.warmUp() }
                val warm = worker.requests.receive()
                assertEquals(64, warm.image.width)
                assertEquals(64, warm.image.height)
                assertEquals(Color.WHITE, warm.image.getPixel(0, 0))
                val second = async { scanner.warmUp() }
                var callbacks = 0
                val scan = async { scanner.scan(source, repo, onAnalyzed = { _, image ->
                    callbacks++
                    assertFalse(image.isRecycled)
                }) }
                val real = worker.requests.receive()
                assertNotSame(source, real.image)
                real.completion.setResult(recognized())
                val result = scan.await()
                assertEquals(1820, result.cp)
                assertEquals(173, result.hp)
                assertEquals(1, callbacks)
                assertFalse(warm.image.isRecycled)
                warm.completion.setResult(Text("", emptyList<Text.TextBlock>()))
                assertTrue(first.await())
                assertTrue(second.await())
                assertTrue(scanner.warmUp())
                assertEquals(2, worker.calls.get())
                assertTrue(warm.image.isRecycled)
                assertTrue(real.image.isRecycled)
                assertFalse(source.isRecycled)
            } finally { scanner.close(); source.recycle() }
            assertEquals(1, worker.closes.get())
        }
    }

    @Test fun cancellationAndCloseRetainBothInputsUntilTheirTasksFinish() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            val source = blank()
            var callbacks = 0
            try {
                val warming = async { scanner.warmUp() }
                val warm = worker.requests.receive()
                val scanning = async { scanner.scan(source, repo, { _, _ -> callbacks++ }, { _, _ -> callbacks++ }) }
                val real = worker.requests.receive()
                warming.cancelAndJoin()
                scanning.cancelAndJoin()
                scanner.close()
                scanner.close()
                assertFalse(scanner.warmUp())
                assertEquals(0, worker.closes.get())
                assertFalse(warm.image.isRecycled)
                assertFalse(real.image.isRecycled)
                warm.completion.setResult(recognized())
                assertTrue(warm.image.isRecycled)
                assertFalse(real.image.isRecycled)
                assertEquals(0, worker.closes.get())
                real.completion.setException(IllegalStateException("late OCR failure"))
                assertTrue(real.image.isRecycled)
                assertEquals(1, worker.closes.get())
                assertEquals(0, callbacks)
                assertFalse(source.isRecycled)
            } finally { scanner.close(); source.recycle() }
        }
    }

    @Test fun failedWarmUpIsBestEffortAndNotRetried() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker()
            val scanner = scanner(worker)
            try {
                val warming = async { scanner.warmUp() }
                val warm = worker.requests.receive()
                warm.completion.setException(IllegalStateException("model unavailable"))
                assertFalse(warming.await())
                assertFalse(scanner.warmUp())
                assertTrue(warm.image.isRecycled)
                assertEquals(1, worker.calls.get())
            } finally { scanner.close() }
        }
    }

    @Test fun immediateCompletionTimingsExcludeAssessmentAndPreserveFailureCallback() = runBlocking {
        withTimeout(10_000) {
            val worker = Worker(immediate = true)
            val scanner = scanner(worker)
            val source = blank()
            try {
                var delivered: ScanResult? = null
                val start = System.nanoTime()
                val result = scanner.scan(source, repo, { result, image ->
                    delivered = result
                    assertFalse(image.isRecycled)
                    Thread.sleep(150)
                })
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertSame(delivered, result)
                val t = checkNotNull(result.timings)
                assertTrue(listOf(t.prepareMs, t.barsMs, t.ocrMs, t.parseMs).all { it in 0..t.totalMs })
                assertTrue("Assessment must not be timed", elapsedMs - t.totalMs >= 140)
                assertTrue(worker.requests.receive().image.isRecycled)
                val assessmentError = IllegalStateException("assessment")
                val diagnosticError = IllegalArgumentException("diagnostic")
                var failureImage: Bitmap? = null
                try {
                    scanner.scan(source, repo, { _, _ -> throw assessmentError }, { image, error ->
                        assertSame(assessmentError, error)
                        assertFalse(image.isRecycled)
                        failureImage = image
                        throw diagnosticError
                    })
                    fail("Expected assessment error")
                } catch (error: IllegalStateException) {
                    assertSame(assessmentError, error)
                    assertArrayEquals(arrayOf(diagnosticError), error.suppressed)
                }
                assertTrue(checkNotNull(failureImage).isRecycled)
                assertFalse(source.isRecycled)
            } finally { scanner.close(); source.recycle() }
        }
    }
}
