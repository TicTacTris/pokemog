package dev.pokemog.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last completed processing run only: no image, recognized text, or persisted telemetry. */
data class ScanPerformance(
    val source: String,
    val acquisitionMs: Long,
    val conversionMs: Long,
    val assessmentMs: Long,
    val readyMs: Long,
    val scanner: ScanTimings?,
    val renderMs: Long? = null,
) {
    fun describe(): String = buildString {
        appendLine("$source / result ready ${readyMs}ms")
        appendLine("${if (source == "Overlay") "Capture" else "Decode"}: ${acquisitionMs}ms")
        if (source == "Overlay") appendLine("Pixel conversion: ${conversionMs}ms")
        scanner?.let {
            appendLine("Image preparation: ${it.prepareMs}ms")
            appendLine("Bar analysis: ${it.barsMs}ms")
            appendLine("OCR: ${it.ocrMs}ms")
            appendLine("Text parsing: ${it.parseMs}ms")
            appendLine("Scanner total: ${it.totalMs}ms")
        }
        appendLine("Assessment: ${assessmentMs}ms")
        renderMs?.let { append("Overlay layout: ${it}ms") }
    }
}

object RecentScanPerformance {
    private val mutable = MutableStateFlow<ScanPerformance?>(null)
    val state: StateFlow<ScanPerformance?> = mutable.asStateFlow()
    fun record(value: ScanPerformance) { mutable.value = value }
}

fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
