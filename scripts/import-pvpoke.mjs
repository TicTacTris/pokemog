import { mkdir, writeFile } from 'node:fs/promises'
import { commit, sourceUrl, evolutionMetadata } from './evolution-metadata.mjs'

const base = sourceUrl
async function download(path) {
  const response = await fetch(base + path)
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`)
  return response.text()
}

const [master, source, license] = await Promise.all([
  download('src/data/gamemaster.json'),
  download('src/js/pokemon/Pokemon.js'),
  download('LICENSE'),
])
const match = source.match(/var cpms = (\[[\d.,\s]+\]);/)
if (!match) throw new Error('CP multiplier table not found')
const cpms = JSON.parse(match[1]).slice(0, 101)
if (cpms.length !== 101 || cpms.some((n, i) => !Number.isFinite(n) || n <= 0 || n >= 1 || (i > 0 && n <= cpms[i - 1]))) {
  throw new Error('Invalid level 1-51 multiplier table')
}
const pokemon = JSON.parse(master).pokemon
  .filter(p => !p.tags?.some(tag => ['mega', 'supermega', 'primal'].includes(tag)) && !/(?:^|_)(mega|primal)(?:_|$)/.test(p.speciesId))
  .map(p => {
    const forms = [...p.speciesName.matchAll(/\(([^)]+)\)/g)].map(m => m[1])
    return {
      id: p.speciesId,
      name: p.speciesName,
      ...(forms.length ? { form: forms.join(', ') } : {}),
      attack: p.baseStats.atk,
      defense: p.baseStats.def,
      stamina: p.baseStats.hp,
    }
  })
if (!pokemon.length || new Set(pokemon.map(p => p.id)).size !== pokemon.length || pokemon.some(p =>
  !p.id || !p.name || [p.attack, p.defense, p.stamina].some(n => !Number.isSafeInteger(n) || n <= 0)
)) throw new Error('Invalid Pokemon data')
if (!license.startsWith('MIT License') || !license.includes('Copyright (c) 2019 pvpoke')) throw new Error('Unexpected license')

const output = new URL('../src/data/', import.meta.url)
const { metadata, report } = evolutionMetadata(pokemon, JSON.parse(master))
await mkdir(output, { recursive: true })
await Promise.all([
  writeFile(new URL('pokemon.json', output), JSON.stringify(pokemon, null, 2) + '\n'),
  writeFile(new URL('cp-multipliers.json', output), JSON.stringify(cpms, null, 2) + '\n'),
  writeFile(new URL('PVPoke-LICENSE.txt', output), license),
  writeFile(new URL('evolutions.json', output), JSON.stringify(metadata, null, 2) + '\n'),
  writeFile(new URL('evolution-metadata.json', output), JSON.stringify({ source: sourceUrl + 'src/data/gamemaster.json', license: 'PVPoke-LICENSE.txt', ...report }, null, 2) + '\n'),
])
console.log(`Imported ${pokemon.length} Pokemon and ${cpms.length} multipliers from ${commit}`)
