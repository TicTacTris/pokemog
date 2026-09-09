import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { assessPokemon, canonicalPokemon, catalog, levelAlternatives, type AssessmentInput, type Species } from './projections'
import { rankIVs, stats } from './calculations'
import metadata from '../data/evolutions.json'
import report from '../data/evolution-metadata.json'

const species = (id: string) => catalog.find(p => p.id === id)!
const ivs = { attack: 15, defense: 13, stamina: 13 }
const input = (id: string, extra: Partial<AssessmentInput> = {}): AssessmentInput => ({ pokemon: species(id), ivs, shadow: false, activeBuddy: 'unknown', maxBaseLevel: 50, allowBestBuddy: false, ...extra })
const tiny: Species = { id: 'tiny', name: 'Tiny', attack: 1, defense: 1, stamina: 1, evolutions: [], hasEvolutionData: false }

describe('durable catalog metadata', () => {
  it('covers every base ID and distinguishes missing metadata from known terminal records', () => {
    expect(Object.keys(metadata)).toHaveLength(catalog.length)
    expect(catalog.filter(p => !p.hasEvolutionData).map(p => p.id)).toEqual(report.missingFamilyMetadata)
    expect(catalog.filter(p => p.hasEvolutionData && !p.evolutions.length).map(p => p.id)).toEqual(expect.arrayContaining(report.terminalWithFamilyMetadata))
    for (const p of catalog) for (const id of p.evolutions) expect(species(id)).toBeDefined()
    expect(species('rockruff').evolutions).toContain('lycanroc_dusk')
    expect(species('scyther_shadow').evolutions).not.toContain('kleavor_shadow')
  })
  it('keeps branches and regional forms rather than reverse ancestors', () => {
    expect(assessPokemon(input('eevee')).leagues[0].evolutions).toHaveLength(9)
    expect(assessPokemon(input('cubone')).leagues[0].evolutions.map(p => p.pokemon.id)).toEqual(['cubone', 'marowak', 'marowak_alolan'])
    expect(assessPokemon(input('slowpoke_galarian')).leagues[0].evolutions.map(p => p.pokemon.id)).toEqual(['slowpoke_galarian', 'slowbro_galarian', 'slowking_galarian'])
    expect(assessPokemon(input('venusaur')).leagues[0].evolutions).toHaveLength(1)
  })
})

describe('level scenarios', () => {
  it('preserves the exact Scatterbug Buddy alternatives and Giratina effective level', () => {
    const a = assessPokemon(input('scatterbug', { observedCp: 206, observedHp: 66 }))
    expect(a.effectiveLevels).toEqual([15])
    expect(a.scenarios).toEqual([{ activeBuddy: false, baseLevels: [15] }, { activeBuddy: true, baseLevels: [14] }])
    expect(levelAlternatives(a.scenarios)).toEqual(['Without active Buddy boost: base level 15', 'With active Buddy boost (+1): base level 14'])
    expect(assessPokemon(input('giratina_altered', { ivs: { attack: 8, defense: 11, stamina: 6 }, observedCp: 1820, observedHp: 173 })).effectiveLevels).toEqual([20])
  })
  it('matches all existing Android fixed-IV inference fixtures', () => {
    const rows = readFileSync(new URL('../../android/app/src/test/resources/inference-parity.tsv', import.meta.url), 'utf8').trim().split('\n')
    for (const row of rows) {
      const [id, attack, defense, stamina, cp, hp, buddy, levels] = row.split('\t')
      const a = assessPokemon(input('bulbasaur', { pokemon: id === 'tiny' ? tiny : species(id), ivs: { attack: +attack, defense: +defense, stamina: +stamina }, observedCp: +cp, observedHp: +hp, activeBuddy: buddy === 'true' ? 'on' : 'off' }))
      expect(a.scenarios.flatMap(s => s.baseLevels)).toEqual(levels === '-' ? [] : levels.split(',').map(Number))
    }
  })
  it('lists actual plateau values and handles partial, impossible and authoritative readings', () => {
    expect(levelAlternatives([{ activeBuddy: false, baseLevels: [14, 15.5] }])).toEqual(['Without active Buddy boost: base levels 14, 15.5'])
    const a = assessPokemon(input('bulbasaur', { pokemon: tiny, ivs: { attack: 0, defense: 0, stamina: 0 }, observedCp: 10, observedHp: 10 }))
    expect(a.scenarios.map(s => s.baseLevels.length)).toEqual([99, 99])
    for (const extra of [{ observedCp: 206 }, { observedHp: 66 }, { observedCp: 999999, observedHp: 999999 }]) expect(assessPokemon(input('scatterbug', extra)).scenarios).toEqual([])
    const confirmed = assessPokemon(input('scatterbug', { baseLevel: 14, observedCp: 206, observedHp: 66 }))
    expect(confirmed.scenarios).toEqual([{ activeBuddy: true, baseLevels: [14] }])
    const conflict = assessPokemon(input('scatterbug', { baseLevel: 20, observedCp: 206, observedHp: 66 }))
    expect(conflict.scenarios).toHaveLength(2)
    expect(conflict.levelMessage).toContain('Warning')
    expect(conflict.leagues[0].evolutions[0].feasibility.code).toBe('uncertain')
  })
  it('rejects nonfinite, fractional and out-of-range inputs, including partial readings', () => {
    for (const n of [NaN, Infinity, -1, 1.25, 51]) expect(() => assessPokemon(input('bulbasaur', { baseLevel: n }))).toThrow(RangeError)
    for (const n of [NaN, Infinity, 9, 10.5]) for (const field of ['observedCp', 'observedHp']) expect(() => assessPokemon(input('bulbasaur', { [field]: n }))).toThrow(RangeError)
    expect(() => assessPokemon(input('bulbasaur', { ivs: { ...ivs, attack: NaN } }))).toThrow(RangeError)
    expect(() => assessPokemon(input('bulbasaur', { maxBaseLevel: 41 as 40 }))).toThrow(RangeError)
    expect(() => assessPokemon(input('bulbasaur', { baseLevel: '15' as unknown as number }))).toThrow(RangeError)
  })
})

describe('league and Shadow projections', () => {
  it('keeps all calculations canonical while using exact linked Shadow names', () => {
    const normal = assessPokemon(input('bulbasaur', { baseLevel: 20 }))
    const shadow = assessPokemon(input('bulbasaur_shadow', { shadow: true, baseLevel: 20 }))
    expect(canonicalPokemon(species('bulbasaur_shadow'))).toBe(species('bulbasaur'))
    expect(shadow.pokemon).toBe(species('bulbasaur_shadow'))
    for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) {
      const a = normal.leagues[i].evolutions[j], b = shadow.leagues[i].evolutions[j]
      expect(b).toEqual({ ...a, pokemon: species(a.pokemon.shadowId!) })
    }
    const unsupported = assessPokemon(input('scyther', { shadow: true })).leagues[0].evolutions
    expect(unsupported.find(p => p.pokemon.id === 'kleavor')).toMatchObject({ shadowUnverified: true, isEvolution: true })
    const shedinja = assessPokemon(input('shedinja', { shadow: true, baseLevel: 50 })).leagues[0].evolutions[0]
    expect(shedinja.maximum.stats.hp).toBe(10)
    expect(shedinja.shadowUnverified).toBe(true)
    expect(assessPokemon(input('bulbasaur', { pokemon: { ...species('bulbasaur'), attack: 119 }, shadow: true })).pokemon.id).toBe('bulbasaur')
  })
  it('reports actual-IV optimum, distinct percentile, theoretical status and maximum schema', () => {
    const a = assessPokemon(input('azumarill'))
    expect(a.leagues.map(l => l.cap)).toEqual([1500, 2500, 500])
    const p = a.leagues[0].evolutions[0], ranks = rankIVs(species('azumarill'), 1500, 50)
    expect(p.optimal?.ivs).toEqual(ivs)
    expect(p.percentBest).toBe(100 * p.optimal!.statProduct / ranks[0].statProduct)
    expect(p.ivPercentile).toBe(100 * (ranks.length - p.optimal!.rank) / (ranks.length - 1))
    expect(p.percentBest).not.toBe(p.ivPercentile)
    expect(p.feasibility.code).toBe('unknown')
    expect(p.maximum).toEqual({ level: 50, stats: stats(species('azumarill'), ivs, 50) })
    expect(assessPokemon(input('azumarill', { maxBaseLevel: 40, allowBestBuddy: true })).leagues[0].evolutions[0].maximum.level).toBe(41)
    const large = assessPokemon(input('bulbasaur', { pokemon: { ...tiny, attack: 10000, defense: 10000, stamina: 10000 } })).leagues[0].evolutions[0]
    expect(large).toMatchObject({ optimal: null, percentBest: null, ivPercentile: null, eligibleSpreads: 0, feasibility: { code: 'no-build' } })
  })
  it('never promises powering down and preserves unknown-Buddy conflicts', () => {
    expect(assessPokemon(input('mewtwo', { baseLevel: 50 })).leagues[0].evolutions[0].feasibility.code).toBe('over-cap')
    expect(assessPokemon(input('scatterbug', { baseLevel: 50, maxBaseLevel: 40 })).leagues[0].evolutions[0].feasibility.code).toBe('above-level-cap')
    const p = species('azumarill'), optimum = rankIVs(p, 1500, 50).find(r => r.ivs.attack === ivs.attack && r.ivs.defense === ivs.defense && r.ivs.stamina === ivs.stamina)!
    const a = assessPokemon(input(p.id, { baseLevel: optimum.level }))
    expect(a.leagues[0].evolutions[0].feasibility.code).toBe('uncertain')
    expect(assessPokemon(input(p.id, { baseLevel: optimum.level, activeBuddy: 'on' })).leagues[0].evolutions[0].feasibility.message).toContain('unequip')
  })
})
