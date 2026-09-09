package dev.pokemog.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val PACK_LIMIT = 8 * 1024 * 1024
internal const val MANIFEST_LIMIT = 64 * 1024
internal const val MAX_EVOLUTION_EDGES = 32
internal const val MAX_EVOLUTION_DEPTH = 16 // Root depth is one.
internal const val MAX_EVOLUTION_DESCENDANTS = 32 // Includes the root.
internal val PACK_FILES = setOf("pokemon.json", "cp-multipliers.json", "evolution-metadata.json", "PVPoke-LICENSE.txt")
data class DataIdentity(val version: Long, val sourceCommit: String, val publishedAt: String)
internal data class PackFile(val size: Int, val sha256: String)
internal data class PackManifest(val identity: DataIdentity, val files: Map<String, PackFile>)
internal data class ValidatedPack(val manifest: PackManifest, val pokemon: List<Pokemon>, val cpms: List<Double>, val license: String)

internal fun InputStream.boundedBytes(limit: Int, checkDeadline: () -> Unit = {}): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkDeadline()
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        checkDeadline()
        if (count < 0) break
        require(output.size() + count <= limit) { "Data exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

// Android's JSONTokener accepts comments, coercions, duplicate keys and non-JSON syntax.
// Gate it with the JSON grammar first, including bounded nesting and duplicate-key rejection.
internal fun strictJson(text: String): Any {
    var at = 0
    fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
    fun string(): String {
        val start = at
        require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return JSONArray("[${text.substring(start, at)}]").getString(0)
            require(c >= ' ')
            if (c == '\\') {
                require(at < text.length)
                val escaped = text[at++]
                require(escaped in "\"\\/bfnrtu")
                if (escaped == 'u') repeat(4) { require(at < text.length && text[at++].digitToIntOrNull(16) != null) }
            }
        }
        error("Unterminated JSON string")
    }
    fun value(depth: Int) {
        require(depth <= 32)
        space(); require(at < text.length)
        when (text[at]) {
            '{', '[' -> {
                val objectValue = text[at++] == '{'
                val end = if (objectValue) '}' else ']'
                val keys = mutableSetOf<String>()
                space()
                if (at < text.length && text[at] == end) { at++; return }
                while (true) {
                    space()
                    if (objectValue) { require(keys.add(string())); space(); require(at < text.length && text[at++] == ':') }
                    value(depth + 1); space(); require(at < text.length)
                    val next = text[at++]
                    if (next == end) break
                    require(next == ',')
                }
            }
            '"' -> string()
            't', 'f', 'n' -> {
                val word = when (text[at]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                require(text.startsWith(word, at)); at += word.length
            }
            else -> {
                val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, at)
                require(match != null && match.range.first == at)
                at = match.range.last + 1
            }
        }
    }
    value(0); space(); require(at == text.length)
    return when (text.trimStart().first()) { '{' -> JSONObject(text); '[' -> JSONArray(text); else -> error("Expected container") }
}

internal fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()
internal fun exactInteger(value: Any, maximum: Long = 9007199254740991L): Long {
    // Integers must be JSON integer tokens, not strings, booleans or rounded doubles.
    require(value is Int || value is Long)
    return (value as Number).toLong().also { require(it in 1..maximum) }
}
internal fun strictString(value: Any): String { require(value is String); return value }
internal fun parseManifest(bytes: ByteArray): PackManifest {
    require(bytes.size <= MANIFEST_LIMIT)
    val json = strictJson(utf8(bytes)) as JSONObject
    require(json.keysSet() == setOf("schemaVersion", "calculationContract", "datasetVersion", "sourceCommit", "publishedAt", "files"))
    require(exactInteger(json.get("schemaVersion")) == 1L && exactInteger(json.get("calculationContract")) == 1L)
    val source = strictString(json.get("sourceCommit"))
    require(source.matches(Regex("[0-9a-fA-F]{40}")))
    val date = strictString(json.get("publishedAt"))
    require(date.length <= 40); Instant.parse(date)
    val files = json.getJSONObject("files")
    require(files.keysSet() == PACK_FILES)
    val entries = PACK_FILES.associateWith { name ->
        val entry = files.getJSONObject(name)
        require(entry.keysSet() == setOf("size", "sha256"))
        val hash = strictString(entry.get("sha256"))
        require(hash.matches(Regex("[0-9a-fA-F]{64}")))
        PackFile(exactInteger(entry.get("size"), PACK_LIMIT.toLong()).toInt(), hash.lowercase())
    }
    require(entries.values.sumOf { it.size.toLong() } <= PACK_LIMIT)
    return PackManifest(DataIdentity(exactInteger(json.get("datasetVersion")), source, date), entries)
}

internal fun verifyFile(bytes: ByteArray, expected: PackFile) {
    require(bytes.size == expected.size) { "Data size mismatch" }
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    require(digest == expected.sha256) { "Data hash mismatch" }
}

internal fun validatePack(manifest: PackManifest, files: Map<String, ByteArray>): ValidatedPack {
    require(files.keys == PACK_FILES)
    files.forEach { (name, bytes) -> verifyFile(bytes, manifest.files.getValue(name)) }
    val catalog = strictJson(utf8(files.getValue("pokemon.json"))) as JSONArray
    val report = strictJson(utf8(files.getValue("evolution-metadata.json"))) as JSONObject
    require(report.keysSet() == setOf("commit", "missingFamilyMetadata", "terminalWithFamilyMetadata", "remappedShadowEdges", "omittedShadowEdges", "correctedEdges"))
    require(strictString(report.get("commit")) == manifest.identity.sourceCommit)
    fun id(value: Any): String = strictString(value).also { require(it.matches(Regex("[a-z0-9][a-z0-9_]{0,127}"))) }
    fun ids(array: JSONArray): List<String> = List(array.length()) { id(array.get(it)) }.also { require(it.distinct().size == it.size) }
    val missing = ids(report.getJSONArray("missingFamilyMetadata")).toSet()
    val terminal = ids(report.getJSONArray("terminalWithFamilyMetadata")).toSet()
    require(missing.intersect(terminal).isEmpty())
    require(catalog.length() in 1..20000)
    val pokemon = List(catalog.length()) { i ->
        val entry = catalog.getJSONObject(i)
        val required = setOf("id", "name", "attack", "defense", "stamina", "evolutions")
        require(entry.keysSet().containsAll(required) && (entry.keysSet() - required - setOf("shadowId", "normalId")).isEmpty())
        val name = strictString(entry.get("name"))
        require(name.isNotBlank() && name.length <= 160 && name.none { it < ' ' })
        val key = id(entry.get("id"))
        require(!key.contains("_mega") && !key.contains("_primal"))
        Pokemon(key, name, exactInteger(entry.get("attack"), 10000).toInt(), exactInteger(entry.get("defense"), 10000).toInt(),
            exactInteger(entry.get("stamina"), 10000).toInt(), ids(entry.getJSONArray("evolutions")),
            if (entry.has("shadowId")) id(entry.get("shadowId")) else null,
            if (entry.has("normalId")) id(entry.get("normalId")) else null,
            key !in missing && (entry.getJSONArray("evolutions").length() > 0 || key in terminal))
    }
    val byId = pokemon.associateBy { it.id }
    require(byId.size == pokemon.size && (missing + terminal).all { it in byId })
    val omittedSources = mutableSetOf<String>()
    for (field in listOf("remappedShadowEdges", "omittedShadowEdges", "correctedEdges")) {
        val rows = report.getJSONArray(field)
        val seen = mutableSetOf<List<String>>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            require(row.length() == if (field == "omittedShadowEdges") 2 else 3)
            val values = List(row.length()) { id(row.get(it)) }
            require(seen.add(values) && values[0] in byId)
            if (field == "omittedShadowEdges") {
                require(values[0].endsWith("_shadow") && values[1] !in byId.getValue(values[0]).evolutions)
                if (values[1].endsWith("_shadow")) {
                    require("${values[0]}:${values[1]}" in setOf("scyther_shadow:kleavor_shadow", "girafarig_shadow:farigiraf_shadow", "stantler_shadow:wyrdeer_shadow"))
                    require(values[1] !in byId)
                } else require("${values[1]}_shadow" !in byId)
                omittedSources.add(values[0])
            } else {
                require(values[2] in byId.getValue(values[0]).evolutions)
                if (field == "remappedShadowEdges") require(values[0].endsWith("_shadow") &&
                    !values[1].endsWith("_shadow") && values[2] == "${values[1]}_shadow")
                else require(values == listOf("rockruff", "lycranroc_dusk", "lycanroc_dusk"))
            }
        }
    }
    for (p in pokemon) {
        require(p.evolutions.size <= MAX_EVOLUTION_EDGES)
        require(p.evolutions.all { it in byId && it.endsWith("_shadow") == p.id.endsWith("_shadow") })
        require(p.id !in missing || p.evolutions.isEmpty())
        require(p.id !in terminal || p.evolutions.isEmpty())
        require(p.evolutions.isNotEmpty() || p.id in missing || p.id in terminal || p.id in omittedSources)
        val normal = if (p.id.endsWith("_shadow")) p.id.removeSuffix("_shadow").takeIf { it in byId } else null
        val shadow = if (!p.id.endsWith("_shadow")) "${p.id}_shadow".takeIf { it in byId } else null
        require(p.normalId == normal && p.shadowId == shadow)
        (normal ?: shadow)?.let { counterpart ->
            val other = byId.getValue(counterpart)
            require(p.attack == other.attack && p.defense == other.defense && p.stamina == other.stamina && p.hasFixedHp == other.hasFixedHp)
        }
    }
    // Iterative topological traversal avoids stack overflow for an adversarial deep graph.
    val incoming = pokemon.associate { it.id to 0 }.toMutableMap()
    pokemon.forEach { p -> p.evolutions.forEach { incoming[it] = incoming.getValue(it) + 1 } }
    val pending = ArrayDeque(incoming.filterValues { it == 0 }.keys)
    var visited = 0
    while (pending.isNotEmpty()) {
        val p = byId.getValue(pending.removeFirst()); visited++
        p.evolutions.forEach { incoming[it] = incoming.getValue(it) - 1; if (incoming[it] == 0) pending.add(it) }
    }
    require(visited == pokemon.size) { "Evolution cycle" }
    for (root in pokemon) {
        val depths = mutableMapOf(root.id to 1)
        val queue = ArrayDeque(listOf(root.id))
        while (queue.isNotEmpty()) {
            val source = queue.removeFirst()
            for (target in byId.getValue(source).evolutions) {
                val depth = depths.getValue(source) + 1
                require(depth <= MAX_EVOLUTION_DEPTH) { "Evolution depth limit" }
                if (depth > (depths[target] ?: -1)) {
                    depths[target] = depth
                    require(depths.size <= MAX_EVOLUTION_DESCENDANTS) { "Evolution reachability limit" }
                    queue.add(target)
                }
            }
        }
    }
    for ((source, targets) in mapOf("eevee" to listOf("sylveon", "umbreon"), "cubone" to listOf("marowak", "marowak_alolan"),
        "slowpoke_galarian" to listOf("slowbro_galarian", "slowking_galarian"), "bulbasaur_shadow" to listOf("ivysaur_shadow"))) {
        byId[source]?.let { require(it.evolutions.containsAll(targets)) }
    }
    val multipliers = strictJson(utf8(files.getValue("cp-multipliers.json"))) as JSONArray
    require(multipliers.length() == 101)
    val cpms = List(101) { (multipliers.get(it) as? Number)?.toDouble() ?: error("Non-numeric CPM") }
    require(cpms.withIndex().all { (i, n) -> n.isFinite() && n > 0 && n < 1 && (i == 0 || n > cpms[i - 1]) })
    val license = utf8(files.getValue("PVPoke-LICENSE.txt"))
    require(license.isNotBlank() && license.length <= MANIFEST_LIMIT && license.none { it == '\u0000' })
    return ValidatedPack(manifest, pokemon, cpms, license)
}
