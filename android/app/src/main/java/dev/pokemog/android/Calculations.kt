package dev.pokemog.android

import kotlin.math.floor
import kotlin.math.sqrt
import java.util.Collections

data class Pokemon(
    val id: String,
    val name: String,
    val attack: Int,
    val defense: Int,
    val stamina: Int,
    val evolutions: List<String> = emptyList(),
    val shadowId: String? = null,
    val normalId: String? = null,
    val hasEvolutionData: Boolean = false,
) {
    val hasFixedHp: Boolean get() = id == "shedinja" || id.startsWith("shedinja_")
}

data class IVs(val attack: Int, val defense: Int, val stamina: Int)
data class Stats(val cp: Int, val hp: Int, val attack: Double, val defense: Double, val statProduct: Double)
data class RankedIVs(val ivs: IVs, val level: Double, val stats: Stats, val rank: Int)

class Calculations(cpms: List<Double>, private val maxCachedRankings: Int = 32) {
    private val cpms = cpms.toList()
    private val cpmSquares = DoubleArray(cpms.size) { this.cpms[it] * this.cpms[it] }
    private data class RankingKey(val id: String, val attack: Int, val defense: Int, val stamina: Int,
        val cpCap: Int, val maxLevel: Double)
    // Owned by this calculator (and therefore its repository), never a process-wide catalog cache.
    private val rankings = object : LinkedHashMap<RankingKey, List<RankedIVs>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RankingKey, List<RankedIVs>>): Boolean = size > maxCachedRankings
    }

    init {
        require(maxCachedRankings in 1..32) { "Ranking cache capacity must be 1-32" }
        require(this.cpms.size == 101 && this.cpms.withIndex().all { (i, n) ->
            n.isFinite() && n > 0 && n < 1 && (i == 0 || n > this.cpms[i - 1])
        }) { "Expected increasing CP multipliers for levels 1 through 51" }
    }

    private fun validatePokemon(p: Pokemon) {
        require(p.id.isNotBlank() && p.name.isNotBlank() && p.attack > 0 && p.defense > 0 && p.stamina > 0) {
            "Pokemon must have an id, name, and positive integer base stats"
        }
    }

    private fun validateIVs(ivs: IVs) {
        require(ivs.attack in 0..15 && ivs.defense in 0..15 && ivs.stamina in 0..15) {
            "IVs must be integers from 0 to 15"
        }
    }

    private fun validateLevel(level: Double) {
        require(level.isFinite() && level in 1.0..51.0 && level * 2 == floor(level * 2)) {
            "Level must be from 1 to 51 in half-level increments"
        }
    }

    private fun calculate(p: Pokemon, ivs: IVs, level: Double): Stats {
        val cpm = cpms[((level - 1) * 2).toInt()]
        val baseAttack = p.attack.toDouble() + ivs.attack
        val baseDefense = p.defense.toDouble() + ivs.defense
        val baseStamina = p.stamina.toDouble() + ivs.stamina
        val attack = baseAttack * cpm
        val defense = baseDefense * cpm
        val hp = if (p.hasFixedHp) 10
            else maxOf(10, floor(baseStamina * cpm).toInt())
        val cpValue = maxOf(10.0, floor(baseAttack * sqrt(baseDefense) * sqrt(baseStamina) * (cpm * cpm) / 10))
        require(cpValue <= Int.MAX_VALUE) { "CP exceeds the Stats integer range" }
        return Stats(cpValue.toInt(), hp, attack, defense, attack * defense * hp)
    }

    fun stats(p: Pokemon, ivs: IVs, level: Double): Stats {
        validatePokemon(p)
        validateIVs(ivs)
        validateLevel(level)
        return calculate(p, ivs, level)
    }

    @Synchronized
    fun rankIVs(p: Pokemon, cpCap: Int, maxLevel: Double): List<RankedIVs> {
        validatePokemon(p)
        require(cpCap >= 10) { "CP cap must be an integer of at least 10" }
        validateLevel(maxLevel)
        val key = RankingKey(p.id, p.attack, p.defense, p.stamina, cpCap, maxLevel)
        rankings[key]?.let { return it }
        val defenseRoots = DoubleArray(16) { sqrt(p.defense.toDouble() + it) }
        val staminaRoots = DoubleArray(16) { sqrt(p.stamina.toDouble() + it) }
        val maxIndex = ((maxLevel - 1) * 2).toInt()
        val results = ArrayList<RankedIVs>(4096)
        for (attack in 0..15) for (defense in 0..15) for (stamina in 0..15) {
            // Keep calculate's left-to-right multiplication and floor, including CP plateaus.
            // Shedinja only overrides HP, never the stamina used for CP.
            val cpBase = (p.attack.toDouble() + attack) * defenseRoots[defense] * staminaRoots[stamina]
            var low = 0
            var high = maxIndex
            var eligible = -1
            // Search through CP plateaus to the highest eligible half-level.
            while (low <= high) {
                val mid = (low + high) / 2
                val cpValue = maxOf(10.0, floor(cpBase * cpmSquares[mid] / 10))
                require(cpValue <= Int.MAX_VALUE) { "CP exceeds the Stats integer range" }
                if (cpValue.toInt() <= cpCap) {
                    eligible = mid
                    low = mid + 1
                } else high = mid - 1
            }
            if (eligible < 0) continue
            val ivs = IVs(attack, defense, stamina)
            val level = 1 + eligible / 2.0
            results.add(RankedIVs(ivs, level, calculate(p, ivs, level), 0))
        }
        results.sortWith(compareByDescending<RankedIVs> { it.stats.statProduct }
            .thenBy { it.ivs.attack }.thenBy { it.ivs.defense }.thenBy { it.ivs.stamina })
        for (i in results.indices) {
            val rank = if (i > 0 && results[i].stats.statProduct == results[i - 1].stats.statProduct)
                results[i - 1].rank else i + 1
            results[i] = results[i].copy(rank = rank)
        }
        return Collections.unmodifiableList(results).also { rankings[key] = it }
    }

    /** Returns base levels, not buddy-boosted effective levels. */
    fun inferLevels(p: Pokemon, ivs: IVs, cp: Int, hp: Int, activeBuddy: Boolean = false): List<Double> {
        validatePokemon(p)
        validateIVs(ivs)
        require(cp >= 10 && hp >= 10) { "Observed CP and HP must be integers of at least 10" }
        return (0..98).map { 1 + it / 2.0 }.filter { baseLevel ->
            val observed = calculate(p, ivs, baseLevel + if (activeBuddy) 1 else 0)
            observed.cp == cp && observed.hp == hp
        }
    }
}
