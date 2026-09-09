package dev.pokemog.android

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ImportedImageBytesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun cleanupRemovesAbandonedImportsButNotActiveOrUnrelatedFiles() {
        val abandoned = temporary.newFile("pokemog-import-abandoned.image")
        val unrelated = temporary.newFile("other.image")
        val active = ImportedImageFiles.create(temporary.root)
        try {
            ImportedImageFiles.cleanup(temporary.root)
            assertFalse(abandoned.exists()); assertTrue(active.exists()); assertTrue(unrelated.exists())
        } finally { ImportedImageFiles.release(active) }
        assertFalse(active.exists())
    }
    private class CountingOutput : OutputStream() {
        var size = 0L
        override fun write(value: Int) { size++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { size += length }
    }
    private fun source(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (left == 0L) return -1
            return minOf(left, length.toLong()).toInt().also { left -= it }
        }
    }
    @Test fun acceptsExactLimitAndRejectsOneByteOverWithoutWritingIt() {
        val exact = CountingOutput()
        source(MAX_IMPORTED_IMAGE_BYTES).copyImportedImage(exact) {}
        assertEquals(MAX_IMPORTED_IMAGE_BYTES, exact.size)
        val over = CountingOutput()
        assertThrows(IllegalArgumentException::class.java) { source(MAX_IMPORTED_IMAGE_BYTES + 1).copyImportedImage(over) {} }
        assertEquals(MAX_IMPORTED_IMAGE_BYTES, over.size)
    }
    @Test fun cancellationAfterProviderReadPreventsWritingStaleBytes() {
        var checks = 0
        val output = CountingOutput()
        assertThrows(CancellationException::class.java) {
            source(1024).copyImportedImage(output) { if (++checks == 2) throw CancellationException() }
        }
        assertEquals(0L, output.size)
    }
}
