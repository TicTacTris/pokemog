package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class DiagnosticRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 10 * DIAGNOSTIC_MAX_AGE
    private fun file(name: String, age: Long = 0, size: Int = 10): File =
        temporary.newFile("pokemog-diagnostic-$name.zip").apply { writeBytes(ByteArray(size)); setLastModified(now - age) }

    @Test fun expirationWorksWithoutAnotherExportAndPreservesOtherFiles() {
        val old = file("old", DIAGNOSTIC_MAX_AGE)
        val fresh = file("fresh", DIAGNOSTIC_MAX_AGE - 1)
        val unrelated = temporary.newFile("other.zip")
        assertEquals(0, purgeDiagnostics(temporary.root, now))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(unrelated.exists())
    }
    @Test fun countAndByteCapsRemoveOldestAndProtectActiveExports() {
        val old = file("old", 20)
        val middle = file("middle", 10)
        val active = file("active", DIAGNOSTIC_MAX_AGE)
        assertEquals(0, purgeDiagnostics(temporary.root, now, setOf(active), maxCount = 2, maxBytes = 20))
        assertFalse(old.exists()); assertTrue(middle.exists()); assertTrue(active.exists())
        assertEquals(1, purgeDiagnostics(temporary.root, now, setOf(active), deleteAll = true))
        assertFalse(middle.exists()); assertTrue(active.exists())
    }
    @Test fun failedDeletionIsReportedAndRetried() {
        val old = file("old", DIAGNOSTIC_MAX_AGE)
        assertEquals(1, purgeDiagnostics(temporary.root, now, remove = { false }))
        assertTrue(old.exists())
        assertEquals(0, purgeDiagnostics(temporary.root, now))
        assertFalse(old.exists())
    }
    @Test fun zipOutputStopsBeforeCrossingLimit() {
        val bytes = ByteArrayOutputStream()
        val output = LimitedDiagnosticOutput(bytes, 10)
        output.write(ByteArray(10))
        assertThrows(IllegalStateException::class.java) { output.write(1) }
        assertThrows(IllegalStateException::class.java) { output.write(ByteArray(2)) }
        assertEquals(10, bytes.size())
    }
}
