package dev.pokemog.android

import java.io.InputStream
import java.io.OutputStream
import java.io.File

internal const val MAX_IMPORTED_IMAGE_BYTES = 20L * 1024 * 1024

internal object ImportedImageFiles {
    private val active = mutableSetOf<File>()
    @Synchronized fun create(cache: File): File = File.createTempFile("pokemog-import-", ".image", cache).also { active.add(it) }
    @Synchronized fun release(file: File) {
        try { file.delete() } finally { active.remove(file) }
    }
    @Synchronized fun cleanup(cache: File) {
        cache.listFiles()?.filter { it.isFile && it.name.startsWith("pokemog-import-") && it.name.endsWith(".image") && it !in active }
            ?.forEach { runCatching { it.delete() } }
    }
}

internal fun InputStream.copyImportedImage(output: OutputStream, checkActive: () -> Unit) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        checkActive()
        val count = read(buffer)
        checkActive()
        if (count < 0) break
        total += count
        require(total <= MAX_IMPORTED_IMAGE_BYTES) { "Encoded image exceeds 20 MiB." }
        output.write(buffer, 0, count)
    }
}
