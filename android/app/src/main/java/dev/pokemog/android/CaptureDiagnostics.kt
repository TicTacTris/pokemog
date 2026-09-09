package dev.pokemog.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import kotlinx.coroutines.*
import androidx.core.content.FileProvider

internal fun installedVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName ?: "unknown"} (${info.longVersionCode})"
}.getOrDefault("unknown")

internal object DiagnosticCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timer: Job? = null
    private val active = mutableSetOf<File>()
    val exportGate = kotlinx.coroutines.sync.Semaphore(1)
    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        scope.launch { purge(app); ImportedImageFiles.cleanup(app.cacheDir) }
        if (timer == null) timer = scope.launch {
            while (isActive) { delay(60_000); purge(app); ImportedImageFiles.cleanup(app.cacheDir) }
        }
    }
    @Synchronized fun begin(context: Context): File {
        check(purge(context, reserve = true) == 0) { "Could not clear old diagnostics" }
        val directory = File(context.cacheDir, "diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        return File.createTempFile("pokemog-diagnostic-", ".zip", directory).also { active.add(it) }
    }
    @Synchronized fun finish(file: File) { active.remove(file) }
    @Synchronized fun remainingBytes(context: Context, output: File): Long = DIAGNOSTIC_MAX_BYTES -
        (File(context.cacheDir, "diagnostics").listFiles()?.filter { it != output && it.isFile }
            ?.sumOf { it.length() } ?: 0L)
    @Synchronized fun purge(context: Context, all: Boolean = false, reserve: Boolean = false): Int = purgeDiagnostics(
        File(context.cacheDir, "diagnostics"), System.currentTimeMillis(), active, all,
        maxCount = if (reserve) 2 else 3, maxBytes = DIAGNOSTIC_MAX_BYTES,
    ) { file ->
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }.getOrDefault(false)
    }
}

/** Owned clones: store, viewer and export each hold a lease so Stop never recycles a displayed image. */
class CaptureDiagnostic private constructor(val captured: Bitmap, val analyzed: Bitmap, val metadata: String) {
    private val references = AtomicInteger(1)

    fun retain(): CaptureDiagnostic {
        while (true) {
            val count = references.get()
            check(count > 0) { "Diagnostic has been released" }
            if (references.compareAndSet(count, count + 1)) return this
        }
    }

    fun release() {
        val count = references.decrementAndGet()
        check(count >= 0) { "Diagnostic released more than once" }
        if (count == 0) { captured.recycle(); analyzed.recycle() }
    }

    companion object {
        fun snapshot(captured: Bitmap, analyzed: Bitmap, metadata: String): CaptureDiagnostic {
            val original = checkNotNull(captured.copy(Bitmap.Config.ARGB_8888, false))
            var processed: Bitmap? = null
            try {
                processed = checkNotNull(analyzed.copy(Bitmap.Config.ARGB_8888, false))
                val info = buildString {
                    appendLine(metadata)
                    appendLine("preprocessing=ScanImages/sRGB/opaque/bilinear/2000px")
                    appendLine("captured=${captured.width}x${captured.height}; analyzed=${analyzed.width}x${analyzed.height}")
                    appendLine("capturedColorSpace=${captured.colorSpace?.name}; analyzedColorSpace=${analyzed.colorSpace?.name}")
                    appendLine("capturedHasAlpha=${captured.hasAlpha()}; analyzedHasAlpha=${analyzed.hasAlpha()}")
                    var minAlpha = 255; var maxAlpha = 0
                    for (y in 0 until captured.height step maxOf(1, captured.height / 32)) {
                        for (x in 0 until captured.width step maxOf(1, captured.width / 32)) {
                            val alpha = captured.getPixel(x, y) ushr 24
                            minAlpha = minOf(minAlpha, alpha); maxAlpha = maxOf(maxAlpha, alpha)
                        }
                    }
                    append("sampledCapturedAlpha=$minAlpha..$maxAlpha")
                }
                return CaptureDiagnostic(original, processed, info)
            } catch (error: Throwable) {
                original.recycle(); processed?.recycle()
                throw error
            }
        }
    }
}

object CaptureDiagnostics {
    private var latest: CaptureDiagnostic? = null

    @Synchronized fun set(diagnostic: CaptureDiagnostic?) {
        if (latest === diagnostic) return
        val previous = latest; latest = diagnostic; previous?.release()
    }
    @Synchronized fun acquire(): CaptureDiagnostic? = latest?.retain()
    fun clear() = set(null)

    fun inspect(context: Context) {
        DiagnosticCache.start(context)
        try {
            context.startActivity(Intent(context, CaptureDiagnosticActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open capture diagnostics. Stop and start PokeMog, then try again.", Toast.LENGTH_LONG).show()
        }
    }
}
