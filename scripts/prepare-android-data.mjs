import { mkdir, readFile, writeFile } from 'node:fs/promises'
import ts from 'typescript'
import { commit, evolutionMetadata } from './evolution-metadata.mjs'

const root = new URL('../', import.meta.url)
async function download(path) {
  const response = await fetch(`https://raw.githubusercontent.com/pvpoke/pvpoke/${commit}/${path}`)
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return response.text()
}
const [master, source, license, bundled, bundledCpms, engine] = await Promise.all([
  download('src/data/gamemaster.json'), download('src/js/pokemon/Pokemon.js'), download('LICENSE'),
  readFile(new URL('src/data/pokemon.json', root), 'utf8'),
  readFile(new URL('src/data/cp-multipliers.json', root), 'utf8'),
  readFile(new URL('src/lib/calculations.ts', root), 'utf8'),
])
const cpms = JSON.parse(source.match(/var cpms = (\[[\d.,\s]+\]);/)?.[1] ?? 'null')?.slice(0, 101)
if (!cpms || cpms.length !== 101 || cpms.some((n, i) => !Number.isFinite(n) || n <= 0 || n >= 1 || (i && n <= cpms[i - 1]))) throw new Error('Invalid CP multipliers')
if (JSON.stringify(cpms) !== JSON.stringify(JSON.parse(bundledCpms))) throw new Error('Bundled CP multipliers differ from pinned source')
if (!license.startsWith('MIT License') || !license.includes('Copyright (c) 2019 pvpoke')) throw new Error('Unexpected license')
const base = JSON.parse(bundled)
if (base.length < 1000) throw new Error('Invalid bundled IDs')
const { metadata, report } = evolutionMetadata(base, JSON.parse(master))
const pokemon = base.map(p => {
  const { evolutions, normalId, shadowId } = metadata[p.id]
  return {
    id: p.id, name: p.name, attack: p.attack, defense: p.defense, stamina: p.stamina,
    evolutions,
    ...(normalId ? { normalId } : {}),
    ...(shadowId ? { shadowId } : {}),
  }
})

// Execute the actual web functions, not a second JS implementation of the formulas.
const replaced = engine.replace(/import cpms from ['"]\.\.\/data\/cp-multipliers\.json['"]/, `const cpms = ${JSON.stringify(cpms)}`)
if (replaced === engine) throw new Error('Could not inject pinned multipliers')
const compiled = ts.transpileModule(replaced, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { stats, rankIVs } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const tiny = { id: 'tiny', name: 'Tiny', attack: 1, defense: 1, stamina: 1 }
const large = { id: 'large', name: 'Large', attack: 1000, defense: 1000, stamina: 1000 }
const fixtures = [...pokemon, tiny, large]
const fixtureById = new Map(fixtures.map(p => [p.id, p]))
const columns = s => [s.cp, s.hp, s.attack, s.defense, s.statProduct]
const statRows = []
for (const id of ['bulbasaur', 'azumarill', 'stunfisk_galarian', 'medicham', 'mewtwo_shadow', 'shedinja', 'tiny']) {
  for (const level of [1, 1.5, 20.5, 40, 50, 50.5, 51]) for (const ivs of [{ attack: 0, defense: 0, stamina: 0 }, { attack: 15, defense: 15, stamina: 15 }, { attack: 0, defense: 15, stamina: 14 }]) {
    statRows.push([id, ivs.attack, ivs.defense, ivs.stamina, level, ...columns(stats(fixtureById.get(id), ivs, level))])
  }
}
const rankRows = [], rankCases = []
for (const [id, cap, max] of [['bulbasaur', 500, 50.5], ['bulbasaur', 1115, 40], ['bulbasaur', 10000, 51], ['bulbasaur', 10000, 1], ['bulbasaur', 12, 1], ['azumarill', 1500, 50], ['stunfisk_galarian', 2500, 51], ['shedinja', 500, 51], ['tiny', 10, 51], ['large', 10, 51]]) {
  const ranks = rankIVs(fixtureById.get(id), cap, max)
  rankCases.push([id, cap, max, ranks.length])
  for (const r of ranks) rankRows.push([id, cap, max, r.ivs.attack, r.ivs.defense, r.ivs.stamina, r.level, ...columns(r), r.rank])
}
const inferenceRows = []
for (const id of ['bulbasaur', 'shedinja', 'tiny']) for (const buddy of [false, true]) for (const observedLevel of [1, 1.5, 20.5, 50, 50.5, 51]) {
  const p = fixtureById.get(id), ivs = { attack: 15, defense: 15, stamina: 15 }
  const observed = stats(p, ivs, observedLevel)
  const levels = Array.from({ length: 99 }, (_, i) => 1 + i / 2).filter(level => {
    const s = stats(p, ivs, level + Number(buddy))
    return s.cp === observed.cp && s.hp === observed.hp
  })
  inferenceRows.push([id, 15, 15, 15, observed.cp, observed.hp, buddy, levels.join(',') || '-'])
}
const assets = new URL('android/app/src/main/assets/', root)
const resources = new URL('android/app/src/test/resources/', root)
const deviceAssets = new URL('android/app/src/androidTest/assets/', root)
await Promise.all([mkdir(assets, { recursive: true }), mkdir(resources, { recursive: true }), mkdir(deviceAssets, { recursive: true })])
const json = value => JSON.stringify(value, null, 2) + '\n'
const tsv = rows => rows.map(row => row.join('\t')).join('\n') + '\n'
await Promise.all([
  writeFile(new URL('s23-ultra-appraisal.png', deviceAssets), await readFile(new URL('src/lib/fixtures/s23-ultra-appraisal.png', root))),
  writeFile(new URL('pokemon.json', assets), json(pokemon)),
  writeFile(new URL('cp-multipliers.json', assets), json(cpms)),
  writeFile(new URL('PVPoke-LICENSE.txt', assets), license),
  writeFile(new URL('evolution-metadata.json', assets), json(report)),
  writeFile(new URL('pokemon.tsv', resources), tsv(fixtures.map(p => [p.id, p.name, p.attack, p.defense, p.stamina]))),
  writeFile(new URL('cp-multipliers.tsv', resources), tsv(cpms.map(n => [n]))),
  writeFile(new URL('stats-parity.tsv', resources), tsv(statRows)),
  writeFile(new URL('rank-cases.tsv', resources), tsv(rankCases)),
  writeFile(new URL('ranks-parity.tsv', resources), tsv(rankRows)),
  writeFile(new URL('inference-parity.tsv', resources), tsv(inferenceRows)),
  writeFile(new URL('PVPoke-LICENSE.txt', resources), license),
])
console.log(`Validated ${pokemon.length} Pokemon, ${cpms.length} multipliers, acyclic resolved evolution graph; ${report.missingFamilyMetadata.length} missing family metadata, ${report.remappedShadowEdges.length} shadow remaps, ${report.omittedShadowEdges.length} omitted shadow edges. Generated ${statRows.length} stats and ${rankRows.length} ranking parity rows from TypeScript (${commit}).`)
