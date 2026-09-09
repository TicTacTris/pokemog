package dev.pokemog.android

import java.util.Locale

data class AssessmentInput(
    val pokemon: Pokemon,
    val ivs: IVs,
    val shadow: Boolean = false,
    val observedCp: Int? = null,
    val observedHp: Int? = null,
    val baseLevel: Double? = null,
    val activeBuddy: Boolean = false,
    val leagueCap: Int = 1500,
    val maxBaseLevel: Int = 50,
    val allowBestBuddy: Boolean = false,
)

data class EvolutionProjection(
    val pokemon: Pokemon,
    val current: List<Pair<Double, Stats>>,
    val optimal: RankedIVs?,
    val percentBest: Double,
    val maximumLevel: Double,
    val maximum: Stats,
    val feasibility: String,
    val eligibleSpreads: Int,
) {
    val ivPercentile: Double?
        get() = optimal?.let { if (eligibleSpreads == 1) 100.0 else 100.0 * (eligibleSpreads - it.rank) / (eligibleSpreads - 1) }

    fun summary(shadow: Boolean, isEvolution: Boolean = false): String = buildString {
        val heading = if (isEvolution) "After evolution, no power-ups" else "Current stats"
        if (current.isEmpty()) appendLine("$heading: level unknown")
        else {
            val first = current.first(); val last = current.last()
            appendLine("$heading: effective ${if (current.size == 1) "level" else "levels"} ${current.joinToString(", ") { fmt(it.first) }}")
            if (current.size == 1) appendLine(first.second.describe(shadow))
            else appendLine("CP ${first.second.cp}-${last.second.cp} | HP ${first.second.hp}-${last.second.hp}\nATK ${fmt(first.second.attack)}-${fmt(last.second.attack)} | DEF ${fmt(first.second.defense)}-${fmt(last.second.defense)}" +
                if (shadow) "\nShadow ATK equivalent ${fmt(first.second.attack * 1.2)}-${fmt(last.second.attack * 1.2)}" else "")
        }
        appendLine()
        if (optimal == null) appendLine("No eligible league build")
        else {
            appendLine("League optimum: level ${fmt(optimal.level)} | IV rank #${optimal.rank}")
            appendLine("PvP IV percentile: ${percent(ivPercentile!!)}%")
            appendLine("${percent(percentBest)}% of this form's best stat product")
            appendLine(optimal.stats.describe(shadow))
        }
        appendLine(feasibility)
        appendLine()
        appendLine("Fully powered up: effective level ${fmt(maximumLevel)}")
        append(maximum.describe(shadow))
    }
}

data class LevelScenario(val activeBuddy: Boolean, val baseLevels: List<Double>)

data class Assessment(
    val baseLevels: List<Double>,
    val levelNote: String,
    val evolutions: List<EvolutionProjection>,
    val levelScenarios: List<LevelScenario> = emptyList(),
) {
    fun summary(shadow: Boolean): String = buildString {
        appendLine(levelNote)
        for (alternative in levelAlternatives(levelScenarios)) appendLine(alternative)
        evolutions.forEachIndexed { index, entry ->
            appendLine("\n${entry.pokemon.name}\n${entry.summary(shadow, isEvolution = index > 0)}")
        }
        if (shadow) appendLine("\nShadow: 20% more damage dealt and received. ATK equivalent is not the real stat or a move damage prediction.")
        append("\nIV rank compares spreads within each form, not overall species strength. Evolution/cup requirements still apply; no purification is modeled.")
    }
}

fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)
fun percent(value: Double): String = String.format(Locale.US, "%.2f", value)
fun Stats.describe(shadow: Boolean): String = "CP $cp | HP $hp\nATK ${fmt(attack)} | DEF ${fmt(defense)}" +
    if (shadow) "\nShadow ATK equivalent ${fmt(attack * 1.2)} (damage x1.2)" else ""

object Projections {
    fun calculate(repository: PokemonRepository, input: AssessmentInput): Assessment =
        calculate(repository.calculations, repository.descendants(input.pokemon), input)

    internal fun calculate(calc: Calculations, targets: List<Pokemon>, input: AssessmentInput): Assessment {
        require(targets.size <= MAX_EVOLUTION_DESCENDANTS) { "Evolution target limit exceeded" }
        require(input.maxBaseLevel == 40 || input.maxBaseLevel == 50)
        require(input.leagueCap in listOf(500, 1500, 2500))
        val levels = if (input.baseLevel != null) {
            require(input.baseLevel in 1.0..50.0 && input.baseLevel * 2 % 1 == 0.0) { "Base level must be 1-50 in half-level steps" }
            listOf(input.baseLevel)
        } else if (input.observedCp != null && input.observedHp != null) {
            calc.inferLevels(input.pokemon, input.ivs, input.observedCp, input.observedHp, input.activeBuddy)
        } else emptyList()
        val note = when {
            input.baseLevel != null -> "Using confirmed base level ${fmt(input.baseLevel)}${if (input.activeBuddy) " (+1 active buddy)" else ""}."
            input.observedCp != null && input.observedHp != null && levels.isEmpty() -> "CP/HP do not match these IVs/form and buddy status. Check readings; current level remains unknown."
            levels.size == 1 -> "Inferred base level ${fmt(levels[0])}${if (input.activeBuddy) " (+1 active buddy)" else ""}."
            levels.size > 1 -> "${levels.size} possible base levels. Enter a confirmed level to resolve rounding ambiguity."
            else -> "Current level unknown. Enter observed CP and maximum HP, or a confirmed base level."
        }
        val maxLevel = input.maxBaseLevel.toDouble() + if (input.allowBestBuddy) 1 else 0
        val projections = targets.map { target ->
            val ranks = calc.rankIVs(target, input.leagueCap, maxLevel)
            val optimal = ranks.firstOrNull { it.ivs == input.ivs }
            val current = levels.map { base ->
                val level = base + if (input.activeBuddy) 1 else 0
                level to calc.stats(target, input.ivs, level)
            }
            val fit = feasibility(calc, target, input, levels, optimal)
            EvolutionProjection(target, current, optimal,
                if (optimal != null) optimal.stats.statProduct / ranks.first().stats.statProduct * 100 else 0.0,
                maxLevel, calc.stats(target, input.ivs, maxLevel), fit, ranks.size)
        }
        return Assessment(levels, note, projections,
            if (levels.isEmpty()) emptyList() else listOf(LevelScenario(input.activeBuddy, levels)))
    }

    internal fun feasibility(calc: Calculations, target: Pokemon, input: AssessmentInput, levels: List<Double>, optimal: RankedIVs?): String {
        if (levels.isEmpty()) return "Theoretical potential: current level unknown."
        if (optimal == null) return "Cannot fit this league."
        val possible = levels.map { base ->
            val minEffectiveLevel = base // A Best Buddy can be unequipped; base level cannot be reduced.
            when {
                calc.stats(target, input.ivs, minEffectiveLevel).cp > input.leagueCap -> "Already over the CP limit after evolution; cannot power down."
                base > input.maxBaseLevel -> "Current base level exceeds the selected power-up cap."
                minEffectiveLevel > optimal.level -> "Cannot reach the theoretical optimum without powering down."
                else -> "League optimum is reachable by powering up${if (input.activeBuddy && calc.stats(target, input.ivs, base + 1).cp > input.leagueCap) "; unequip the active buddy boost first" else ""}."
            }
        }.distinct()
        return if (possible.size == 1) possible.first() else "Feasibility depends on the current level; resolve the level ambiguity first."
    }
}
