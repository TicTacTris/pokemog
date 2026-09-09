package dev.pokemog.android

import android.content.Context
import android.app.ActivityManager
import org.json.JSONArray
import org.json.JSONObject

class PokemonRepository private constructor(
    entries: List<Pokemon>, cpms: List<Double>, val identity: DataIdentity, val license: String, lowRam: Boolean,
) {
    constructor(context: Context) : this(
        readPokemonCatalog(context.assetText("pokemon.json"), context.assetText("evolution-metadata.json")),
        JSONArray(context.assetText("cp-multipliers.json")).let { array -> List(array.length()) { array.getDouble(it) } },
        BUNDLED_DATA, context.assetText("PVPoke-LICENSE.txt"),
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
    )
    internal constructor(pack: ValidatedPack, lowRam: Boolean = false) : this(
        pack.pokemon, pack.cpms, pack.manifest.identity, pack.license, lowRam,
    )
    val pokemon: List<Pokemon> = java.util.Collections.unmodifiableList(entries.map {
        it.copy(evolutions = java.util.Collections.unmodifiableList(it.evolutions.toList()))
    })
    val calculations = Calculations(cpms, if (lowRam) 8 else 32)
    internal val nameIndex by lazy { PokemonNameIndex(pokemon) }
    private val byId = pokemon.associateBy { it.id }

    init {
        require(byId.size == pokemon.size) { "Duplicate Pokemon IDs" }
        require(pokemon.all { p -> p.evolutions.all { it in byId } &&
            (p.shadowId == null || p.shadowId in byId) && (p.normalId == null || p.normalId in byId)
        }) { "Unresolved Pokemon reference" }
    }

    /** Breadth-first forward traversal; variant links are not evolution edges. */
    fun descendants(p: Pokemon): List<Pokemon> {
        val result = mutableListOf<Pokemon>()
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<Pokemon>()
        pending.add(p)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current.id)) continue
            require(result.size < MAX_EVOLUTION_DESCENDANTS && current.evolutions.size <= MAX_EVOLUTION_EDGES) { "Evolution target limit exceeded" }
            result.add(current)
            current.evolutions.forEach { id -> byId[id]?.let { pending.add(it) } }
        }
        return result
    }
}

private fun Context.assetText(name: String) = assets.open(name).bufferedReader().use { it.readText() }

internal fun readPokemonCatalog(data: String, report: String): List<Pokemon> {
    val entries = JSONArray(data)
    val metadata = JSONObject(report)
    val missing = metadata.getJSONArray("missingFamilyMetadata").let { values ->
        (0 until values.length()).map { values.getString(it) }.toSet()
    }
    val terminal = metadata.getJSONArray("terminalWithFamilyMetadata").let { values ->
        (0 until values.length()).map { values.getString(it) }.toSet()
    }
    return List(entries.length()) { index ->
        val entry = entries.getJSONObject(index)
        val evolutions = entry.getJSONArray("evolutions")
        Pokemon(
            id = entry.getString("id"), name = entry.getString("name"),
            attack = entry.getInt("attack"), defense = entry.getInt("defense"), stamina = entry.getInt("stamina"),
            evolutions = List(evolutions.length()) { evolutions.getString(it) },
            shadowId = if (entry.has("shadowId")) entry.getString("shadowId") else null,
            normalId = if (entry.has("normalId")) entry.getString("normalId") else null,
            hasEvolutionData = entry.getString("id") !in missing &&
                (evolutions.length() > 0 || entry.getString("id") in terminal),
        )
    }
}
