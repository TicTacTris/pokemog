package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class ScanAssessmentsTest {
    private val calc = Calculations(javaClass.getResourceAsStream("/cp-multipliers.tsv")!!.bufferedReader().useLines {
        it.filter(String::isNotBlank).map(String::toDouble).toList()
    })
    private val normal = Pokemon("normal", "Example", 118, 111, 128, listOf("evolved"), shadowId = "shadow")
    private val shadow = normal.copy(id = "shadow", name = "Example (Shadow)", evolutions = listOf("evolved_shadow"), shadowId = null, normalId = "normal")
    private val regional = normal.copy(id = "regional", name = "Example (Regional)", attack = 210, defense = 180, stamina = 190, evolutions = emptyList(), shadowId = null)
    private val evolved = Pokemon("evolved", "Evolution", 198, 189, 190)
    private val evolvedShadow = evolved.copy(id = "evolved_shadow", name = "Evolution (Shadow)", normalId = "evolved")
    private val catalog = listOf(normal, shadow, regional, evolved, evolvedShadow)
    private val ivs = IVs(1, 15, 14)
    private fun scan(ids: List<String> = listOf(normal.id), ivs: IVs? = this.ivs, cp: Int? = null, hp: Int? = null) =
        ScanResult("", ids, ivs, cp, hp)
    private fun assess(scan: ScanResult, shadow: Boolean = false, pokemon: List<Pokemon> = catalog) =
        ScanAssessments.calculate(calc, pokemon, scan, shadow)

    @Test fun oversizedRuntimeTargetsFailGracefullyBeforeRanking() {
        val entries = List(33) { i -> Pokemon("node_$i", "Node $i", 100, 100, 100,
            if (i == 0) (1..32).map { "node_$it" } else emptyList()) }
        val result = assess(scan(listOf("node_0")), pokemon = entries)
        assertTrue(result.leagues.isEmpty())
        assertFalse(result.canToggleShadow)
        assertTrue(result.message.contains("limits"))
        assertThrows(IllegalArgumentException::class.java) {
            Projections.calculate(calc, entries, AssessmentInput(entries.first(), ivs))
        }
    }

    @Test fun scatterbugReadingsPreserveConditionalBaseLevelsWithoutIntermediateValues() {
        val scatterbug = Pokemon("scatterbug", "Scatterbug", 63, 63, 116)
        val result = assess(scan(listOf(scatterbug.id), IVs(15, 13, 13), 206, 66), pokemon = listOf(scatterbug))
        assertEquals(listOf(15.0), result.effectiveLevels)
        for (league in result.leagues) {
            assertEquals(listOf(LevelScenario(false, listOf(15.0)), LevelScenario(true, listOf(14.0))), league.assessment.levelScenarios)
            assertEquals(listOf(14.0, 15.0), league.assessment.baseLevels)
            assertEquals(listOf("Without active Buddy boost: base level 15", "With active Buddy boost (+1): base level 14"),
                levelAlternatives(league.assessment.levelScenarios))
        }
    }

    @Test fun boundaryReadingsRetainOnlyValidBuddyBranches() {
        for ((level, scenario) in listOf(1.0 to LevelScenario(false, listOf(1.0)), 51.0 to LevelScenario(true, listOf(50.0)))) {
            val observed = calc.stats(regional, ivs, level)
            val result = assess(scan(listOf(regional.id), cp = observed.cp, hp = observed.hp))
            assertEquals(listOf(level), result.effectiveLevels)
            for (league in result.leagues) assertEquals(listOf(scenario), league.assessment.levelScenarios)
        }
    }

    @Test fun cpPlateausKeepEveryMatchingBaseValueInItsOwnBranch() {
        val tiny = Pokemon("tiny", "Tiny", 1, 1, 1)
        val zero = IVs(0, 0, 0)
        val result = assess(scan(listOf(tiny.id), zero, 10, 10), pokemon = listOf(tiny))
        val bases = (0..98).map { 1.0 + it / 2.0 }
        for (league in result.leagues) {
            assertEquals(listOf(LevelScenario(false, bases), LevelScenario(true, bases)), league.assessment.levelScenarios)
            assertEquals(listOf(
                "Without active Buddy boost: base levels ${bases.joinToString(", ", transform = ::levelNumber)}",
                "With active Buddy boost (+1): base levels ${bases.joinToString(", ", transform = ::levelNumber)}",
            ), levelAlternatives(league.assessment.levelScenarios))
        }
    }

    @Test fun missingOrInvalidBarsNeverBecomeZeroIvs() {
        for (ivs in listOf(null, IVs(-1, 15, 15), IVs(16, 0, 0))) {
            val result = assess(scan(ivs = ivs))
            assertNull(result.ivs)
            assertEquals(normal, result.pokemon)
            assertTrue(result.leagues.isEmpty())
            assertTrue(result.message.contains("Rescan"))
            assertTrue(result.canToggleShadow)
        }
        assertEquals(IVs(0, 0, 0), assess(scan(ivs = IVs(0, 0, 0))).ivs)
    }

    @Test fun unresolvedAndAmbiguousNamesNeverPickFirstOrSearchOutsideParsedIds() {
        val ambiguous = assess(scan(listOf(normal.id, regional.id)))
        assertNull(ambiguous.pokemon)
        assertTrue(ambiguous.leagues.isEmpty())
        assertTrue(ambiguous.message.contains(normal.name))
        assertTrue(ambiguous.message.contains(regional.name))
        assertTrue(ambiguous.message.contains("Rescan"))
        val observed = calc.stats(normal, ivs, 20.0)
        assertNull(assess(scan(listOf("missing"), cp = observed.cp, hp = observed.hp)).pokemon)
        assertNull(assess(scan(listOf(normal.id, regional.id), cp = 999999, hp = 999999)).pokemon)
    }

    @Test fun shadowToggleUsesOnlyExplicitLinksAndDeduplicates() {
        val ids = listOf(normal.id, shadow.id, normal.id)
        assertEquals(normal, assess(scan(ids)).pokemon)
        val result = assess(scan(ids), shadow = true)
        assertEquals(shadow, result.pokemon)
        assertTrue(result.shadow)
        assertTrue(result.canToggleShadow)
        assertEquals(listOf(shadow.id, evolvedShadow.id), result.leagues.first().assessment.evolutions.map { it.pokemon.id })
        assertEquals(normal, assess(scan(listOf(shadow.id))).pokemon)
        assertEquals(shadow, assess(scan(listOf(normal.id)), shadow = true).pokemon)
        val unsupported = assess(scan(listOf(regional.id)), shadow = true)
        assertEquals(regional, unsupported.pokemon)
        assertTrue(unsupported.canToggleShadow)
        assertTrue(unsupported.leagues.isNotEmpty())
        assertEquals(setOf(regional.id), unsupported.unverifiedShadowIds)
        assertTrue(result.unverifiedShadowIds.isEmpty())
        val mixed = assess(scan(listOf(normal.id, regional.id)))
        assertFalse(mixed.canToggleShadow)
        assertNull(mixed.pokemon)
    }

    @Test fun cpAndHpNarrowRegionalCandidatesIncludingEffectiveLevel51() {
        for (level in listOf(20.0, 51.0)) {
            val observed = calc.stats(regional, ivs, level)
            val result = assess(scan(listOf(normal.id, regional.id), cp = observed.cp, hp = observed.hp))
            assertEquals(regional, result.pokemon)
            assertTrue(result.leagues.first().assessment.evolutions.single().current.any { it.first == level })
            if (level == 51.0) {
                assertEquals(listOf(50.0), result.leagues.first().assessment.baseLevels)
                assertTrue(result.message.contains("require an active buddy boost"))
            }
        }
        val sameStats = regional.copy(attack = normal.attack, defense = normal.defense, stamina = normal.stamina)
        val observed = calc.stats(normal, ivs, 20.0)
        assertNull(assess(scan(listOf(normal.id, regional.id), cp = observed.cp, hp = observed.hp),
            pokemon = listOf(normal, sameStats)).pokemon)
    }

    @Test fun resolvedRegionalWithoutShadowCannotSwitchToUnrelatedShadow() {
        for (level in listOf(20.0, 51.0)) {
            val observed = calc.stats(regional, ivs, level)
            val scan = scan(listOf(normal.id, regional.id), cp = observed.cp, hp = observed.hp)
            val unchecked = assess(scan)
            assertEquals(regional, unchecked.pokemon)
            assertTrue(unchecked.canToggleShadow)
            val checked = assess(scan, shadow = true)
            assertTrue(checked.canToggleShadow)
            assertEquals(regional, checked.pokemon)
            assertEquals(setOf(regional.id), checked.unverifiedShadowIds)
            assertEquals(unchecked.leagues, checked.leagues)
            assertEquals(unchecked.effectiveLevels, checked.effectiveLevels)
            assertTrue(checked.effectiveLevels.contains(level))
        }
    }

    @Test fun resolvedRegionalWithShadowOnlyMapsToItsOwnCounterpart() {
        val regionalNormal = regional.copy(shadowId = "regional_shadow")
        val regionalShadow = regional.copy(id = "regional_shadow", name = "Example (Regional Shadow)", normalId = regional.id)
        val pokemon = catalog.filter { it.id != regional.id } + listOf(regionalNormal, regionalShadow)
        for (level in listOf(20.0, 51.0)) {
            val observed = calc.stats(regionalNormal, ivs, level)
            val scan = scan(listOf(normal.id, regional.id), cp = observed.cp, hp = observed.hp)
            val unchecked = assess(scan, pokemon = pokemon)
            assertEquals(regionalNormal, unchecked.pokemon)
            assertTrue(unchecked.canToggleShadow)
            val checked = assess(scan, shadow = true, pokemon = pokemon)
            assertEquals(regionalShadow, checked.pokemon)
            assertTrue(checked.canToggleShadow)
            assertTrue(checked.leagues.isNotEmpty())
            assertTrue(checked.leagues.all { league ->
                league.assessment.evolutions.single().current.any { it.first == level }
            })
        }
    }

    @Test fun shadowAvailabilityNeverDisambiguatesUnresolvedBaseForms() {
        val ids = listOf(normal.id, regional.id)
        assertNull(assess(scan(ids)).pokemon)
        val unresolved = assess(scan(ids), shadow = true)
        assertNull(unresolved.pokemon)
        assertFalse(unresolved.canToggleShadow)
        assertTrue(unresolved.leagues.isEmpty())
        val sameStats = regional.copy(attack = normal.attack, defense = normal.defense, stamina = normal.stamina)
        val pokemon = catalog.filter { it.id != regional.id } + sameStats
        val observed = calc.stats(normal, ivs, 20.0)
        val scan = scan(ids, cp = observed.cp, hp = observed.hp)
        assertNull(assess(scan, pokemon = pokemon).pokemon)
        val checked = assess(scan, shadow = true, pokemon = pokemon)
        assertNull(checked.pokemon)
        assertFalse(checked.canToggleShadow)
        assertTrue(checked.leagues.isEmpty())
    }

    @Test fun identifiedSpeciesKeepsEditableConditionWithoutBarsOrShadowRecord() {
        for (condition in listOf(false, true)) {
            val result = assess(scan(listOf(regional.id), ivs = null), shadow = condition)
            assertEquals(regional, result.pokemon)
            assertNull(result.ivs)
            assertEquals(condition, result.shadow)
            assertTrue(result.canToggleShadow)
            assertTrue(result.leagues.isEmpty())
            assertTrue(result.effectiveLevels.isEmpty())
            assertEquals(if (condition) setOf(regional.id) else emptySet<String>(), result.unverifiedShadowIds)
            val known = assess(scan(listOf(normal.id), ivs = null), shadow = condition)
            assertEquals(if (condition) shadow else normal, known.pokemon)
            assertTrue(known.canToggleShadow)
            assertTrue(known.unverifiedShadowIds.isEmpty())
            for (ids in listOf(listOf(normal.id, regional.id), listOf("missing"))) {
                val unresolved = assess(scan(ids, ivs = null), shadow = condition)
                assertNull(unresolved.pokemon)
                assertFalse(unresolved.canToggleShadow)
                assertTrue(unresolved.leagues.isEmpty())
                assertTrue(unresolved.message.contains("Rescan"))
            }
        }
    }

    @Test fun shadowConditionPreservesAllCalculationsAndToggleOffReverses() {
        for (p in listOf(normal, regional)) {
            val observed = calc.stats(p, ivs, 20.0)
            val scan = scan(listOf(p.id), cp = observed.cp, hp = observed.hp)
            val unchecked = assess(scan)
            val checked = assess(scan, shadow = true)
            assertTrue(unchecked.canToggleShadow)
            assertTrue(checked.canToggleShadow)
            assertEquals(unchecked.ivs, checked.ivs)
            assertEquals(unchecked.message, checked.message)
            assertEquals(unchecked.effectiveLevels, checked.effectiveLevels)
            assertEquals(listOf(1500, 2500, 500), checked.leagues.map { it.cpCap })
            for ((a, b) in unchecked.leagues.zip(checked.leagues)) {
                assertEquals(a.assessment.baseLevels, b.assessment.baseLevels)
                assertEquals(a.assessment.evolutions.size, b.assessment.evolutions.size)
                for ((normalEntry, shadowEntry) in a.assessment.evolutions.zip(b.assessment.evolutions)) {
                    assertEquals(normalEntry, shadowEntry.copy(pokemon = normalEntry.pokemon))
                    assertTrue(shadowEntry.summary(true).contains("Shadow ATK equivalent"))
                    assertFalse(normalEntry.summary(false).contains("Shadow ATK equivalent"))
                }
            }
            assertEquals(unchecked, assess(scan, shadow = false))
            assertTrue(unchecked.unverifiedShadowIds.isEmpty())
        }
        assertEquals(normal, assess(scan(listOf(shadow.id)), shadow = false).pokemon)
    }

    @Test fun shadowEvolutionAvailabilityRequiresTheMatchingGraphPath() {
        val noShadowEdge = shadow.copy(evolutions = emptyList())
        val pokemon = catalog.filter { it.id != shadow.id } + noShadowEdge
        val result = assess(scan(), shadow = true, pokemon = pokemon)
        assertEquals(noShadowEdge, result.pokemon)
        assertEquals(listOf(shadow.id, evolvedShadow.id), result.leagues.first().assessment.evolutions.map { it.pokemon.id })
        assertEquals(setOf(evolvedShadow.id), result.unverifiedShadowIds)

        val missingTarget = assess(scan(), shadow = true, pokemon = catalog.filter { it.id != evolvedShadow.id })
        assertEquals(listOf(shadow.id, evolved.id), missingTarget.leagues.first().assessment.evolutions.map { it.pokemon.id })
        assertEquals(setOf(evolved.id), missingTarget.unverifiedShadowIds)

        val regionalFamily = regional.copy(evolutions = listOf("regional_evolution"))
        val regionalEvolution = evolved.copy(id = "regional_evolution", name = "Evolution (Regional)")
        val regionalResult = assess(scan(listOf(regional.id)), shadow = true,
            pokemon = catalog.filter { it.id != regional.id } + listOf(regionalFamily, regionalEvolution))
        assertEquals(listOf(regional.id, regionalEvolution.id), regionalResult.leagues.first().assessment.evolutions.map { it.pokemon.id })
        assertEquals(setOf(regional.id, regionalEvolution.id), regionalResult.unverifiedShadowIds)
    }

    @Test fun brokenShadowBaseStatsUseCanonicalDataAndUnverifiedAvailability() {
        val broken = shadow.copy(attack = shadow.attack + 1)
        val pokemon = catalog.filter { it.id != shadow.id } + broken
        val unchecked = assess(scan(), pokemon = pokemon)
        val checked = assess(scan(), shadow = true, pokemon = pokemon)
        assertEquals(normal, checked.pokemon)
        assertEquals(setOf(normal.id, evolvedShadow.id), checked.unverifiedShadowIds)
        for ((a, b) in unchecked.leagues.zip(checked.leagues)) {
            for ((normalEntry, shadowEntry) in a.assessment.evolutions.zip(b.assessment.evolutions)) {
                assertEquals(normalEntry, shadowEntry.copy(pokemon = normalEntry.pokemon))
            }
        }
        val brokenTarget = evolvedShadow.copy(stamina = evolvedShadow.stamina + 1)
        val result = assess(scan(), shadow = true, pokemon = catalog.filter { it.id != evolvedShadow.id } + brokenTarget)
        assertEquals(listOf(shadow.id, evolved.id), result.leagues.first().assessment.evolutions.map { it.pokemon.id })
        assertEquals(setOf(evolved.id), result.unverifiedShadowIds)
    }

    @Test fun unknownCurrentLevelStillHasTheoreticalPotentialAndFixedLeagues() {
        for ((cp, hp) in listOf(null to null, 9 to 10, 999999 to 999999, 100 to null)) {
            val result = assess(scan(cp = cp, hp = hp))
            assertEquals(normal, result.pokemon)
            assertTrue(result.message.contains("Current level unknown"))
            assertEquals(listOf(1500, 2500, 500), result.leagues.map { it.cpCap })
            for (league in result.leagues) {
                assertTrue(league.assessment.baseLevels.isEmpty())
                assertTrue(league.assessment.levelScenarios.isEmpty())
                assertTrue(levelAlternatives(league.assessment.levelScenarios).isEmpty())
                assertEquals(listOf(normal.id, evolved.id), league.assessment.evolutions.map { it.pokemon.id })
                for (entry in league.assessment.evolutions) {
                    assertTrue(entry.current.isEmpty())
                    assertEquals(50.0, entry.maximumLevel, 0.0)
                    assertEquals(calc.stats(entry.pokemon, ivs, 50.0), entry.maximum)
                    assertTrue(entry.feasibility.contains("Theoretical"))
                }
                assertFalse(league.assessment.summary(false).contains("Enter", ignoreCase = true))
                assertFalse(league.assessment.summary(false).contains("confirmed", ignoreCase = true))
            }
        }
    }

    @Test fun buddyBaseAmbiguityDoesNotGuaranteeReachability() {
        val optimum = calc.rankIVs(evolved, 1500, 50.0).single { it.ivs == ivs }
        val observedLevel = optimum.level + 0.5
        val observed = calc.stats(normal, ivs, observedLevel)
        val result = assess(scan(cp = observed.cp, hp = observed.hp))
        assertTrue(result.message.contains("Active buddy status is unknown"))
        val assessment = result.leagues.first().assessment
        assertTrue(assessment.baseLevels.contains(observedLevel))
        assertTrue(assessment.baseLevels.contains(observedLevel - 1))
        val entry = assessment.evolutions.single { it.pokemon.id == evolved.id }
        assertTrue(entry.current.any { it.first == observedLevel })
        assertTrue(entry.feasibility.contains("not guaranteed"))
        assertEquals(optimum, entry.optimal)
    }

    @Test fun noEligibleSpreadsProducesNoPercentile() {
        val giant = Pokemon("giant", "Giant", 10000, 10000, 10000)
        val result = assess(scan(listOf(giant.id)), pokemon = listOf(giant))
        for (league in result.leagues) {
            val entry = league.assessment.evolutions.single()
            assertEquals(0, entry.eligibleSpreads)
            assertNull(entry.optimal)
            assertNull(entry.ivPercentile)
            assertTrue(entry.summary(false).contains("No eligible league build"))
        }
    }

    @Test fun warmShadowProjectionKeepsCanonicalShedinjaRules() {
        val canonical = Pokemon("shedinja", "Shedinja", 153, 73, 1, shadowId = "alternate_shadow")
        val counterpart = canonical.copy(id = "alternate_shadow", name = "Shadow", shadowId = null, normalId = canonical.id)
        val selected = IVs(15, 15, 15)
        val observed = calc.stats(canonical, selected, 20.0)
        val scan = scan(listOf(canonical.id), selected, observed.cp, observed.hp)
        val pokemon = listOf(canonical, counterpart)
        val unchecked = assess(scan, pokemon = pokemon)
        val checked = assess(scan, shadow = true, pokemon = pokemon)
        for ((a, b) in unchecked.leagues.zip(checked.leagues)) {
            val normalEntry = a.assessment.evolutions.single()
            val shadowEntry = b.assessment.evolutions.single()
            assertEquals(normalEntry, shadowEntry.copy(pokemon = canonical))
            assertEquals(10, shadowEntry.maximum.hp)
            assertEquals(10, shadowEntry.optimal!!.stats.hp)
            assertTrue(shadowEntry.current.all { it.second.hp == 10 })
            assertSame(normalEntry.optimal, shadowEntry.optimal)
        }
        assertEquals(unchecked, assess(scan, pokemon = pokemon))
    }
}
