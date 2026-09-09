package dev.pokemog.android

import java.io.File
import java.io.FileOutputStream

internal interface DataPointer {
    fun read(): String
    fun write(value: String)
}

/** Only this store touches snapshot directories; repositories own parsed values, never files. */
internal class DataPackStore(private val root: File, private val pointer: DataPointer) {
    private var active: Long? = null
    private var previous: Long? = null

    fun load(): ValidatedPack? {
        root.mkdirs()
        val versions = runCatching {
            pointer.read().split('\n').filter { it.isNotEmpty() }.also { require(it.size in 1..2) }
                .map { value -> require(value.matches(Regex("[1-9][0-9]{0,15}"))); value.toLong().also { require(it <= 9007199254740991L) } }
        }.getOrDefault(emptyList())
        val valid = versions.distinct().mapNotNull { version ->
            runCatching { readDirectory(File(root, version.toString())).also { require(it.manifest.identity.version == version) } }.getOrNull()
        }.sortedByDescending { it.manifest.identity.version }
        active = valid.firstOrNull()?.manifest?.identity?.version
        previous = valid.getOrNull(1)?.manifest?.identity?.version
        // Repair a corrupt active reference before cleanup, preserving the known-good fallback.
        pointer.write(listOfNotNull(active, previous).joinToString("\n"))
        cleanup()
        return valid.firstOrNull()
    }

    private fun readDirectory(directory: File): ValidatedPack {
        val manifest = parseManifest(File(directory, "manifest.json").inputStream().use { it.boundedBytes(MANIFEST_LIMIT) })
        val bytes = manifest.files.mapValues { (name, entry) -> File(directory, name).inputStream().use { it.boundedBytes(entry.size) } }
        return validatePack(manifest, bytes)
    }

    fun activate(manifestBytes: ByteArray, files: Map<String, ByteArray>, minimumVersion: Long, deadline: Long = Long.MAX_VALUE): ValidatedPack {
        val manifest = parseManifest(manifestBytes)
        require(manifest.identity.version > maxOf(minimumVersion, active ?: 0)) { "Older or unchanged dataset" }
        val validated = validatePack(manifest, files)
        check(System.nanoTime() < deadline) { "Data update deadline exceeded" }
        root.mkdirs()
        cleanup()
        val stage = File(root, "staging")
        stage.deleteRecursively(); check(stage.mkdir())
        try {
            for ((name, bytes) in files + ("manifest.json" to manifestBytes)) {
                FileOutputStream(File(stage, name)).use { it.write(bytes); it.fd.sync() }
            }
            readDirectory(stage)
            check(System.nanoTime() < deadline) { "Data update deadline exceeded" }
            val destination = File(root, manifest.identity.version.toString())
            check(!destination.exists() && stage.renameTo(destination)) { "Snapshot rename failed" }
            pointer.write(listOfNotNull(manifest.identity.version, active).joinToString("\n"))
            previous = active
            active = manifest.identity.version
            cleanup()
            return validated
        } finally { stage.deleteRecursively() }
    }

    private fun cleanup() {
        val keep = listOfNotNull(active, previous).map { it.toString() }.toSet() + setOf("current", "current.bak", "current.new")
        root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }
}
