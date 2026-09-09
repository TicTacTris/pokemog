import { describe, expect, it } from 'vitest'
import pokemonData from '../data/pokemon.json'
import cpms from '../data/cp-multipliers.json'
import { findIVs, ivPercent, rankIVs, stats } from './calculations'
import type { Pokemon } from './calculations'
import { readFileSync } from 'node:fs'

const bulbasaur: Pokemon = { id: 'bulbasaur', name: 'Bulbasaur', attack: 118, defense: 111, stamina: 128 }
const perfect = { attack: 15, defense: 15, stamina: 15 }
const zero = { attack: 0, defense: 0, stamina: 0 }

describe('existing Android fixture parity and cache safety', () => {
  const rows = (name: string) => readFileSync(new URL(`../../android/app/src/test/resources/${name}.tsv`, import.meta.url), 'utf8').trim().split('\n').map(line => line.split('\t'))
  const fixtures = new Map(rows('pokemon').map(([id, name, attack, defense, stamina]) => [id, { id, name, attack: +attack, defense: +defense, stamina: +stamina }]))
  it('matches every existing stat fixture exactly, including CP/HP rounding', () => {
    for (const [id, ...values] of rows('stats-parity')) {
      const [attack, defense, stamina, level, cp, hp, atk, def, statProduct] = values.map(Number)
      expect(stats(fixtures.get(id)!, { attack, defense, stamina }, level)).toEqual({ cp, hp, attack: atk, defense: def, statProduct })
    }
  })
  it('matches every ranking row and eligible case count exactly', () => {
    const positions = new Map<string, number>()
    for (const [id, cap, max, count] of rows('rank-cases')) expect(rankIVs(fixtures.get(id)!, +cap, +max)).toHaveLength(+count)
    for (const [id, ...values] of rows('ranks-parity')) {
      const [cap, max, attack, defense, stamina, level, cp, hp, atk, def, statProduct, rank] = values.map(Number)
      const key = `${id}:${cap}:${max}`, index = positions.get(key) ?? 0
      expect(rankIVs(fixtures.get(id)!, cap, max)[index]).toEqual({ ivs: { attack, defense, stamina }, level, cp, hp, attack: atk, defense: def, statProduct, rank })
      positions.set(key, index + 1)
    }
  })
  it('freezes all cached layers and keys by stats, species rule, cap and maximum', () => {
    const ranks = rankIVs(bulbasaur, 500, 50)
    expect(rankIVs({ ...bulbasaur, name: 'Display only' }, 500, 50)).toBe(ranks)
    expect(() => ranks.pop()).toThrow()
    expect(() => { ranks[0].rank = 999 }).toThrow()
    expect(() => { ranks[0].ivs.attack = 999 }).toThrow()
    expect(rankIVs({ ...bulbasaur, attack: 119 }, 500, 50)).not.toBe(ranks)
    expect(rankIVs({ ...bulbasaur, id: 'shedinja' }, 500, 50)[0].hp).toBe(10)
    expect(rankIVs(bulbasaur, 1500, 50)).not.toBe(ranks)
    expect(rankIVs(bulbasaur, 500, 51)).not.toBe(ranks)
    for (let i = 0; i < 33; i++) rankIVs(bulbasaur, 2000 + i, 1)
    expect(rankIVs(bulbasaur, 500, 50)).not.toBe(ranks)
  })
})

describe('bundled data', () => {
  it('contains unique species and meaningful forms, but no Mega or Primal forms', () => {
    const data: Pokemon[] = pokemonData
    expect(data.length).toBeGreaterThan(1000)
    expect(new Set(data.map(p => p.id)).size).toBe(data.length)
    expect(data.find(p => p.id === 'bulbasaur')).toEqual(bulbasaur)
    expect(data.find(p => p.id === 'stunfisk_galarian')?.form).toBe('Galarian')
    expect(data.some(p => /(?:^|_)(mega|primal)(?:_|$)/.test(p.id))).toBe(false)
    for (const p of data) expect(() => stats(p, zero, 1)).not.toThrow()
  })

  it('uses the precise pinned multipliers including half-levels and level 51', () => {
    expect(cpms).toHaveLength(101)
    expect(cpms[1]).toBe(0.135137430784308)
    expect(cpms[78]).toBe(0.790300011634826)
    expect(cpms[98]).toBe(0.840300023555755)
    expect(cpms[100]).toBe(0.845300018787384)
    for (let i = 1; i < cpms.length; i++) expect(cpms[i]).toBeGreaterThan(cpms[i - 1])
  })
})

describe('stats and IV percentage', () => {
  it('calculates known perfect Bulbasaur stats at level 40 without rounding effective stats', () => {
    const result = stats(bulbasaur, perfect, 40)
    expect(result.cp).toBe(1115)
    expect(result.hp).toBe(113)
    expect(result.attack).toBe(133 * 0.790300011634826)
    expect(result.defense).toBe(126 * 0.790300011634826)
    expect(result.statProduct).toBe(result.attack * result.defense * result.hp)
    expect(stats(bulbasaur, perfect, 51).cp).toBe(1275)
  })

  it('applies CP/HP floors and Shedinja fixed HP', () => {
    const tiny = { ...bulbasaur, attack: 1, defense: 1, stamina: 1 }
    expect(stats(tiny, zero, 1)).toMatchObject({ cp: 10, hp: 10 })
    const shedinja = pokemonData.find(p => p.id === 'shedinja')!
    expect(stats(shedinja, perfect, 51).hp).toBe(10)
  })

  it('returns unrounded percentages on the 0-100 scale', () => {
    expect(ivPercent(zero)).toBe(0)
    expect(ivPercent(perfect)).toBe(100)
    expect(ivPercent({ ...zero, attack: 1 })).toBeCloseTo(100 / 45)
  })

  it.each([-1, 16, 1.5, NaN, Infinity])('rejects invalid IV %s', value => {
    expect(() => ivPercent({ ...zero, attack: value })).toThrow(RangeError)
    expect(() => stats(bulbasaur, { ...zero, stamina: value }, 1)).toThrow(RangeError)
  })

  it.each([0, 0.5, 1.25, 51.5, NaN, Infinity])('rejects invalid level %s', level => {
    expect(() => stats(bulbasaur, zero, level)).toThrow(RangeError)
    expect(() => rankIVs(bulbasaur, 1500, level)).toThrow(RangeError)
    expect(() => findIVs(bulbasaur, 10, 10, level, 51)).toThrow(RangeError)
  })

  it('rejects malformed Pokemon', () => {
    for (const attack of [0, -1, 1.5, NaN, Infinity]) {
      expect(() => stats({ ...bulbasaur, attack }, zero, 1)).toThrow(RangeError)
    }
    expect(() => stats({ ...bulbasaur, id: '' }, zero, 1)).toThrow(RangeError)
  })
})

describe('rankIVs', () => {
  it('evaluates all 4096 IV spreads at their highest eligible half-level', () => {
    const results = rankIVs(bulbasaur, 500, 50.5)
    expect(results).toHaveLength(4096)
    expect(new Set(results.map(r => JSON.stringify(r.ivs))).size).toBe(4096)
    for (const [i, r] of results.entries()) {
      expect(r.cp).toBeLessThanOrEqual(500)
      expect(r).toMatchObject(stats(bulbasaur, r.ivs, r.level))
      if (r.level < 50.5) expect(stats(bulbasaur, r.ivs, r.level + 0.5).cp).toBeGreaterThan(500)
      if (i) expect(r.statProduct).toBeLessThanOrEqual(results[i - 1].statProduct)
    }
  })

  it('honors the level cap and allows exact CP cap equality', () => {
    const results = rankIVs(bulbasaur, 1115, 40)
    expect(results.find(r => JSON.stringify(r.ivs) === JSON.stringify(perfect))).toMatchObject({ cp: 1115, level: 40 })
    const uncapped = rankIVs(bulbasaur, 10000, 51)
    // 14 and 15 stamina IV produce the same floored HP at level 51.
    expect(uncapped.find(r => JSON.stringify(r.ivs) === JSON.stringify(perfect))).toMatchObject({ level: 51, rank: 1 })
    expect(uncapped.every(r => r.level === 51)).toBe(true)
  })

  it('uses shared competition ranks for exact ties and deterministic ordering', () => {
    const results = rankIVs(bulbasaur, 10000, 1)
    let ties = 0
    results.forEach((r, i) => {
      const tied = i > 0 && r.statProduct === results[i - 1].statProduct
      if (tied) ties++
      expect(r.rank).toBe(tied ? results[i - 1].rank : i + 1)
    })
    expect(ties).toBeGreaterThan(0)
    expect(rankIVs(bulbasaur, 10000, 1)).toEqual(results)
  })

  it('omits spreads with no eligible level, returning empty when none qualify', () => {
    const large = { ...bulbasaur, attack: 1000, defense: 1000, stamina: 1000 }
    expect(rankIVs(large, 10, 51)).toEqual([])
    const partial = rankIVs(bulbasaur, 12, 1)
    expect(partial.length).toBeGreaterThan(0)
    expect(partial.length).toBeLessThan(4096)
  })
})

describe('findIVs', () => {
  it('recovers a known half-level spread and returns only exact matches', () => {
    const observed = stats(bulbasaur, perfect, 20.5)
    const matches = findIVs(bulbasaur, observed.cp, observed.hp, 20, 21)
    expect(matches).toContainEqual({ ivs: perfect, level: 20.5, cp: observed.cp, hp: observed.hp })
    for (const match of matches) expect(stats(bulbasaur, match.ivs, match.level)).toMatchObject({ cp: observed.cp, hp: observed.hp })
    expect(findIVs(bulbasaur, 99999, 99999, 1, 51)).toEqual([])
  })

  it('includes both range endpoints and all 4096 spreads even on CP/HP plateaus', () => {
    const tiny = { ...bulbasaur, attack: 1, defense: 1, stamina: 1 }
    const matches = findIVs(tiny, 10, 10, 1, 1.5)
    expect(matches).toHaveLength(8192)
    expect(matches[0].level).toBe(1)
    expect(matches.at(-1)?.level).toBe(1.5)
    const observed = stats(bulbasaur, perfect, 51)
    expect(findIVs(bulbasaur, observed.cp, observed.hp, 51, 51)).toContainEqual({ ivs: perfect, level: 51, cp: observed.cp, hp: observed.hp })
  })

  it('rejects invalid observed stats, CP caps, and reversed ranges', () => {
    for (const n of [9, -1, 10.5, NaN, Infinity]) {
      expect(() => rankIVs(bulbasaur, n, 50)).toThrow(RangeError)
      expect(() => findIVs(bulbasaur, n, 10, 1, 51)).toThrow(RangeError)
      expect(() => findIVs(bulbasaur, 10, n, 1, 51)).toThrow(RangeError)
    }
    expect(() => findIVs(bulbasaur, 10, 10, 20, 19)).toThrow(RangeError)
  })
})
