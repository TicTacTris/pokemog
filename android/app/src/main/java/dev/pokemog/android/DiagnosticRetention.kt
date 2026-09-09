package dev.pokemog.android

import java.io.File
import java.io.OutputStream

internal const val DIAGNOSTIC_MAX_BYTES = 100L * 1024 * 1024
internal const val DIAGNOSTIC_MAX_AGE = 60 * 60 * 1000L

internal fun purgeDiagnostics(
    directory: File, now: Long, protected: Set<File> = emptySet(), deleteAll: Boolean = false,
    maxCount: Int = 3, maxBytes: Long = DIAGNOSTIC_MAX_BYTES,
    remove: (File) -> Boolean = { it.delete() },
): Int {
    val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith("pokemog-diagnostic-") }
        ?.sortedBy { it.lastModified() } ?: return if (directory.exists()) 1 else 0
    var count = files.size
    var bytes = files.sumOf { it.length() }
    var failures = 0
    for (file in files) {
        if (file in protected) { if (deleteAll) failures++; continue }
        if (deleteAll || now - file.lastModified() >= DIAGNOSTIC_MAX_AGE || file.lastModified() > now || count > maxCount || bytes > maxBytes) {
            val size = file.length()
            if (remove(file)) { count--; bytes -= size } else failures++
        }
    }
    return failures
}

internal class LimitedDiagnosticOutput(private val output: OutputStream, private val limit: Long = DIAGNOSTIC_MAX_BYTES) : OutputStream() {
    private var written = 0L
    override fun write(value: Int) { check(written < limit) { "Diagnostic ZIP exceeds size limit" }; output.write(value); written++ }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(length.toLong() <= limit - written) { "Diagnostic ZIP exceeds size limit" }
        output.write(bytes, offset, length); written += length
    }
    override fun flush() = output.flush()
    override fun close() = output.close()
}
