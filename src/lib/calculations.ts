import cpms from '../data/cp-multipliers.json'

export type Pokemon = {
  id: string
  name: string
  form?: string
  attack: number
  defense: number
  stamina: number
}
export type IVs = { attack: number; defense: number; stamina: number }
export type Stats = { cp: number; hp: number; attack: number; defense: number; statProduct: number }
export type RankedIVs = Stats & { ivs: IVs; level: number; rank: number }
export type IVMatch = { ivs: IVs; level: number; cp: number; hp: number }

function validatePokemon(pokemon: Pokemon) {
  if (!pokemon || typeof pokemon.id !== 'string' || !pokemon.id.trim() ||
    typeof pokemon.name !== 'string' || !pokemon.name.trim() ||
    (pokemon.form !== undefined && typeof pokemon.form !== 'string') ||
    [pokemon.attack, pokemon.defense, pokemon.stamina].some(n => !Number.isSafeInteger(n) || n <= 0)) {
    throw new RangeError('Pokemon must have an id, name, and positive integer base stats')
  }
}

function validateIVs(ivs: IVs) {
  if (!ivs || [ivs.attack, ivs.defense, ivs.stamina].some(n => !Number.isInteger(n) || n < 0 || n > 15)) {
    throw new RangeError('IVs must be integers from 0 to 15')
  }
}

function validateLevel(level: number) {
  if (typeof level !== 'number' || !Number.isInteger(level * 2) || level < 1 || level > 51) {
    throw new RangeError('Level must be from 1 to 51 in half-level increments')
  }
}

function validateDisplayStat(value: number, name: string) {
  if (!Number.isSafeInteger(value) || value < 10) throw new RangeError(`${name} must be an integer of at least 10`)
}

function calculate(pokemon: Pokemon, ivs: IVs, level: number): Stats {
  const cpm = cpms[(level - 1) * 2]
  const baseAttack = pokemon.attack + ivs.attack
  const baseDefense = pokemon.defense + ivs.defense
  const baseStamina = pokemon.stamina + ivs.stamina
  const attack = baseAttack * cpm
  const defense = baseDefense * cpm
  // Shedinja has fixed HP, independent of its stamina IV and level.
  const hp = /^shedinja(?:_|$)/.test(pokemon.id) ? 10 : Math.max(10, Math.floor(baseStamina * cpm))
  const cp = Math.max(10, Math.floor(baseAttack * Math.sqrt(baseDefense) * Math.sqrt(baseStamina) * cpm ** 2 / 10))
  return { cp, hp, attack, defense, statProduct: attack * defense * hp }
}

export function stats(pokemon: Pokemon, ivs: IVs, level: number): Stats {
  validatePokemon(pokemon)
  validateIVs(ivs)
  validateLevel(level)
  return calculate(pokemon, ivs, level)
}

export function ivPercent(ivs: IVs): number {
  validateIVs(ivs)
  return (ivs.attack + ivs.defense + ivs.stamina) / 45 * 100
}

const rankingCache = new Map<string, RankedIVs[]>()

export function rankIVs(pokemon: Pokemon, cpCap: number, maxLevel: number): RankedIVs[] {
  validatePokemon(pokemon)
  validateDisplayStat(cpCap, 'CP cap')
  validateLevel(maxLevel)
  const key = JSON.stringify([pokemon.id, pokemon.attack, pokemon.defense, pokemon.stamina, cpCap, maxLevel])
  const cached = rankingCache.get(key)
  if (cached) {
    rankingCache.delete(key)
    rankingCache.set(key, cached)
    return cached
  }
  const results: RankedIVs[] = []
  for (let attack = 0; attack <= 15; attack++) {
    for (let defense = 0; defense <= 15; defense++) {
      for (let stamina = 0; stamina <= 15; stamina++) {
        const ivs = { attack, defense, stamina }
        // CP is monotonic; find the highest eligible half-level without skipping CP plateaus.
        let low = 0
        let high = (maxLevel - 1) * 2
        let eligible = -1
        while (low <= high) {
          const mid = Math.floor((low + high) / 2)
          if (calculate(pokemon, ivs, 1 + mid / 2).cp <= cpCap) {
            eligible = mid
            low = mid + 1
          } else high = mid - 1
        }
        if (eligible < 0) continue
        const level = 1 + eligible / 2
        results.push({ ivs, level, ...calculate(pokemon, ivs, level), rank: 0 })
      }
    }
  }
  results.sort((a, b) => b.statProduct - a.statProduct || a.ivs.attack - b.ivs.attack || a.ivs.defense - b.ivs.defense || a.ivs.stamina - b.ivs.stamina)
  results.forEach((result, index) => {
    result.rank = index > 0 && result.statProduct === results[index - 1].statProduct ? results[index - 1].rank : index + 1
  })
  // Keep the shipped array type, but prevent callers from poisoning shared rankings.
  results.forEach(result => { Object.freeze(result.ivs); Object.freeze(result) })
  Object.freeze(results)
  rankingCache.set(key, results)
  if (rankingCache.size > 32) rankingCache.delete(rankingCache.keys().next().value!)
  return results
}

export function findIVs(pokemon: Pokemon, cp: number, hp: number, minLevel: number, maxLevel: number): IVMatch[] {
  validatePokemon(pokemon)
  validateDisplayStat(cp, 'CP')
  validateDisplayStat(hp, 'HP')
  validateLevel(minLevel)
  validateLevel(maxLevel)
  if (minLevel > maxLevel) throw new RangeError('Minimum level cannot exceed maximum level')
  const results: IVMatch[] = []
  for (let level = minLevel; level <= maxLevel; level += 0.5) {
    for (let attack = 0; attack <= 15; attack++) {
      for (let defense = 0; defense <= 15; defense++) {
        for (let stamina = 0; stamina <= 15; stamina++) {
          const ivs = { attack, defense, stamina }
          const result = calculate(pokemon, ivs, level)
          if (result.cp === cp && result.hp === hp) results.push({ ivs, level, cp, hp })
        }
      }
    }
  }
  return results
}
