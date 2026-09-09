package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class ResultLabelsTest {
    @Test fun alternativesListExactSortedValuesAndOnlyPresentBranches() {
        assertEquals(listOf(
            "Without active Buddy boost: base level 15",
            "With active Buddy boost (+1): base level 14",
        ), levelAlternatives(listOf(LevelScenario(true, listOf(14.0)), LevelScenario(false, listOf(15.0)))))
        assertEquals(listOf("Without active Buddy boost: base levels 14, 15.5, 17"), levelAlternatives(listOf(
            LevelScenario(false, listOf(17.0, 14.0, 15.5, 14.0)), LevelScenario(true, emptyList()))))
        assertEquals(listOf("With active Buddy boost (+1): base levels 14, 14.5"),
            levelAlternatives(listOf(LevelScenario(true, listOf(14.5, 14.0)))))
        assertTrue(levelAlternatives(emptyList()).isEmpty())
        assertEquals("15", levelNumber(15.0))
        assertEquals("15.5", levelNumber(15.5))
    }

    @Test fun preserveOnlyRelevantConditionsInCompactResults() {
        assertEquals("Over cap", shortFeasibility("Already over the CP limit after evolution; cannot power down."))
        assertEquals("Level unknown", shortFeasibility("Theoretical potential only: current level unknown."))
        assertEquals("Level uncertain", shortFeasibility("Reachability depends on the unknown base level / active buddy status."))
        assertEquals("Above level limit", shortFeasibility("Current base level exceeds the selected power-up cap."))
        assertEquals("Remove buddy boost", shortFeasibility("League optimum is reachable by powering up; unequip the active buddy boost first."))
        assertNull(shortFeasibility("League optimum is reachable by powering up."))
    }

    @Test fun currentLevelIsSeparateFromOptimalAndDoesNotInventUnknownValues() {
        val pokemon = Pokemon("sample", "Sample", 100, 100, 100)
        val ivs = IVs(8, 11, 6)
        val stats = Stats(1820, 173, 100.0, 100.0, 1730000.0)
        val optimal = RankedIVs(ivs, 30.5, stats, 5)
        val entry = EvolutionProjection(pokemon, listOf(20.0 to stats), optimal, 99.0, 50.0, stats, "", 4096)
        fun result(entries: List<Pair<Double, Stats>>) = ScanSummary(pokemon, ivs, false, true, "", listOf(
            ScanLeague(1500, "Great League", Assessment(listOf(19.0, 20.0), "", listOf(entry.copy(current = entries))))))
        assertEquals("20.0", effectiveLevelLabel(result(listOf(20.0 to stats))))
        assertEquals("20.0, 20.5", effectiveLevelLabel(result(listOf(20.5 to stats, 20.0 to stats))))
        assertEquals("20.0, 21.5", effectiveLevelLabel(result(listOf(21.5 to stats, 20.0 to stats, 21.5 to stats))))
        assertEquals("Unknown", effectiveLevelLabel(result(emptyList())))
        assertEquals("Unknown", effectiveLevelLabel(null))
    }
}
