# OCR Pipeline Performance

## Owner API

- Keep one `ScreenshotScanner(context, repository)` per owning ViewModel/service session.
- Call `suspend fun warmUp(): Boolean` when the app/session opens, not on each scan. It makes one shared best-effort attempt using only a locally generated opaque white 64x64 bitmap. `true` means that task succeeded; `false` means initialization failed or the scanner is closed. Caller cancellation propagates. Concurrent/repeated callers share the same attempt, including its failure; there is no retry loop or visible dummy scan result.
- Keep `scan(bitmap, onAnalyzed?, onFailure?)` unchanged. The caller retains ownership of the source. Callbacks receive the actual owned analyzed image and must not modify/recycle it. Result text, candidate order/forms, numeric parsing, IV detection and failure callback/suppressed-error behavior are unchanged.
- Call `close()` at owner end. It is idempotent, rejects new task submissions and defers recognizer closure until outstanding ML Kit tasks finish. Cancelling a coroutine does not cancel ML Kit: prepared scan images and the warm-up image remain alive until their tasks finish. A scan also retains its image through callbacks.
- Real scans share a coroutine `Semaphore(1)`, acquired before preparing an owned image. The permit is released only after both scan cleanup/callbacks and ML Kit completion, so cancel/rescan cannot accumulate large native inputs. Waiting callers keep their own source bitmaps but allocate no analyzed image. Cancelling a queued caller does not release an unacquired permit; failed preparation releases its acquired permit. After close, queued callers fail before preparation as the gate becomes available. Owners must cancel their scopes at shutdown, especially if a native task never completes. Warm-up remains separate, at most one 64x64 input.
- Real scans do not explicitly wait for warm-up. A small lifecycle lock protects submission/close, never an OCR await or bar analysis. ML Kit may internally queue a real scan behind model initialization. That model/queue wait is included in that scan's `ocrMs` and `totalMs`.

No raw-screen prewarming, network work, image precision reduction, crop restriction, recognition loosening, or inference of missing data was introduced. Main and overlay integration now retain one scanner per owner, warm it when the app/session opens, and close it at owner shutdown. Overlay data initialization runs before the first user scan where possible. The existing 250ms capture hide/settle guard remains unchanged.

## Timings

`ScanResult.timings: ScanTimings? = null` is populated by successful scanner results. Pure `parseScreenshotText` calls still return null timings, preserving existing expected results. Fields are exactly:

```kotlin
data class ScanTimings(
    val prepareMs: Long,
    val barsMs: Long,
    val ocrMs: Long,
    val parseMs: Long,
    val totalMs: Long,
)
```

All durations use monotonic `System.nanoTime()`, truncated to whole milliseconds (zero is valid).

| Field | Interval |
| --- | --- |
| `prepareMs` | Existing full `ScanImages.prepare` operation, excluding gate wait |
| `barsMs` | Pixel allocation/copy and unchanged full-image bar detection |
| `ocrMs` | Immediately before task submission through the direct task-completion listener; includes submission, ML Kit initialization/queueing and inference |
| `parseMs` | OCR line/element region extraction, pure text parsing and IV result assembly |
| `totalMs` | Worker start before acquiring the real-scan gate through parsed result assembly, before `onAnalyzed`; includes gate wait |

OCR starts immediately after preparation, before CPU bar analysis. Bars and OCR overlap and durations are **not additive**. Completion is recorded in an `AtomicLong` by a direct-executor listener, not after CPU work or coroutine resumption. A completion signal covers the race where a task exposes `isComplete` before its listeners finish. Total excludes this scan's callbacks/assessment, capture, initial dispatch to the worker, final dispatch to the caller, and cleanup. Its gate wait can include the preceding scan's callbacks or still-running native task. No `queueMs` field was added: gate wait is included only in `totalMs`, not `prepareMs` or `ocrMs`. Owner construction/catalog-index preparation and separate warm-up work are outside scan timing. Any scan delay caused by ongoing model initialization is included as described above. Owners should measure capture, assessment and end-to-end separately and expose these numbers only in diagnostic metadata/drawer UI.

## Host Verification

Isolated JDK 17 runs on this Linux host, no Gradle invocation:

- 16 JVM tests passed (`ScreenshotTextTest`, `ScreenshotTextPerformanceTest`).
- All 1,062 native text fixtures matched with a fresh and shared catalog index. The fixtures already contain the two intentional historical corrections, `CP\n123` and `HP\n100`; no new expectation changes were made.
- Shared-index tests repeat different texts concurrently with geometry, exact-name boundaries, accents, gender symbols and multiple forms. Readings/geometry state remains local to each call.
- Scanner and eight lifecycle/timing instrumentation tests compiled against cached Android/ML Kit dependencies using Kotlin 2.1.20. Gate regressions cover queued cancellation, preparation deferred until native completion, permit retention through assessment, failed preparation and closed queued callers. Instrumentation was not executed: `adb devices` lists no connected device/emulator.

Pure-parser microbenchmark: 1,681 catalog entries, a fixed Giratina OCR string and four line/element regions, three warm-up batches then seven measured batches of 300 parses, median batch time per parse, 512 MiB JVM heap. The before measurement uses a private pre-existing compiled parser baseline, which also matches all 1,062 current fixtures. The isolated parser harness and baseline are not distributed in this repository; these historical measurements are not directly reproducible from the source tree alone.

| Parser mode | Median ms/parse | Batch range ms/parse |
| --- | ---: | ---: |
| Compiled baseline | 0.798 | 0.791-0.825 |
| Hoisted regexes, fresh catalog index per call | 0.694 | 0.683-0.716 |
| Hoisted regexes, scanner-style reused catalog index | 0.041 | 0.039-0.044 |

These are host/JIT microbenchmark results, not Android OCR, cold-start, camera/capture, assessment, or end-to-end measurements. They do not establish that the reported roughly one-second phone delay is resolved. Device measurements should compare cold initialization, completed warm-up, warm-up overlapping a real scan, repeated scans and cancel/owner-close behavior.

## Integrated Device Measurements

`Menu > Scan details` shows the last completed import or overlay processing run. `ScanPerformance` adds capture/decode, pixel conversion, assessment, result-ready, and (for the overlay) view-layout construction times. The one-record process-local store contains timings only, not images/text, and does not persist or upload data. Result-ready time is not a compositor/frame-present measurement. Stages overlap and scheduler/initialization overhead can appear outside the individual stage intervals. Failure diagnostics include the available scanner/capture/assessment timing fields only when the user explicitly exports them.
