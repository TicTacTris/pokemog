package dev.pokemog.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DataPackTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun bundled() = PACK_FILES.associateWith { javaClass.classLoader!!.getResourceAsStream(it)!!.use { stream -> stream.readBytes() } }
    private fun manifest(files: Map<String, ByteArray>, version: Long = BUNDLED_DATA.version): ByteArray {
        val entries = JSONObject()
        files.forEach { (name, bytes) -> entries.put(name, JSONObject().put("size", bytes.size).put("sha256",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })) }
        return JSONObject().put("schemaVersion", 1).put("calculationContract", 1).put("datasetVersion", version)
            .put("sourceCommit", BUNDLED_DATA.sourceCommit).put("publishedAt", BUNDLED_DATA.publishedAt).put("files", entries)
            .toString().toByteArray()
    }
    private fun validated(files: Map<String, ByteArray>, version: Long = BUNDLED_DATA.version) = validatePack(parseManifest(manifest(files, version)), files)
    private fun rejected(block: () -> Unit) { assertThrows(Exception::class.java, block) }
    private fun changedCatalog(change: (JSONArray) -> Unit): Map<String, ByteArray> {
        val files = bundled().toMutableMap()
        val catalog = JSONArray(utf8(files.getValue("pokemon.json")))
        change(catalog)
        files["pokemon.json"] = catalog.toString().toByteArray()
        return files
    }
    private fun changedReport(change: (JSONObject) -> Unit): Map<String, ByteArray> {
        val files = bundled().toMutableMap()
        val report = JSONObject(utf8(files.getValue("evolution-metadata.json")))
        change(report)
        files["evolution-metadata.json"] = report.toString().toByteArray()
        return files
    }

    @Test fun bundledPackMeetsReleaseContract() {
        val pack = validated(bundled())
        assertEquals(1681, pack.pokemon.size)
        assertEquals(101, pack.cpms.size)
        assertEquals(347, pack.pokemon.count { !it.hasEvolutionData }) // Includes omitted-only edges, not proven terminals.
        assertTrue(pack.pokemon.single { it.id == "shedinja" }.hasFixedHp)
    }

    @Test fun manifestRejectsUnsupportedContractsAndUnsafeOrCoercedVersions() {
        for ((key, value) in listOf("schemaVersion" to 2, "calculationContract" to 2, "datasetVersion" to 0,
            "datasetVersion" to -1, "datasetVersion" to 9007199254740992L, "datasetVersion" to "123",
            "datasetVersion" to true, "publishedAt" to "yesterday", "sourceCommit" to "not-a-commit")) {
            val json = JSONObject(utf8(manifest(bundled()))).put(key, value)
            rejected { parseManifest(json.toString().toByteArray()) }
        }
        for (value in listOf("1.5", "1.0", "1e3", "9007199254740991.1")) {
            rejected { parseManifest(utf8(manifest(bundled())).replace("\"datasetVersion\":${BUNDLED_DATA.version}", "\"datasetVersion\":$value").toByteArray()) }
        }
    }

    @Test fun downloadedEvolutionKnowledgeMatchesBundledIncludingOmittedOnlySources() {
        val files = bundled()
        val downloaded = validated(files).pokemon
        val local = readPokemonCatalog(utf8(files.getValue("pokemon.json")), utf8(files.getValue("evolution-metadata.json")))
        assertEquals(local.associate { it.id to it.hasEvolutionData }, downloaded.associate { it.id to it.hasEvolutionData })
        val report = JSONObject(utf8(files.getValue("evolution-metadata.json")))
        val omitted = report.getJSONArray("omittedShadowEdges")
        val terminals = report.getJSONArray("terminalWithFamilyMetadata")
        val omittedIds = (0 until omitted.length()).map { omitted.getJSONArray(it).getString(0) }.toSet()
        val adjusted = JSONArray((0 until terminals.length()).map { terminals.getString(it) }.filter { it !in omittedIds })
        report.put("terminalWithFamilyMetadata", adjusted)
        val fixture = files + ("evolution-metadata.json" to report.toString().toByteArray())
        val parsed = validated(fixture).pokemon
        assertEquals(readPokemonCatalog(utf8(files.getValue("pokemon.json")), report.toString()).associate { it.id to it.hasEvolutionData },
            parsed.associate { it.id to it.hasEvolutionData })
        val omittedOnly = parsed.filter { it.id in omittedIds && it.evolutions.isEmpty() }
        assertTrue(omittedOnly.isNotEmpty())
        assertTrue(omittedOnly.none { it.hasEvolutionData })
    }

    @Test fun evolutionResourceContractRejectsDeepAndWideGraphs() {
        fun graph(size: Int, wide: Boolean): Map<String, ByteArray> {
            val files = bundled().toMutableMap()
            val catalog = JSONArray()
            val terminals = JSONArray()
            repeat(size) { i ->
                val edges = if (wide && i == 0) (1 until size).map { "node_$it" }
                    else if (!wide && i + 1 < size) listOf("node_${i + 1}") else emptyList()
                if (edges.isEmpty()) terminals.put("node_$i")
                catalog.put(JSONObject().put("id", "node_$i").put("name", "Node $i").put("attack", 100)
                    .put("defense", 100).put("stamina", 100).put("evolutions", JSONArray(edges)))
            }
            files["pokemon.json"] = catalog.toString().toByteArray()
            files["evolution-metadata.json"] = JSONObject().put("commit", BUNDLED_DATA.sourceCommit)
                .put("missingFamilyMetadata", JSONArray()).put("terminalWithFamilyMetadata", terminals)
                .put("remappedShadowEdges", JSONArray()).put("omittedShadowEdges", JSONArray()).put("correctedEdges", JSONArray()).toString().toByteArray()
            return files
        }
        validated(graph(16, false)) // Root depth is one; fifteen edges are supported.
        rejected { validated(graph(17, false)) }
        validated(graph(32, true))
        rejected { validated(graph(33, true)) }
        rejected { validated(graph(34, true)) }
        rejected { validated(graph(9000, false)) }
    }

    private fun contractPack(catalog: JSONArray): Map<String, ByteArray> {
        val terminal = JSONArray()
        for (i in 0 until catalog.length()) {
            val row = catalog.getJSONObject(i)
            if (row.getJSONArray("evolutions").length() == 0) terminal.put(row.get("id"))
        }
        val report = JSONObject().put("commit", BUNDLED_DATA.sourceCommit)
            .put("missingFamilyMetadata", JSONArray()).put("terminalWithFamilyMetadata", terminal)
            .put("remappedShadowEdges", JSONArray()).put("omittedShadowEdges", JSONArray()).put("correctedEdges", JSONArray())
        return bundled() + mapOf("pokemon.json" to catalog.toString().toByteArray(),
            "evolution-metadata.json" to report.toString().toByteArray())
    }

    private fun contractRow(id: String) = JSONObject().put("id", id).put("name", "Test")
        .put("attack", 100).put("defense", 100).put("stamina", 100).put("evolutions", JSONArray())

    // Literal export of pokemog-data/test/fixtures/contract-fixtures.json (2026-09-09).
    // Keep the copy and case expansion aligned with test/contract.test.mjs; no sibling checkout is required.
    @Test fun publisherContractFixturesAgreeWithAndroid() {
        val fixtures = JSONObject(javaClass.classLoader!!.getResourceAsStream("contract-fixtures.json")!!.use { utf8(it.readBytes()) })
        val limits = fixtures.getJSONObject("graphLimits")
        assertEquals(MAX_EVOLUTION_DEPTH, limits.getInt("maxDepth"))
        assertEquals(1, limits.getInt("rootDepth"))
        assertEquals(MAX_EVOLUTION_EDGES, limits.getInt("maxOutdegree"))
        assertEquals(MAX_EVOLUTION_DESCENDANTS, limits.getInt("maxReachableIncludingRoot"))
        val cases = fixtures.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val fixture = cases.getJSONObject(i)
            var rows = List(fixture.optInt("count", 1)) { contractRow("p$it") }
            when (fixture.optString("graph")) {
                "chain" -> rows.dropLast(1).forEachIndexed { index, row -> row.put("evolutions", JSONArray().put("p${index + 1}")) }
                "star" -> rows[0].put("evolutions", JSONArray(rows.drop(1).map { it.getString("id") }))
            }
            if (fixture.has("field")) rows[0].put(fixture.getString("field"), fixture.get("value"))
            if (fixture.has("mutation")) {
                rows = listOf(contractRow("a").put("shadowId", "a_shadow"), contractRow("a_shadow").put("normalId", "a"))
                when (fixture.getString("mutation")) {
                    "counterpartStat" -> rows[1].put("attack", 101)
                    "counterpartLink" -> rows[1].remove("normalId")
                    "category" -> rows[0].put("evolutions", JSONArray().put("a_shadow"))
                    else -> fail("Unknown fixture mutation")
                }
            }
            val files = contractPack(JSONArray(rows))
            if (fixture.getBoolean("accepted")) {
                assertEquals(fixture.getString("name"), rows.size, validated(files).pokemon.size)
            } else assertThrows(fixture.getString("name"), Exception::class.java) { validated(files) }
        }
    }

    @Test fun publisherIdAndNameLengthBoundaries() {
        for ((field, limit) in listOf("id" to 128, "name" to 160)) {
            val row = contractRow("a").put(field, "a".repeat(limit))
            assertEquals(1, validated(contractPack(JSONArray().put(row))).pokemon.size)
            row.put(field, "a".repeat(limit + 1))
            rejected { validated(contractPack(JSONArray().put(row))) }
        }
    }

    @Test fun expiredNetworkDeadlineFailsBeforeOpeningConnection() {
        assertThrows(java.net.SocketTimeoutException::class.java) { DataPackNetwork.get(DATA_API, 1024, System.nanoTime() - 1) }
    }

    @Test fun networkDeadlineIsCheckedAfterReadIncludingEndOfStream() {
        for (count in listOf(-1, 1)) {
            var expired = false
            val input = object : java.io.InputStream() {
                override fun read(): Int = error("Expected bulk read")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    expired = true
                    return count
                }
            }
            assertThrows(java.net.SocketTimeoutException::class.java) {
                input.boundedBytes(1) { if (expired) throw java.net.SocketTimeoutException() }
            }
        }
    }

    @Test fun publisherMetadataRowsAndOmittedOnlyKnowledge() {
        val files = contractPack(JSONArray().put(contractRow("a_shadow"))).toMutableMap()
        val original = JSONObject(utf8(files.getValue("evolution-metadata.json")))
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("extra", JSONArray()) },
            { it.put("terminalWithFamilyMetadata", JSONArray()) },
            { it.put("missingFamilyMetadata", JSONArray().put("a_shadow")) },
            { it.getJSONArray("terminalWithFamilyMetadata").put("a_shadow") },
            { it.put("correctedEdges", JSONArray().put(JSONArray(listOf("a_shadow", "b", "c")))) },
            { it.put("remappedShadowEdges", JSONArray().put(true)) },
            { it.put("omittedShadowEdges", JSONArray().put(JSONArray(listOf("a_shadow", "b_shadow")))) },
        )) {
            files["evolution-metadata.json"] = JSONObject(original.toString()).also(change).toString().toByteArray()
            rejected { validated(files) }
        }
        original.put("terminalWithFamilyMetadata", JSONArray())
            .put("omittedShadowEdges", JSONArray().put(JSONArray(listOf("a_shadow", "b"))))
        files["evolution-metadata.json"] = original.toString().toByteArray()
        assertFalse(validated(files).pokemon.single().hasEvolutionData)
        original.getJSONArray("omittedShadowEdges").put(JSONArray(listOf("a_shadow", "b")))
        files["evolution-metadata.json"] = original.toString().toByteArray()
        rejected { validated(files) }
    }

    @Test fun sharedDagDescendantsCountOnceAndLongestPathControlsDepth() {
        val rows = List(17) { contractRow("p$it") }
        rows.dropLast(1).forEachIndexed { i, row -> row.put("evolutions", JSONArray().put("p${i + 1}")) }
        rows[0].put("evolutions", JSONArray(listOf("p1", "p16")))
        rejected { validated(contractPack(JSONArray(rows))) }
        rows[15].put("evolutions", JSONArray())
        assertEquals(17, validated(contractPack(JSONArray(rows))).pokemon.size)
        val diamond = listOf(contractRow("a").put("evolutions", JSONArray(listOf("b", "c"))),
            contractRow("b").put("evolutions", JSONArray().put("d")),
            contractRow("c").put("evolutions", JSONArray().put("d")), contractRow("d"))
        assertEquals(4, validated(contractPack(JSONArray(diamond))).pokemon.size)
    }

    @Test fun manifestPinsFilesHashesSizesAndTotalBudget() {
        val baseline = utf8(manifest(bundled()))
        for (modify in listOf<(JSONObject) -> Unit>(
            { it.put("url", "https://example.com") },
            { it.getJSONObject("files").put("../pokemon.json", JSONObject()) },
            { it.getJSONObject("files").remove("PVPoke-LICENSE.txt") },
            { it.getJSONObject("files").getJSONObject("pokemon.json").put("size", 1.5) },
            { it.getJSONObject("files").getJSONObject("pokemon.json").put("size", PACK_LIMIT) },
            { it.getJSONObject("files").getJSONObject("pokemon.json").put("sha256", "bad") },
        )) rejected { parseManifest(JSONObject(baseline).also(modify).toString().toByteArray()) }
        rejected { parseManifest(ByteArray(MANIFEST_LIMIT + 1)) }
    }

    @Test fun jsonGrammarRejectsAndroidLeniencyAndDuplicateKeys() {
        for (text in listOf("{'a':1}", "{a:1}", "{\"a\":1,}", "[1,]", "[01]", "[NaN]", "[Infinity]", "[+1]",
            "/*comment*/{}", "{} garbage", "{\"a\":1,\"\\u0061\":2}", "[\"bad\nstring\"]", "[\"\\x01\"]", "[1;2]", "[.5]")) {
            rejected { strictJson(text) }
        }
        rejected { strictJson("[".repeat(34) + "0" + "]".repeat(34)) }
        strictJson(" {\"value\": [true, false, null, -1.5e+2, \"escaped \\\" name\"]} \n")
        rejected { utf8(byteArrayOf(0xc3.toByte(), 0x28)) }
    }

    @Test fun streamingCountsActualBytesIncludingOnePastLimit() {
        assertArrayEquals(ByteArray(8192), ByteArray(8192).inputStream().use { it.boundedBytes(8192) })
        rejected { ByteArray(8193).inputStream().use { it.boundedBytes(8192) } }
        rejected { ByteArray(1).inputStream().use { it.boundedBytes(0) } }
    }

    @Test fun hashAndSizeFailuresRejectBeforeParsing() {
        val files = bundled()
        val packManifest = parseManifest(manifest(files))
        val modified = files.getValue("pokemon.json").copyOf().also { it[0] = ' '.code.toByte() }
        rejected { validatePack(packManifest, files + ("pokemon.json" to modified)) }
        rejected { validatePack(packManifest, files + ("pokemon.json" to modified.copyOf(modified.size - 1))) }
    }

    @Test fun catalogRejectsInvalidTypesShapeNamesIdsAndStats() {
        for ((key, value) in listOf("attack" to 118.5, "attack" to "118", "attack" to 0, "attack" to 10001,
            "name" to "", "name" to "x".repeat(161), "name" to true, "id" to "../escape", "id" to "bulbasaur_mega",
            "specialHp" to 1, "shadowId" to JSONObject.NULL, "evolutions" to "ivysaur")) {
            rejected { validated(changedCatalog { it.getJSONObject(0).put(key, value) }) }
        }
        rejected { validated(changedCatalog { it.put(it.getJSONObject(0)) }) }
    }

    @Test fun graphRejectsMissingReferencesCyclesAndCounterpartCorruption() {
        rejected { validated(changedCatalog { it.getJSONObject(0).put("evolutions", JSONArray().put("absent")) }) }
        rejected { validated(changedCatalog { it.getJSONObject(0).put("evolutions", JSONArray().put("bulbasaur")) }) }
        rejected { validated(changedCatalog { it.getJSONObject(0).put("evolutions", JSONArray().put("ivysaur_shadow")) }) }
        rejected { validated(changedCatalog { it.getJSONObject(0).put("shadowId", "ivysaur_shadow") }) }
        rejected { validated(changedCatalog { it.getJSONObject(1).put("attack", 119) }) }
        rejected { validated(changedCatalog { array ->
            for (i in 0 until array.length()) if (array.getJSONObject(i).getString("id") == "eevee")
                array.getJSONObject(i).put("evolutions", JSONArray().put("sylveon"))
        }) }
    }

    @Test fun metadataMustPartitionKnownIdsAndAgreeWithGraphAndSource() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("commit", "a".repeat(40)) },
            { it.getJSONArray("missingFamilyMetadata").put("absent") },
            { it.getJSONArray("missingFamilyMetadata").put("bulbasaur") },
            { it.getJSONArray("terminalWithFamilyMetadata").put("bulbasaur") },
            { it.getJSONArray("missingFamilyMetadata").put(it.getJSONArray("terminalWithFamilyMetadata").getString(0)) },
            { it.getJSONArray("missingFamilyMetadata").put(it.getJSONArray("missingFamilyMetadata").getString(0)) },
            { it.put("terminalWithFamilyMetadata", JSONArray()) },
            { it.put("correctedEdges", JSONArray().put(JSONArray().put("rockruff"))) },
        )) rejected { validated(changedReport(change)) }
    }

    @Test fun multipliersMustBeExactly101FiniteIncreasingNumbersInsideUnitInterval() {
        val files = bundled()
        for (change in listOf<(JSONArray) -> Unit>(
            { it.remove(100) }, { it.put(0, "0.1") }, { it.put(0, true) }, { it.put(0, 0) }, { it.put(100, 1) },
            { it.put(1, it.get(0)) }, { it.put(1, 0.01) },
        )) {
            val array = JSONArray(utf8(files.getValue("cp-multipliers.json"))).also(change)
            rejected { validated(files + ("cp-multipliers.json" to array.toString().toByteArray())) }
        }
    }

    private class Pointer : DataPointer {
        var value = ""
        var failWrite = false
        override fun read() = value
        override fun write(value: String) { check(!failWrite); this.value = value }
    }

    @Test fun corruptActiveCacheFallsBackToRevalidatedPreviousAndCleansStaging() {
        val root = temporary.newFolder()
        val pointer = Pointer()
        val files = bundled()
        val store = DataPackStore(root, pointer)
        assertNull(store.load())
        store.activate(manifest(files, 1), files, 0)
        store.activate(manifest(files, 2), files, 1)
        File(root, "2/pokemon.json").appendText(" ")
        File(root, "staging").mkdir()
        File(root, "999").mkdir()
        assertEquals(1L, DataPackStore(root, pointer).load()!!.manifest.identity.version)
        assertEquals("1", pointer.value)
        assertEquals(setOf("1"), root.list()!!.toSet())
        File(root, "1/pokemon.json").writeBytes(ByteArray(10))
        assertNull(DataPackStore(root, pointer).load())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun cacheRechecksHashEvenWhenSizeIsUnchanged() {
        val root = temporary.newFolder()
        val pointer = Pointer()
        val files = bundled()
        DataPackStore(root, pointer).activate(manifest(files, 1), files, 0)
        val path = File(root, "1/pokemon.json")
        path.writeBytes(path.readBytes().also { it[0] = ' '.code.toByte() })
        assertNull(DataPackStore(root, pointer).load())
    }

    @Test fun activationRetainsOnlyTwoVersionsAndParsedRepositoriesSurviveCleanup() {
        val root = temporary.newFolder()
        val pointer = Pointer()
        val store = DataPackStore(root, pointer)
        val files = bundled()
        val old = PokemonRepository(store.activate(manifest(files, 1), files, 0))
        store.activate(manifest(files, 2), files, 1)
        store.activate(manifest(files, 3), files, 2)
        assertEquals(setOf("2", "3"), root.list()!!.toSet())
        assertEquals(1L, old.identity.version)
        assertEquals(3, old.descendants(old.pokemon.first()).size)
        rejected { store.activate(manifest(files, 2), files, 3) }
        rejected { store.activate(manifest(files, 3), files, 3) }
        assertEquals("3\n2", pointer.value)
    }

    @Test fun pointerFailureLeavesPreviousActiveAndOrphanIsNeverLoaded() {
        val root = temporary.newFolder()
        val pointer = Pointer()
        val files = bundled()
        val store = DataPackStore(root, pointer)
        store.activate(manifest(files, 1), files, 0)
        pointer.failWrite = true
        rejected { store.activate(manifest(files, 2), files, 1) }
        assertEquals("1", pointer.value)
        pointer.failWrite = false
        val restarted = DataPackStore(root, pointer)
        assertEquals(1L, restarted.load()!!.manifest.identity.version)
        restarted.activate(manifest(files, 2), files, 1)
        assertEquals("2\n1", pointer.value)
    }

    @Test fun corruptOrTraversalPointerFallsBackWithoutReadingOutsideRoot() {
        val root = temporary.newFolder()
        val pointer = Pointer()
        for (value in listOf("../outside", "1\n2\n3", "0", "9007199254740992", "1/../../")) {
            pointer.value = value
            assertNull(DataPackStore(root, pointer).load())
        }
    }

    @Test fun urlPolicyPinsExactRepoPathsAndOnlyHttpsReleaseCdnRedirects() {
        val original = "https://github.com/TicTacTris/pokemog-data/releases/download/data-1/pokemon.json"
        assertTrue(releaseAssetUrl(original, "pokemon.json", "data-1"))
        for (url in listOf(original.replace("https:", "http:"), original.replace("TicTacTris", "someone"),
            original.replace("github.com", "github.com.evil.test"), "$original?extra=1", "$original#fragment",
            original.replace("github.com", "user@github.com"), original.replace("github.com", "github.com:443"),
            original.replace("pokemon.json", "%70okemon.json"), original.replace("/download/", "/blob/"))) {
            assertFalse(url, releaseAssetUrl(url, "pokemon.json", "data-1"))
        }
        for (url in listOf("https://release-assets.githubusercontent.com/path?signature=abc", "https://objects.githubusercontent.com/path",
            "https://github-production-release-asset-2e65be.s3.amazonaws.com/path")) assertTrue(trustedRedirect(url, original))
        for (url in listOf("http://release-assets.githubusercontent.com/path", "https://evil.test/path", "https://raw.githubusercontent.com/other/repo/main/x",
            "https://release-assets.githubusercontent.com.evil.test/path", "https://github.com/other/repo/x", "https://user@release-assets.githubusercontent.com/path"))
            assertFalse(url, trustedRedirect(url, original))
    }

    @Test fun releaseApiMustListAllFiveUniqueTrustedAssets() {
        fun release() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "data-1")
            .put("assets", JSONArray((PACK_FILES + "manifest.json").map { name -> JSONObject().put("name", name)
                .put("browser_download_url", "https://github.com/TicTacTris/pokemog-data/releases/download/data-1/$name") }))
        assertEquals(5, DataPackNetwork.releaseAssets(release().toString().toByteArray()).size)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("draft", "false") }, { it.put("prerelease", true) },
            { it.getJSONArray("assets").remove(0) },
            { it.getJSONArray("assets").put(1, it.getJSONArray("assets").getJSONObject(0)) },
            { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/pokemon.json") },
        )) rejected { DataPackNetwork.releaseAssets(release().also(change).toString().toByteArray()) }
    }

    @Test fun persistentCooldownUsesSixHourSuccessAndFifteenMinuteFailure() {
        val now = 100_000_000L
        assertTrue(dataCheckDue(now, 0, 0))
        assertFalse(dataCheckDue(now, now - 6 * 60 * 60 * 1000L + 1, 0))
        assertTrue(dataCheckDue(now, now - 6 * 60 * 60 * 1000L, 0))
        assertFalse(dataCheckDue(now, 0, now - 15 * 60 * 1000L + 1))
        assertTrue(dataCheckDue(now, 0, now - 15 * 60 * 1000L))
        assertFalse(dataCheckDue(now, now + 100, 0))
    }

    @Test fun clockCorrectionClearsFarFutureTimestampsButPreservesOtherCooldowns() {
        val now = 100_000_000L
        val yearAhead = now + 365 * 24 * 60 * 60 * 1000L
        assertEquals(0L, normalizedDataCheckTimestamp(now, yearAhead))
        assertEquals(0L, normalizedDataCheckTimestamp(now, Long.MAX_VALUE))
        assertTrue(dataCheckDue(now, yearAhead, yearAhead))
        assertTrue(dataCheckDue(now, yearAhead, 0))
        assertTrue(dataCheckDue(now, 0, yearAhead))
        assertFalse(dataCheckDue(now, yearAhead, now - 1000))
        assertFalse(dataCheckDue(now, now - 1000, yearAhead))
        val corrected = normalizedDataCheckTimestamp(now, yearAhead)
        assertTrue(dataCheckDue(now + 1000, corrected, corrected))
    }

    @Test fun smallFutureSkewHasFiniteGraceAndNormalCooldownExpiry() {
        val now = 100_000_000L
        val tolerance = 5 * 60 * 1000L
        val future = now + tolerance
        assertEquals(future, normalizedDataCheckTimestamp(now, future))
        assertEquals(0L, normalizedDataCheckTimestamp(now, future + 1))
        assertFalse(dataCheckDue(now, future, 0))
        assertFalse(dataCheckDue(now, 0, future))
        assertFalse(dataCheckDue(future + 6 * 60 * 60 * 1000L - 1, future, 0))
        assertTrue(dataCheckDue(future + 6 * 60 * 60 * 1000L, future, 0))
        assertFalse(dataCheckDue(future + 15 * 60 * 1000L - 1, 0, future))
        assertTrue(dataCheckDue(future + 15 * 60 * 1000L, 0, future))
    }

    @Test fun frozenRepositoryKeepsIndexCalculatorAndShadowOnItsVersionDuringPublication() {
        val old = PokemonRepository(validated(bundled(), 1))
        val next = PokemonRepository(validated(changedCatalog { it.getJSONObject(0).put("name", "New Bulbasaur") }, 2))
        val active = AtomicReference(old)
        val captured = CountDownLatch(1)
        val published = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val operation = executor.submit<Long> {
                val snapshot = active.get()
                captured.countDown()
                check(published.await(5, TimeUnit.SECONDS))
                assertEquals("Bulbasaur", snapshot.pokemon.first().name)
                assertSame(old.nameIndex, snapshot.nameIndex)
                assertSame(old.calculations, snapshot.calculations)
                val scan = ScanResult("", listOf("bulbasaur"), IVs(1, 2, 3), null, null)
                assertTrue(ScanAssessments.calculate(snapshot, scan, true).shadow)
                snapshot.identity.version
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            active.set(next); published.countDown()
            assertEquals(1L, operation.get(10, TimeUnit.SECONDS))
            assertEquals(2L, active.get().identity.version)
            assertNotSame(old.nameIndex, next.nameIndex)
            rejected { (old.pokemon as MutableList).clear() }
            rejected { (old.pokemon.first().evolutions as MutableList).clear() }
        } finally { published.countDown(); executor.shutdownNow() }
    }

    @Test fun liveReleaseSmokeUsesProductionDownloaderAndValidator() {
        assumeTrue("Opt-in public GET smoke test", System.getenv("POKEMOG_DATA_SMOKE") == "1")
        val assets = DataPackNetwork.releaseAssets(DataPackNetwork.get(DATA_API, 1024 * 1024))
        val manifest = parseManifest(DataPackNetwork.get(assets.getValue("manifest.json"), MANIFEST_LIMIT))
        val files = manifest.files.mapValues { (name, file) -> DataPackNetwork.get(assets.getValue(name), file.size) }
        val pack = validatePack(manifest, files)
        assertTrue(pack.manifest.identity.version >= BUNDLED_DATA.version)
        assertTrue(assets.getValue("manifest.json").contains("/data-${manifest.identity.version}/"))
        assertTrue(pack.pokemon.size >= 1000)
        println("LIVE MANIFEST VALIDATED: ${manifest.identity}; ${pack.pokemon.size} Pokemon; ${pack.cpms.size} CPMs; ${files.values.sumOf { it.size }} bytes; all four SHA-256 hashes verified")
    }
}
