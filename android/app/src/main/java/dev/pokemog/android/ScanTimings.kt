package dev.pokemog.android

/** Monotonic milliseconds. Bars and OCR overlap; total includes the real-scan gate wait,
 * but excludes this scan's result/assessment callbacks. Prepare measures only image preparation.
 * OCR includes process submission and any ML Kit model/queue wait, not just inference.
 */
data class ScanTimings(
    val prepareMs: Long,
    val barsMs: Long,
    val ocrMs: Long,
    val parseMs: Long,
    val totalMs: Long,
)
