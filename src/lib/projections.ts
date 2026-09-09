import base from '../data/pokemon.json'
import metadata from '../data/evolutions.json'
import { rankIVs, stats, type Pokemon, type IVs, type Stats, type RankedIVs } from './calculations'

export type Species = Pokemon & { evolutions: readonly string[]; shadowId?: string; normalId?: string; hasEvolutionData: boolean }
export const catalog: Species[] = base.map(p => ({ ...p, ...(metadata as Record<string, Omit<Species, keyof Pokemon>>)[p.id] }))
const byId = new Map(catalog.map(p => [p.id, p]))
export type LevelScenario = { activeBuddy: boolean; baseLevels: number[] }
export type AssessmentInput = { pokemon: Species; ivs: IVs; shadow: boolean; observedCp?: number; observedHp?: number; baseLevel?: number; activeBuddy: 'unknown' | 'off' | 'on'; maxBaseLevel: 40 | 50; allowBestBuddy: boolean }
export type EvolutionProjection = {
  pokemon: Species; isEvolution: boolean; current: { level: number; stats: Stats }[]; optimal: RankedIVs | null
  percentBest: number | null; ivPercentile: number | null; eligibleSpreads: number
  maximum: { level: number; stats: Stats }
  feasibility: { code: 'unknown' | 'reachable' | 'over-cap' | 'above-level-cap' | 'uncertain' | 'no-build'; message: string }
  shadowUnverified: boolean
}
export type LeagueAssessment = { cap: 500 | 1500 | 2500; name: string; evolutions: EvolutionProjection[] }
export type Assessment = { pokemon: Species; ivs: IVs; shadow: boolean; scenarios: LevelScenario[]; effectiveLevels: number[]; levelMessage: string; leagues: LeagueAssessment[] }

export function canonicalPokemon(p: Species): Species {
  const candidates = [...new Map([...(p.normalId && byId.has(p.normalId) ? [byId.get(p.normalId)!] : []), ...catalog.filter(n => n.shadowId === p.id)].map(n => [n.id, n])).values()]
  return candidates.length === 1 ? candidates[0] : p
}

function shadowCounterpart(p: Species): Species | undefined {
  const candidates = [...new Map([...(p.shadowId && byId.has(p.shadowId) ? [byId.get(p.shadowId)!] : []), ...catalog.filter(n => n.normalId === p.id)].map(n => [n.id, n])).values()]
  const s = candidates.length === 1 ? candidates[0] : undefined
  return s && (!s.normalId || s.normalId === p.id) && s.attack === p.attack && s.defense === p.defense && s.stamina === p.stamina ? s : undefined
}

export function levelAlternatives(scenarios: LevelScenario[]): string[] {
  return scenarios.map(s => `${s.activeBuddy ? 'With active Buddy boost (+1)' : 'Without active Buddy boost'}: base level${s.baseLevels.length === 1 ? '' : 's'} ${s.baseLevels.join(', ')}`)
}

export function assessPokemon(input: AssessmentInput): Assessment {
  stats(input.pokemon, input.ivs, 1)
  if (!['unknown', 'off', 'on'].includes(input.activeBuddy) || ![40, 50].includes(input.maxBaseLevel) || typeof input.shadow !== 'boolean' || typeof input.allowBestBuddy !== 'boolean') throw new RangeError('Invalid assessment settings')
  for (const value of [input.observedCp, input.observedHp]) if (value !== undefined && (!Number.isSafeInteger(value) || value < 10)) throw new RangeError('CP and HP must be integers of at least 10')
  if (input.baseLevel !== undefined && (typeof input.baseLevel !== 'number' || !Number.isInteger(input.baseLevel * 2) || input.baseLevel < 1 || input.baseLevel > 50)) throw new RangeError('Base level must be 1-50 in half-level steps')
  const selected = canonicalPokemon(input.pokemon)
  const buddies = input.activeBuddy === 'unknown' ? [false, true] : [input.activeBuddy === 'on']
  const complete = input.observedCp !== undefined && input.observedHp !== undefined
  // One calculation per effective half-level, independent of how many Buddy scenarios share it.
  const observed = new Map<number, Stats>()
  const matches = (level: number) => {
    let s = observed.get(level)
    if (!s) { s = stats(selected, input.ivs, level); observed.set(level, s) }
    return (input.observedCp === undefined || s.cp === input.observedCp) && (input.observedHp === undefined || s.hp === input.observedHp)
  }
  let scenarios: LevelScenario[] = buddies.map(activeBuddy => ({ activeBuddy, baseLevels: input.baseLevel !== undefined ? [input.baseLevel] : complete ? Array.from({ length: 99 }, (_, i) => 1 + i / 2).filter(level => matches(level + Number(activeBuddy))) : [] })).filter(s => s.baseLevels.length)
  let contradiction = false
  if (input.baseLevel !== undefined && (input.observedCp !== undefined || input.observedHp !== undefined)) {
    const consistent = scenarios.filter(s => matches(input.baseLevel! + Number(s.activeBuddy)))
    if (consistent.length) scenarios = consistent
    else contradiction = true
  }
  const effectiveLevels = [...new Set(scenarios.flatMap(s => s.baseLevels.map(l => l + Number(s.activeBuddy))))].sort((a, b) => a - b)
  const levelMessage = (contradiction ? 'Warning: observed CP/HP contradict the confirmed base level, IVs, form or Buddy status. Using the confirmed base level; reachability is uncertain.' : effectiveLevels.length ? `${input.baseLevel !== undefined ? 'Confirmed base level' : 'Observed effective level'} ${input.baseLevel ?? effectiveLevels.join(', ')}. ${scenarios.length > 1 ? 'Active Buddy status is unknown; reachability may be ambiguous.' : scenarios[0].activeBuddy ? 'Active Buddy boost (+1).' : 'No active Buddy boost.'}` : 'Current level unknown: CP/HP are missing or inconsistent with this form and IVs. Only theoretical league potential is shown.') + ` League potential uses maximum base level ${input.maxBaseLevel}, ${input.allowBestBuddy ? 'with' : 'without'} a future Buddy boost.`
  const targets: Species[] = [], pending = [selected], seen = new Set<string>()
  while (pending.length) {
    const p = pending.shift()!
    if (seen.has(p.id)) continue
    seen.add(p.id)
    targets.push(p)
    for (const id of p.evolutions) {
      const next = byId.get(id)
      if (next && canonicalPokemon(next).id === next.id) pending.push(next)
    }
  }
  const shadows = new Map(targets.map(p => [p.id, input.shadow ? shadowCounterpart(p) : undefined]))
  const verified = new Set(shadows.get(selected.id) ? [selected.id] : [])
  let changed = true
  while (input.shadow && changed) {
    changed = false
    for (const p of targets) for (const t of targets) {
      if (verified.has(p.id) && !verified.has(t.id) && p.evolutions.includes(t.id) && shadows.get(p.id)?.evolutions.includes(shadows.get(t.id)?.id ?? '')) { verified.add(t.id); changed = true }
    }
  }
  const max = input.maxBaseLevel + Number(input.allowBestBuddy)
  const leagues = ([{ cap: 1500, name: 'Great League' }, { cap: 2500, name: 'Ultra League' }, { cap: 500, name: 'Little League' }] as const).map(({ cap, name }): LeagueAssessment => ({ cap, name, evolutions: targets.map((p): EvolutionProjection => {
    const ranks = rankIVs(p, cap, max)
    const optimal = ranks.find(r => r.ivs.attack === input.ivs.attack && r.ivs.defense === input.ivs.defense && r.ivs.stamina === input.ivs.stamina) ?? null
    const fits: EvolutionProjection['feasibility'][] = scenarios.flatMap(s => s.baseLevels.map(baseLevel => {
      if (stats(p, input.ivs, baseLevel).cp > cap) return { code: 'over-cap', message: 'Already over the CP limit after evolution; cannot power down.' }
      if (baseLevel > input.maxBaseLevel) return { code: 'above-level-cap', message: 'Current base level exceeds the selected power-up cap.' }
      if (optimal && baseLevel > optimal.level) return { code: 'over-cap', message: 'Cannot reach the theoretical optimum without powering down.' }
      return { code: 'reachable', message: `League optimum is reachable by powering up${s.activeBuddy && stats(p, input.ivs, baseLevel + 1).cp > cap ? '; unequip the active Buddy boost first' : ''}.` }
    }))
    const distinct = new Set(fits.map(f => f.message))
    const feasibility: EvolutionProjection['feasibility'] = !optimal ? { code: 'no-build', message: 'No eligible league build.' } : contradiction || distinct.size > 1 ? { code: 'uncertain', message: 'Theoretical optimum: reachability depends on the base level / active Buddy status or contradictory readings; it is not guaranteed.' } : fits[0] ?? { code: 'unknown', message: 'Theoretical potential only: current level unknown.' }
    return { pokemon: shadows.get(p.id) ?? p, isEvolution: p.id !== selected.id, current: effectiveLevels.map(level => ({ level, stats: stats(p, input.ivs, level) })), optimal,
      percentBest: optimal ? 100 * optimal.statProduct / ranks[0].statProduct : null,
      ivPercentile: optimal ? ranks.length === 1 ? 100 : 100 * (ranks.length - optimal.rank) / (ranks.length - 1) : null,
      eligibleSpreads: ranks.length, maximum: { level: max, stats: stats(p, input.ivs, max) }, feasibility, shadowUnverified: input.shadow && !verified.has(p.id) }
  }) }))
  return { pokemon: shadows.get(selected.id) ?? selected, ivs: { ...input.ivs }, shadow: input.shadow, scenarios, effectiveLevels, levelMessage, leagues }
}
