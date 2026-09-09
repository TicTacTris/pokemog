package dev.pokemog.android

import org.junit.Assert.*
import org.junit.Test

class FormEquivalenceTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private val calc = Calculations(resource("cp-multipliers.tsv").lineSequence().filter { it.isNotBlank() }.map { it.toDouble() }.toList())
    private val catalog = readPokemonCatalog(resource("pokemon.json"), resource("evolution-metadata.json"))
    private val tox = catalog.filter { it.id in setOf("toxtricity", "toxtricity_amped", "toxtricity_low_key") }
    private val ivs = IVs(10, 11, 15)
    private val a = Pokemon("a", "Example (First)", 224, 140, 181, hasEvolutionData = true)
    private val b = a.copy(id = "b", name = "Example (Second)")
    private fun scan(forms: List<Pokemon>) = ScanResult("original OCR", forms.map { it.id }, ivs, 1436, 117)
    private fun assess(forms: List<Pokemon>, all: List<Pokemon> = forms, shadow: Boolean = false, input: ScanResult = scan(forms)) =
        ScanAssessments.calculate(calc, all, input, shadow)
    private fun reject(forms: List<Pokemon>, all: List<Pokemon> = forms, input: ScanResult = scan(forms)) {
        for (shadow in listOf(false, true)) {
            val result = assess(forms, all, shadow, input)
            assertNull(result.pokemon)
            assertTrue(result.leagues.isEmpty())
            assertFalse(result.canToggleShadow)
            assertFalse(result.formUnresolved)
        }
    }

    @Test fun actualCatalogToxtricityAllSubsetsAndPermutationsShareWithoutIdentifying() {
        assertEquals(3, tox.size)
        assertTrue(tox.all { it.hasEvolutionData && it.evolutions.isEmpty() })
        assertEquals(tox.map { it.id }.toSet(), PokemonNameIndex(catalog).candidates("Toxtricity").toSet())
        val orders = tox.flatMap { first -> (tox - first).map { second -> listOf(first, second) + (tox - first - second) } }
        for (forms in orders.flatMap { listOf(it, it.take(2)) }) {
            val input = scan(forms)
            val result = assess(forms, catalog, input = input)
            assertNull(result.pokemon)
            assertEquals(forms.sortedBy { it.id }, result.unresolvedCandidateIdentities)
            assertEquals("Toxtricity", result.displayName)
            assertEquals("Form unresolved; shared IV calculations", result.formLabel)
            assertEquals(forms.map { it.id }, input.candidates)
            assertEquals(listOf(20.0), result.effectiveLevels)
            assertTrue(result.canToggleShadow)
            val checked = assess(forms, catalog, true)
            assertEquals(result.leagues, checked.leagues)
            assertEquals(result.unresolvedCandidateIdentities, checked.unresolvedCandidateIdentities)
            assertEquals(setOf(forms.minBy { it.id }.id), checked.unverifiedShadowIds)
            for (league in result.leagues) {
                assertEquals(listOf(LevelScenario(false, listOf(20.0)), LevelScenario(true, listOf(19.0))), league.assessment.levelScenarios)
                val entry = league.assessment.evolutions.single()
                assertEquals("Toxtricity (form unresolved)", entry.pokemon.name)
                assertTrue(result.isCurrent(entry.pokemon))
                assertEquals(1436, entry.current.single().second.cp)
                assertEquals(117, entry.current.single().second.hp)
                val exact = assess(listOf(forms.first()), catalog).leagues.single { it.cpCap == league.cpCap }.assessment.evolutions.single()
                assertEquals(exact.copy(pokemon = entry.pokemon), entry)
                assertTrue(league.assessment.summary(false).contains(result.formLabel!!))
            }
        }
    }

    @Test fun genericPolicySupportsOtherCatalogFormsAndStrictNameGroups() {
        val forms = listOf(a, b.copy(name = "EXAMPLE (Second)"))
        assertTrue(assess(forms).formUnresolved)
        reject(listOf(a, b.copy(name = "Unrelated (Second)")))
        reject(listOf(a, b.copy(name = "Example_Second")))
        // Both still match the rounded observations, but their exact base stats differ.
        val tiny = a.copy(attack = 1, defense = 1, stamina = 1)
        val different = b.copy(attack = 2, defense = 1, stamina = 1)
        reject(listOf(tiny, different), input = scan(listOf(tiny, different)).copy(ivs = IVs(0, 0, 0), cp = 10, hp = 10))
        val shell = a.copy(id = "shedinja", attack = 153, defense = 73, stamina = 1)
        val otherRule = shell.copy(id = "ordinary", name = b.name)
        val observed = calc.stats(shell, ivs, 1.0)
        reject(listOf(shell, otherRule), input = scan(listOf(shell, otherRule)).copy(cp = observed.cp, hp = observed.hp))
    }

    @Test fun otherRealCatalogFormsShareButMissingFamiliesRemainAmbiguous() {
        val forms = catalog.filter { catalogNameGroup(it.name) == "meowstic" }
        assertEquals(2, forms.size)
        val observed = calc.stats(forms.first(), ivs, 20.0)
        val result = assess(forms, catalog, input = scan(forms).copy(cp = observed.cp, hp = observed.hp))
        assertTrue(result.formUnresolved)
        assertEquals("Meowstic", result.displayName)
        val missing = catalog.filter { catalogNameGroup(it.name) == "oricorio" }
        assertEquals(4, missing.size)
        assertTrue(missing.none { it.hasEvolutionData })
        val other = calc.stats(missing.first(), ivs, 20.0)
        reject(missing, catalog, scan(missing).copy(cp = other.cp, hp = other.hp))
    }

    @Test fun sharedFormsRequireCompleteCompatibleValidObservations() {
        val forms = listOf(a, b)
        for (input in listOf(scan(forms).copy(ivs = null), scan(forms).copy(ivs = IVs(16, 11, 15)),
            scan(forms).copy(ivs = IVs(10, -1, 15)), scan(forms).copy(ivs = IVs(10, 11, 16)),
            scan(forms).copy(cp = null), scan(forms).copy(hp = null), scan(forms).copy(cp = 9),
            scan(forms).copy(hp = 9), scan(forms).copy(cp = 999999), scan(forms).copy(hp = 999999))) reject(forms, input = input)
        val zero = scan(forms).copy(ivs = IVs(0, 0, 0)).let { input ->
            val observed = calc.stats(a, input.ivs!!, 20.0)
            input.copy(cp = observed.cp, hp = observed.hp)
        }
        assertTrue(assess(forms, input = zero).formUnresolved)
    }

    @Test fun missingMetadataNeverProvesTerminalAndDivergentGraphsNeverShare() {
        reject(listOf(a, b.copy(hasEvolutionData = false)))
        val x = a.copy(id = "x", name = "Evolution")
        val y = x.copy(id = "y")
        val divergent = listOf(a.copy(evolutions = listOf(x.id)), b.copy(evolutions = listOf(y.id)))
        reject(divergent, divergent + x + y)
        val shared = listOf(a.copy(evolutions = listOf(x.id)), b.copy(evolutions = listOf(x.id)))
        assertTrue(assess(shared, shared + x).formUnresolved)
        assertEquals(listOf("a", "x"), assess(shared, shared + x).leagues.first().assessment.evolutions.map { it.pokemon.id })
        reject(shared, shared + x.copy(hasEvolutionData = false))
        reject(shared, shared + x.copy(evolutions = listOf("missing")))
        reject(shared, shared + x.copy(evolutions = listOf("a")))
    }

    @Test fun deepNormalAndShadowGraphsAreStackSafeAndStillRejectInvalidDescendants() {
        val size = 9000
        val normals = List(size) { i -> a.copy(id = "node_$i", shadowId = "node_${i}_shadow",
            evolutions = if (i + 1 < size) listOf("node_${i + 1}") else emptyList()) }
        val shadows = normals.map { it.copy(id = "${it.id}_shadow", normalId = it.id, shadowId = null,
            evolutions = it.evolutions.map { id -> "${id}_shadow" }) }
        val forms = listOf(a, b).map { it.copy(evolutions = listOf(normals.first().id), shadowId = "${it.id}_shadow") }
        val roots = forms.map { it.copy(id = "${it.id}_shadow", normalId = it.id, shadowId = null,
            evolutions = listOf(shadows.first().id)) }
        val byId = (forms + roots + normals + shadows).associateBy { it.id }
        fun equivalent(entries: Map<String, Pokemon>) = ScanAssessments.calculationEquivalent(forms, entries,
            { it.normalId != null }, { p -> p.shadowId?.let { entries[it] } })
        assertFalse("Deep graphs exceed the supported contract without stack overflow", equivalent(byId))
        for (chain in listOf(normals, shadows)) {
            val last = chain.last()
            for (invalid in listOf(last.copy(hasEvolutionData = false), last.copy(evolutions = listOf("absent")),
                last.copy(evolutions = listOf(chain.first().id)),
                last.copy(normalId = if (last.normalId == null) "unexpected" else null))) {
                assertFalse(equivalent(byId + (last.id to invalid)))
            }
        }
    }

    @Test fun convergentDagPathsAreNotMistakenForCycles() {
        val leaf = a.copy(id = "leaf")
        val left = a.copy(id = "left", evolutions = listOf(leaf.id))
        val right = a.copy(id = "right", evolutions = listOf(leaf.id))
        val forms = listOf(a, b).map { it.copy(evolutions = listOf(left.id, right.id, leaf.id)) }
        val byId = (forms + left + right + leaf).associateBy { it.id }
        assertTrue(ScanAssessments.calculationEquivalent(forms, byId, { false }, { null }))
        assertFalse(ScanAssessments.calculationEquivalent(forms,
            byId + (leaf.id to leaf.copy(evolutions = listOf(right.id))), { false }, { null }))
    }

    @Test fun shadowAvailabilityAndPathsMustAgreeEvenWhenSwitchIsOff() {
        val sa = a.copy(id = "a_shadow", normalId = a.id)
        val sb = b.copy(id = "b_shadow", normalId = b.id)
        val forms = listOf(a, b)
        reject(forms, forms + sa)
        reject(forms, forms + sa + sb.copy(attack = sb.attack + 1))
        reject(forms, forms + sa + sb.copy(hasEvolutionData = false))
        val terminal = assess(forms, forms + sa + sb, true)
        assertTrue(terminal.formUnresolved)
        assertTrue(terminal.unverifiedShadowIds.isEmpty())
        assertNull(terminal.pokemon)
        assertEquals("Example (form unresolved)", terminal.leagues.first().assessment.evolutions.first().pokemon.name)
        val x = a.copy(id = "x", name = "Evolution")
        val sx = x.copy(id = "x_shadow", normalId = x.id)
        val roots = forms.map { it.copy(evolutions = listOf(x.id)) }
        reject(roots, roots + x + sx + sa.copy(evolutions = listOf(sx.id)) + sb)
        val all = roots + x + sx + listOf(sa, sb).map { it.copy(evolutions = listOf(sx.id)) }
        val shared = assess(roots, all, true)
        assertTrue(shared.formUnresolved)
        assertTrue(shared.unverifiedShadowIds.isEmpty())
        assertEquals(listOf("a_shadow", "x_shadow"), shared.leagues.first().assessment.evolutions.map { it.pokemon.id })
        val unverified = assess(roots, roots + x + sx + sa + sb, true)
        assertTrue(unverified.formUnresolved)
        assertEquals(setOf(sx.id), unverified.unverifiedShadowIds)
    }
}
