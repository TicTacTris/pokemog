package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class ProjectionsTest {
    private val calc = Calculations(javaClass.getResourceAsStream("/cp-multipliers.tsv")!!.bufferedReader().useLines { it.filter(String::isNotBlank).map(String::toDouble).toList() })
    private val all = javaClass.getResourceAsStream("/pokemon.tsv")!!.bufferedReader().useLines { lines ->
        lines.filter(String::isNotBlank).map { line ->
            val f = line.split('\t'); Pokemon(f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt())
        }.toList()
    }
    private val p = all.first { it.id == "bulbasaur" }
    private val evolved = all.first { it.id == "venusaur" }
    private val ivs = IVs(1, 15, 14)

    @Test fun evolutionPreservesIvsAndLevelAndUsesActualIvsForMaximum() {
        val input = AssessmentInput(p, ivs, baseLevel = 20.5, maxBaseLevel = 40)
        val result = Projections.calculate(calc, listOf(p, evolved), input)
        assertEquals(listOf(20.5), result.baseLevels)
        assertEquals(listOf(LevelScenario(false, listOf(20.5))), result.levelScenarios)
        assertEquals(calc.stats(evolved, ivs, 20.5), result.evolutions[1].current.single().second)
        assertEquals(calc.stats(evolved, ivs, 40.0), result.evolutions[1].maximum)
        assertEquals(ivs, result.evolutions[1].optimal!!.ivs)
        assertTrue(result.evolutions.all { it.optimal!!.stats.cp <= 1500 })
    }

    @Test fun summariesDistinguishCurrentAndEvolutionIncludingUnknownLevels() {
        for (level in listOf(null, 20.5)) {
            val result = Projections.calculate(calc, listOf(p, evolved), AssessmentInput(p, ivs, baseLevel = level))
            val suffix = if (level == null) "level unknown" else "effective level 20.5"
            val summary = result.summary(false)
            assertTrue(summary.contains("${p.name}\nCurrent stats: $suffix"))
            assertTrue(summary.contains("${evolved.name}\nAfter evolution, no power-ups: $suffix"))
            assertFalse(summary.contains("evolve now"))
            assertTrue(result.evolutions[1].summary(false, isEvolution = true).startsWith("After evolution, no power-ups:"))
        }
    }

    @Test fun summaryListsActualNonContiguousLevelsAndBuddyAlternatives() {
        val result = Projections.calculate(calc, listOf(p), AssessmentInput(p, ivs, baseLevel = 20.0))
        val levels = listOf(20.0, 21.5)
        val entry = result.evolutions.single().copy(current = levels.map { it to calc.stats(p, ivs, it) })
        val scenarios = listOf(LevelScenario(false, levels), LevelScenario(true, listOf(19.0, 20.5)))
        val summary = result.copy(evolutions = listOf(entry), levelScenarios = scenarios).summary(true)
        assertTrue(summary.contains("Current stats: effective levels 20.0, 21.5"))
        assertFalse(summary.contains("20.0-21.5"))
        for (alternative in levelAlternatives(scenarios)) assertTrue(summary.contains(alternative))
        assertTrue(summary.contains("Without active Buddy boost: base levels 20, 21.5"))
        assertTrue(summary.contains("With active Buddy boost (+1): base levels 19, 20.5"))
        assertTrue(summary.contains("Shadow ATK equivalent"))
    }

    @Test fun shadowChangesDamageEquivalentOnly() {
        val input = AssessmentInput(p, ivs, baseLevel = 20.0)
        val normal = Projections.calculate(calc, listOf(p), input)
        val shadow = Projections.calculate(calc, listOf(p), input.copy(shadow = true))
        assertEquals(normal, shadow)
        assertTrue(shadow.summary(true).contains("Shadow ATK equivalent"))
        assertFalse(normal.summary(false).contains("Shadow ATK equivalent"))
        assertTrue(shadow.summary(true).contains(fmt(shadow.evolutions.first().maximum.attack * 1.2)))
    }

    @Test fun repeatedProjectionUsesSelectedIvsWithoutKeyingRankingOnThem() {
        val input = AssessmentInput(p, ivs, baseLevel = 20.0)
        val ranks = calc.rankIVs(p, 1500, 50.0)
        for (selected in listOf(ivs, IVs(15, 15, 15), IVs(0, 0, 0))) {
            val entry = Projections.calculate(calc, listOf(p), input.copy(ivs = selected)).evolutions.single()
            assertEquals(ranks.single { it.ivs == selected }, entry.optimal)
            assertEquals(calc.stats(p, selected, 20.0), entry.current.single().second)
            assertSame(ranks, calc.rankIVs(p, 1500, 50.0))
        }
        assertEquals(Projections.calculate(calc, listOf(p), input), Projections.calculate(calc, listOf(p), input.copy(shadow = true)))
    }

    @Test fun currentBuddyAndFutureBuddyAreIndependent() {
        val observed = calc.stats(p, ivs, 21.0)
        val input = AssessmentInput(p, ivs, observedCp = observed.cp, observedHp = observed.hp, activeBuddy = true, maxBaseLevel = 40)
        val noFutureBoost = Projections.calculate(calc, listOf(p), input)
        assertTrue(noFutureBoost.baseLevels.contains(20.0))
        assertEquals(listOf(LevelScenario(true, noFutureBoost.baseLevels)), noFutureBoost.levelScenarios)
        assertTrue(noFutureBoost.evolutions.first().current.any { it.first == 21.0 && it.second == observed })
        assertEquals(40.0, noFutureBoost.evolutions.first().maximumLevel, 0.0)
        val futureBoost = Projections.calculate(calc, listOf(p), input.copy(allowBestBuddy = true))
        assertEquals(41.0, futureBoost.evolutions.first().maximumLevel, 0.0)
    }

    @Test fun overCapEvolutionDoesNotClaimReachableLowerLevel() {
        val input = AssessmentInput(p, ivs, baseLevel = 50.0, leagueCap = 500)
        val result = Projections.calculate(calc, listOf(evolved), input).evolutions.single()
        assertNotNull(result.optimal)
        assertTrue(result.current.single().second.cp > 500)
        assertTrue(result.feasibility.contains("Already over the CP limit"))
    }

    @Test fun unknownAndConflictingReadingsNeverInventCurrentLevel() {
        val unknown = Projections.calculate(calc, listOf(p), AssessmentInput(p, ivs))
        assertTrue(unknown.baseLevels.isEmpty())
        assertTrue(unknown.levelScenarios.isEmpty())
        assertTrue(unknown.evolutions.single().current.isEmpty())
        val invalid = Projections.calculate(calc, listOf(p), AssessmentInput(p, ivs, observedCp = 999999, observedHp = 999999))
        assertTrue(invalid.baseLevels.isEmpty())
        assertTrue(invalid.levelScenarios.isEmpty())
        assertTrue(invalid.levelNote.contains("do not match"))
    }

    @Test fun ambiguousLevelsAreRetainedAndExplicitLevelOverridesReadings() {
        val tiny = Pokemon("tiny", "Tiny", 1, 1, 1)
        val ambiguous = Projections.calculate(calc, listOf(tiny), AssessmentInput(tiny, IVs(0, 0, 0), observedCp = 10, observedHp = 10))
        assertTrue(ambiguous.baseLevels.size > 1)
        assertEquals(listOf(LevelScenario(false, ambiguous.baseLevels)), ambiguous.levelScenarios)
        val explicit = Projections.calculate(calc, listOf(tiny), AssessmentInput(tiny, ivs, observedCp = 9999, observedHp = 9999, baseLevel = 30.5))
        assertEquals(listOf(30.5), explicit.baseLevels)
    }

    @Test fun percentileUsesCompetitionRankAndEligiblePopulation() {
        val ranks = calc.rankIVs(p, 1500, 50.0)
        val projection = Projections.calculate(calc, listOf(p), AssessmentInput(p, ivs)).evolutions.single()
        assertEquals(ranks.size, projection.eligibleSpreads)
        for (rank in listOf(1, 2, ranks.size)) {
            val entry = projection.copy(optimal = ranks.first().copy(rank = rank))
            assertEquals(100.0 * (ranks.size - rank) / (ranks.size - 1), entry.ivPercentile!!, 0.0)
        }
        assertEquals(100.0, projection.copy(eligibleSpreads = 1, optimal = ranks.first()).ivPercentile!!, 0.0)
        assertNull(projection.copy(optimal = null, eligibleSpreads = 0).ivPercentile)
        val tied = calc.rankIVs(Pokemon("tiny", "Tiny", 1, 1, 1), 500, 50.0)
        val pair = tied.zipWithNext().first { (a, b) -> a.rank == b.rank }
        assertEquals(projection.copy(optimal = pair.first, eligibleSpreads = tied.size).ivPercentile,
            projection.copy(optimal = pair.second, eligibleSpreads = tied.size).ivPercentile)
    }

    @Test fun percentageFormattingIsSeparateAndUsesUsDecimals() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("98.13", percent(98.126))
            val projection = Projections.calculate(calc, listOf(p), AssessmentInput(p, ivs)).evolutions.single()
            assertTrue(projection.summary(false).contains("${percent(projection.ivPercentile!!)}%"))
            assertTrue(projection.summary(false).contains("${percent(projection.percentBest)}% of"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
