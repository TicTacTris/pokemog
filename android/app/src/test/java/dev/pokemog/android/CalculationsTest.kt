package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class CalculationsTest {
    private fun rows(name: String): List<List<String>> = checkNotNull(javaClass.getResourceAsStream("/$name")) {
        "Missing generated fixture $name; run node scripts/prepare-android-data.mjs"
    }.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { it.split('\t') }.toList() }

    private val cpms = rows("cp-multipliers.tsv").map { it[0].toDouble() }
    private val calculations = Calculations(cpms)
    private val pokemon = rows("pokemon.tsv").associate { row ->
        row[0] to Pokemon(row[0], row[1], row[2].toInt(), row[3].toInt(), row[4].toInt())
    }
    private val bulbasaur = pokemon.getValue("bulbasaur")
    private val perfect = IVs(15, 15, 15)
    private val zero = IVs(0, 0, 0)

    private fun assertStats(row: List<String>, offset: Int, actual: Stats) {
        assertEquals(row[offset].toInt(), actual.cp)
        assertEquals(row[offset + 1].toInt(), actual.hp)
        // JS and JVM may differ by a few ULPs, but displayed stats and ranks must match exactly.
        for ((index, value) in listOf(actual.attack, actual.defense, actual.statProduct).withIndex()) {
            val expected = row[offset + 2 + index].toDouble()
            assertEquals(expected, value, maxOf(1e-12, kotlin.math.abs(expected) * 1e-14))
        }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Kotlin equivalent of the web engine's RangeError.
        }
    }

    @Test fun pinnedDataAndKnownStats() {
        assertTrue(pokemon.size > 1000)
        assertEquals(101, cpms.size)
        assertEquals(0.135137430784308, cpms[1], 0.0)
        assertEquals(0.790300011634826, cpms[78], 0.0)
        assertEquals(0.840300023555755, cpms[98], 0.0)
        assertEquals(0.845300018787384, cpms[100], 0.0)
        pokemon.values.forEach { calculations.stats(it, zero, 1.0) }
        val stats = calculations.stats(bulbasaur, perfect, 40.0)
        assertEquals(1115, stats.cp)
        assertEquals(113, stats.hp)
        assertEquals(133 * cpms[78], stats.attack, 0.0)
        assertEquals(126 * cpms[78], stats.defense, 0.0)
        assertEquals(stats.attack * stats.defense * stats.hp, stats.statProduct, 0.0)
        assertEquals(1275, calculations.stats(bulbasaur, perfect, 51.0).cp)
        assertEquals(10, calculations.stats(pokemon.getValue("tiny"), zero, 1.0).cp)
        assertEquals(10, calculations.stats(pokemon.getValue("tiny"), zero, 1.0).hp)
        val shedinja = pokemon.getValue("shedinja")
        assertEquals(10, calculations.stats(shedinja, perfect, 51.0).hp)
        assertEquals(10, calculations.stats(shedinja.copy(id = "shedinja_shadow"), perfect, 51.0).hp)
    }

    @Test fun typeScriptStatsParity() {
        for (r in rows("stats-parity.tsv")) {
            assertStats(r, 5, calculations.stats(pokemon.getValue(r[0]), IVs(r[1].toInt(), r[2].toInt(), r[3].toInt()), r[4].toDouble()))
        }
    }

    @Test fun typeScriptFullRankingParity() {
        val expected = rows("ranks-parity.tsv").groupBy { it.take(3) }
        for (case in rows("rank-cases.tsv")) {
            val p = pokemon.getValue(case[0])
            val cap = case[1].toInt()
            val maxLevel = case[2].toDouble()
            val ranks = calculations.rankIVs(p, cap, maxLevel)
            assertEquals(case.take(3).toString(), case[3].toInt(), ranks.size)
            assertEquals(ranks.size, ranks.map { it.ivs }.toSet().size)
            val fixtures = expected[case.take(3)].orEmpty()
            assertEquals(fixtures.size, ranks.size)
            ranks.forEachIndexed { index, rank ->
                val r = fixtures[index]
                assertEquals(IVs(r[3].toInt(), r[4].toInt(), r[5].toInt()), rank.ivs)
                assertEquals(r[6].toDouble(), rank.level, 0.0)
                assertStats(r, 7, rank.stats)
                assertEquals(r[12].toInt(), rank.rank)
                assertTrue(rank.stats.cp <= cap)
                if (rank.level < maxLevel) assertTrue(calculations.stats(p, rank.ivs, rank.level + 0.5).cp > cap)
                val tied = index > 0 && rank.stats.statProduct == ranks[index - 1].stats.statProduct
                assertEquals(if (tied) ranks[index - 1].rank else index + 1, rank.rank)
            }
        }
    }

    @Test fun typeScriptInferenceParity() {
        for (r in rows("inference-parity.tsv")) {
            val expected = if (r[7] == "-") emptyList() else r[7].split(',').map { it.toDouble() }
            assertEquals(r.toString(), expected, calculations.inferLevels(
                pokemon.getValue(r[0]), IVs(r[1].toInt(), r[2].toInt(), r[3].toInt()),
                r[4].toInt(), r[5].toInt(), r[6].toBooleanStrict(),
            ))
        }
    }

    @Test fun inferenceReturnsBaseLevelsAndEveryPlateau() {
        val observed = calculations.stats(bulbasaur, perfect, 20.5)
        assertTrue(calculations.inferLevels(bulbasaur, perfect, observed.cp, observed.hp).contains(20.5))
        assertTrue(calculations.inferLevels(bulbasaur, perfect, observed.cp, observed.hp, true).contains(19.5))
        for (base in listOf(1.0, 1.5, 49.5, 50.0)) for (buddy in listOf(false, true)) {
            val s = calculations.stats(bulbasaur, perfect, base + if (buddy) 1 else 0)
            val levels = calculations.inferLevels(bulbasaur, perfect, s.cp, s.hp, buddy)
            assertTrue(levels.contains(base))
            assertTrue(levels.all { it in 1.0..50.0 })
        }
        assertEquals((0..98).map { 1 + it / 2.0 }, calculations.inferLevels(pokemon.getValue("tiny"), zero, 10, 10))
        assertTrue(calculations.inferLevels(bulbasaur, perfect, 99999, 99999).isEmpty())
    }

    @Test fun validatesInputs() {
        for (value in listOf(-1, 16)) {
            rejects { calculations.stats(bulbasaur, zero.copy(attack = value), 1.0) }
            rejects { calculations.inferLevels(bulbasaur, zero.copy(stamina = value), 10, 10) }
        }
        for (level in listOf(0.0, 0.5, 1.25, 51.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            rejects { calculations.stats(bulbasaur, zero, level) }
            rejects { calculations.rankIVs(bulbasaur, 1500, level) }
        }
        for (p in listOf(bulbasaur.copy(attack = 0), bulbasaur.copy(defense = -1), bulbasaur.copy(stamina = 0), bulbasaur.copy(id = " "), bulbasaur.copy(name = ""))) {
            rejects { calculations.stats(p, zero, 1.0) }
            rejects { calculations.rankIVs(p, 1500, 50.0) }
            rejects { calculations.inferLevels(p, zero, 10, 10) }
        }
        for (value in listOf(-1, 9)) {
            rejects { calculations.rankIVs(bulbasaur, value, 50.0) }
            rejects { calculations.inferLevels(bulbasaur, zero, value, 10) }
            rejects { calculations.inferLevels(bulbasaur, zero, 10, value) }
        }
        rejects { Calculations(cpms.dropLast(1)) }
        rejects { Calculations(cpms.toMutableList().also { it[1] = it[0] }) }
        rejects { Calculations(cpms.toMutableList().also { it[0] = Double.NaN }) }
        rejects { Calculations(cpms.toMutableList().also { it[0] = 0.0 }) }
    }

    @Test fun cachedRankingsUseOnlyCalculationInputsAndStillValidate() {
        val original = calculations.rankIVs(bulbasaur, 1500, 50.0)
        assertSame(original, calculations.rankIVs(bulbasaur.copy(name = "Renamed", evolutions = listOf("other"),
            shadowId = "anything", normalId = "anything"), 1500, 50.0))
        for (p in listOf(bulbasaur.copy(id = "other"), bulbasaur.copy(attack = bulbasaur.attack + 1),
            bulbasaur.copy(defense = bulbasaur.defense + 1), bulbasaur.copy(stamina = bulbasaur.stamina + 1),
            bulbasaur.copy(id = "shedinja"), bulbasaur.copy(id = "shedinja_shadow"))) {
            val ranks = calculations.rankIVs(p, 1500, 50.0)
            assertNotSame(original, ranks)
            assertEquals(Calculations(cpms).rankIVs(p, 1500, 50.0), ranks)
            if (p.id.startsWith("shedinja")) assertTrue(ranks.all { it.stats.hp == 10 })
        }
        for ((cap, level) in listOf(500 to 50.0, 2500 to 50.0, 1500 to 40.0, 1500 to 50.5, 1500 to 51.0)) {
            val ranks = calculations.rankIVs(bulbasaur, cap, level)
            assertNotSame(original, ranks)
            assertEquals(Calculations(cpms).rankIVs(bulbasaur, cap, level), ranks)
        }
        rejects { calculations.rankIVs(bulbasaur.copy(name = ""), 1500, 50.0) }
        assertNotSame(original, Calculations(cpms).rankIVs(bulbasaur, 1500, 50.0))
        val changedCpms = cpms.map { it * 0.99 }
        assertNotEquals(original, Calculations(changedCpms).rankIVs(bulbasaur, 1500, 50.0))
    }

    @Test fun cacheIsBoundedAccessOrderedAndCannotBePoisoned() {
        val first = calculations.rankIVs(bulbasaur, 1500, 1.0)
        val second = calculations.rankIVs(bulbasaur, 1501, 1.0)
        for (cap in 1502..1531) calculations.rankIVs(bulbasaur, cap, 1.0)
        assertSame(first, calculations.rankIVs(bulbasaur, 1500, 1.0))
        calculations.rankIVs(bulbasaur, 1532, 1.0)
        assertSame(first, calculations.rankIVs(bulbasaur, 1500, 1.0))
        val reloaded = calculations.rankIVs(bulbasaur, 1501, 1.0)
        assertNotSame(second, reloaded)
        assertEquals(second, reloaded)
        for (list in listOf(first, first.subList(0, 2))) {
            try {
                (list as MutableList<RankedIVs>).clear()
                fail("Cached list must reject mutation")
            } catch (_: UnsupportedOperationException) { }
            try {
                (list as MutableList<RankedIVs>)[0] = first.last()
                fail("Cached list must reject replacement")
            } catch (_: UnsupportedOperationException) { }
        }
        assertEquals(4096, first.size)
        assertEquals(Calculations(cpms).rankIVs(bulbasaur, 1500, 1.0), first)
    }

    @Test fun concurrentColdRequestsPublishOneCompleteReadOnlyRanking() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (0 until 8).map {
                executor.submit<List<RankedIVs>> {
                    start.await()
                    calculations.rankIVs(bulbasaur, 1500, 50.0)
                }
            }
            start.countDown()
            val ranks = futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(4096, ranks.first().size)
            ranks.forEach { assertSame(ranks.first(), it) }
        } finally { executor.shutdownNow() }
    }

    @Test fun lowMemoryCacheIsBoundedWithoutChangingRankings() {
        val lowMemory = Calculations(cpms, 8)
        val first = lowMemory.rankIVs(bulbasaur, 1500, 1.0)
        for (cap in 1501..1508) lowMemory.rankIVs(bulbasaur, cap, 1.0)
        val restored = lowMemory.rankIVs(bulbasaur, 1500, 1.0)
        assertNotSame(first, restored)
        assertEquals(first, restored)
        rejects { Calculations(cpms, 0) }
        rejects { Calculations(cpms, 33) }
    }

    @Test fun cpOnlyProbesExactlyMatchOriginalStatsProbes() {
        // Independent old algorithm: full Stats at every probe, then identical ordering and competition ranks.
        val canonicalCases = listOf("shinx", "luxio", "luxray", "giratina_altered", "eevee", "vaporeon", "jolteon",
            "flareon", "espeon", "umbreon", "leafeon", "glaceon", "sylveon").flatMap { id ->
            listOf(500, 1500, 2500).map { listOf(id, it.toString(), "50.0") }
        }
        for (case in rows("rank-cases.tsv") + canonicalCases + listOf(
            listOf("shedinja", "500", "51.0"), listOf("tiny", "10", "51.0"),
            listOf("bulbasaur", "10", "1.0"), listOf("bulbasaur", "500", "40.5"))) {
            val p = pokemon.getValue(case[0]); val cap = case[1].toInt(); val max = case[2].toDouble()
            val old = ArrayList<RankedIVs>()
            for (a in 0..15) for (d in 0..15) for (s in 0..15) {
                val ivs = IVs(a, d, s)
                var low = 0; var high = ((max - 1) * 2).toInt(); var eligible = -1
                while (low <= high) {
                    val mid = (low + high) / 2
                    if (calculations.stats(p, ivs, 1 + mid / 2.0).cp <= cap) {
                        eligible = mid; low = mid + 1
                    } else high = mid - 1
                }
                if (eligible >= 0) {
                    val level = 1 + eligible / 2.0
                    old.add(RankedIVs(ivs, level, calculations.stats(p, ivs, level), 0))
                }
            }
            old.sortWith(compareByDescending<RankedIVs> { it.stats.statProduct }
                .thenBy { it.ivs.attack }.thenBy { it.ivs.defense }.thenBy { it.ivs.stamina })
            for (i in old.indices) old[i] = old[i].copy(rank =
                if (i > 0 && old[i].stats.statProduct == old[i - 1].stats.statProduct) old[i - 1].rank else i + 1)
            assertEquals(case.toString(), old, calculations.rankIVs(p, cap, max))
        }
        val overflow = bulbasaur.copy(attack = Int.MAX_VALUE, defense = Int.MAX_VALUE, stamina = Int.MAX_VALUE)
        rejects { calculations.rankIVs(overflow, Int.MAX_VALUE, 50.0) }
    }
}
