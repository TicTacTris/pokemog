package dev.pokemog.android

data class ScanLeague(val cpCap: Int, val title: String, val assessment: Assessment)
data class ScanSummary(
    val pokemon: Pokemon?,
    val ivs: IVs?,
    val shadow: Boolean,
    val canToggleShadow: Boolean,
    val message: String,
    val leagues: List<ScanLeague>,
    val unverifiedShadowIds: Set<String> = emptySet(),
    val unresolvedCandidateIdentities: List<Pokemon> = emptyList(),
) {
    val formUnresolved: Boolean get() = unresolvedCandidateIdentities.size > 1 && leagues.isNotEmpty()
    val formLabel: String? get() = if (formUnresolved) "Form unresolved; shared IV calculations" else null
    val displayName: String? get() = if (formUnresolved) catalogBaseName(unresolvedCandidateIdentities.first().name) else pokemon?.name
    val candidateLabel: String? get() = formLabel?.let { "$it. Candidates: ${unresolvedCandidateIdentities.joinToString { p -> "${p.name} [${p.id}]" }}" }
    fun isCurrent(p: Pokemon): Boolean = p.id == leagues.firstOrNull()?.assessment?.evolutions?.firstOrNull()?.pokemon?.id
    val effectiveLevels: List<Double>
        get() = leagues.firstOrNull()?.assessment?.evolutions?.firstOrNull()?.current
            ?.map { it.first }?.distinct()?.sorted().orEmpty()
}

object ScanAssessments {
    fun calculate(repo: PokemonRepository, scan: ScanResult, shadow: Boolean): ScanSummary =
        calculate(repo.calculations, repo.pokemon, scan, shadow)

    internal fun calculate(calc: Calculations, pokemonList: List<Pokemon>, scan: ScanResult, shadow: Boolean): ScanSummary {
        val byId = pokemonList.associateBy { it.id }
        val parsed = scan.candidates.mapNotNull { byId[it] }.distinctBy { it.id }
        fun isShadow(p: Pokemon) = p.normalId != null || pokemonList.any { it.shadowId == p.id }
        val normals = parsed.flatMap { p ->
            if (!isShadow(p)) listOf(p)
            else listOfNotNull(p.normalId?.let { byId[it] }) + pokemonList.filter { it.shadowId == p.id }
        }.distinctBy { it.id }
        val ivs = scan.ivs?.takeIf { it.attack in 0..15 && it.defense in 0..15 && it.stamina in 0..15 }
        val readingsValid = scan.cp != null && scan.hp != null && scan.cp >= 10 && scan.hp >= 10
        // Keep buddy status attached to each base-level set; effective level alone cannot establish reachability.
        fun levelScenarios(p: Pokemon): List<LevelScenario> =
            if (ivs != null && readingsValid) listOf(false, true).mapNotNull { buddy ->
                calc.inferLevels(p, ivs, scan.cp!!, scan.hp!!, buddy).takeIf { it.isNotEmpty() }?.let { LevelScenario(buddy, it) }
            } else emptyList()
        val normalPossibilities = normals.associateWith(::levelScenarios)
        val compatibleNormals = normals.filter { normalPossibilities.getValue(it).isNotEmpty() }
        // Shadow is a damage condition, never evidence for choosing a species or regional form.
        val remainingNormals = compatibleNormals.ifEmpty { normals }
        fun unavailable(message: String) = ScanSummary(null, ivs, shadow, false, message, emptyList())
        if (remainingNormals.isEmpty()) return unavailable(
            "No supported Pokemon could be identified. Rescan with the Pokemon name visible."
        )
        fun shadowCounterpart(p: Pokemon): Pokemon? {
            val linked = (listOfNotNull(p.shadowId?.let { byId[it] }) + pokemonList.filter { it.normalId == p.id })
                .distinctBy { it.id }.singleOrNull() ?: return null
            return linked.takeIf {
                (it.normalId == null || it.normalId == p.id) &&
                    it.attack == p.attack && it.defense == p.defense && it.stamina == p.stamina
            }
        }
        val unresolved = if (remainingNormals.size > 1) remainingNormals.sortedBy { it.id } else emptyList()
        if (unresolved.isNotEmpty() && (ivs == null || !readingsValid ||
                compatibleNormals.size != remainingNormals.size ||
                !calculationEquivalent(unresolved, byId, ::isShadow, ::shadowCounterpart))) {
            return unavailable("Scan is ambiguous: ${remainingNormals.joinToString { it.name }}. Rescan with the name, CP, maximum HP and appraisal bars visible.")
                .copy(unresolvedCandidateIdentities = unresolved)
        }
        // Stable calculation representative only; never publish it as an identified form.
        val selected = unresolved.firstOrNull() ?: remainingNormals.single()
        val selectedShadow = if (shadow) shadowCounterpart(selected) else null
        val displayPokemon = selectedShadow ?: selected
        if (ivs == null) return ScanSummary(
            displayPokemon, null, shadow, true,
            "Appraisal bars could not be read. Rescan with all three IV bars visible; no IVs have been assumed.",
            emptyList(), if (shadow && selectedShadow == null) setOf(selected.id) else emptySet(),
        )
        val scenarios = normalPossibilities.getValue(selected)
        val effectiveLevels = scenarios.flatMap { (buddy, levels) -> levels.map { it + if (buddy) 1 else 0 } }.distinct().sorted()
        val note = (if (unresolved.isNotEmpty()) "Form unresolved; shared IV calculations. " else "") + if (effectiveLevels.isEmpty()) {
            "Current level unknown: CP/HP are missing, invalid or inconsistent with this form and IVs. Only theoretical league potential is shown. Rescan with CP and maximum HP visible."
        } else {
            val range = if (effectiveLevels.size == 1) fmt(effectiveLevels.single()) else "${fmt(effectiveLevels.first())}-${fmt(effectiveLevels.last())}"
            "Observed effective level $range. " + when {
                scenarios.size > 1 -> "Active buddy status is unknown; base level and reachability may be ambiguous."
                scenarios.single().activeBuddy -> "Readings require an active buddy boost; base level is one lower."
                else -> "Readings match only an unboosted Pokemon."
            }
        } + " League potential uses maximum base level 50, with no future buddy boost."
        val targets = mutableListOf<Pokemon>()
        val pending = ArrayDeque<Pokemon>()
        val seen = mutableSetOf<String>()
        pending.add(selected)
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            if (!seen.add(p.id) || isShadow(p)) continue
            if (targets.size >= MAX_EVOLUTION_DESCENDANTS || p.evolutions.size > MAX_EVOLUTION_EDGES)
                return unavailable("Evolution data exceeds supported limits. Try an updated data pack.")
            targets.add(p)
            p.evolutions.mapNotNull { byId[it] }.forEach { pending.add(it) }
        }
        val shadowTargets = if (shadow) targets.associateWith(::shadowCounterpart) else emptyMap()
        val verified = mutableSetOf<String>()
        if (selectedShadow != null) verified.add(selected.id)
        // A catalog counterpart alone does not prove it can evolve from this scanned Shadow.
        var changed = true
        while (shadow && changed) {
            changed = false
            for (p in targets) {
                if (p.id !in verified) continue
                val source = shadowTargets[p] ?: continue
                for (target in targets) {
                    val destination = shadowTargets[target] ?: continue
                    if (target.id in p.evolutions && destination.id in source.evolutions && verified.add(target.id)) changed = true
                }
            }
        }
        val unverified = if (shadow) targets.filter { it.id !in verified }
            .map { (shadowTargets[it] ?: it).id }.toSet() else emptySet()
        val leagues = listOf(1500 to "Great League", 2500 to "Ultra League", 500 to "Little League").map { (cap, title) ->
            val input = AssessmentInput(selected, ivs, shadow = shadow, leagueCap = cap)
            val theoretical = Projections.calculate(calc, targets, input)
            val entries = theoretical.evolutions.map { projection ->
                val fits = scenarios.flatMap { (buddy, levels) -> levels.map { base ->
                    Projections.feasibility(calc, projection.pokemon, input.copy(activeBuddy = buddy), listOf(base), projection.optimal)
                } }.distinct()
                projection.copy(
                    // Calculate on canonical data so toggling cannot change stats, ranks or special species rules.
                    pokemon = (shadowTargets[projection.pokemon] ?: projection.pokemon).let { p ->
                        if (unresolved.isNotEmpty() && projection.pokemon.id == selected.id)
                            p.copy(name = "${catalogBaseName(selected.name)} (form unresolved)") else p
                    },
                    current = effectiveLevels.map { it to calc.stats(projection.pokemon, ivs, it) },
                    feasibility = when {
                        projection.optimal == null -> "No eligible league build."
                        fits.isEmpty() -> "Theoretical potential only: current level unknown."
                        fits.size == 1 -> fits.single()
                        else -> "Theoretical optimum: reachability depends on the unknown base level / active buddy status; it is not guaranteed."
                    },
                )
            }
            ScanLeague(cap, title, Assessment(scenarios.flatMap { it.baseLevels }.distinct().sorted(), note, entries, scenarios))
        }
        return ScanSummary(if (unresolved.isEmpty()) displayPokemon else null, ivs, shadow, true, note, leagues, unverified, unresolved)
    }

    /** Exact descendant identities are required, not merely equal descendant stats. */
    internal fun calculationEquivalent(
        forms: List<Pokemon>, byId: Map<String, Pokemon>, isShadow: (Pokemon) -> Boolean,
        counterpart: (Pokemon) -> Pokemon?,
    ): Boolean {
        val first = forms.first()
        val group = catalogNameGroup(first.name)
        if (group.isBlank() || forms.any { catalogNameGroup(it.name) != group ||
                it.attack != first.attack || it.defense != first.defense || it.stamina != first.stamina ||
                it.hasFixedHp != first.hasFixedHp || it.evolutions != first.evolutions }) return false
        fun knownGraph(root: Pokemon, shadowGraph: Boolean): Boolean {
            val visiting = mutableSetOf<String>()
            val done = mutableSetOf<String>()
            val pending = ArrayDeque<Pair<Pokemon, Boolean>>()
            pending.add(root to false)
            while (pending.isNotEmpty()) {
                val (p, exiting) = pending.removeLast()
                if (exiting) {
                    visiting.remove(p.id)
                    done.add(p.id)
                    continue
                }
                if (p.id in done) continue
                if (visiting.size > MAX_EVOLUTION_DEPTH || visiting.size + done.size >= MAX_EVOLUTION_DESCENDANTS ||
                    p.evolutions.size > MAX_EVOLUTION_EDGES) return false
                if (!p.hasEvolutionData || isShadow(p) != shadowGraph || !visiting.add(p.id)) return false
                // Exit only after every descendant: a gray node is a cycle, a done node is a shared DAG path.
                pending.add(p to true)
                for (id in p.evolutions.asReversed()) pending.add((byId[id] ?: return false) to false)
            }
            return true
        }
        if (forms.any { !knownGraph(it, false) }) return false
        val shadows = forms.map { p ->
            val linked = counterpart(p)
            // A malformed/ambiguous link is not equivalent to absent availability.
            if (linked == null && (p.shadowId != null || byId.values.any { it.normalId == p.id })) return false
            linked
        }
        if (shadows.all { it == null }) return true
        if (shadows.any { it == null }) return false
        val roots = shadows.filterNotNull()
        return roots.all { knownGraph(it, true) && it.evolutions == roots.first().evolutions }
    }
}
