import { readFile, writeFile } from 'node:fs/promises'
import { commit, sourceUrl, evolutionMetadata } from './evolution-metadata.mjs'

const output = new URL('../src/data/', import.meta.url)
const response = await fetch(sourceUrl + 'src/data/gamemaster.json')
if (!response.ok) throw new Error(`gamemaster: HTTP ${response.status}`)
const base = JSON.parse(await readFile(new URL('pokemon.json', output), 'utf8'))
const { metadata, report } = evolutionMetadata(base, await response.json())
const licenseResponse = await fetch(sourceUrl + 'LICENSE')
if (!licenseResponse.ok) throw new Error(`LICENSE: HTTP ${licenseResponse.status}`)
const license = await licenseResponse.text()
if (license !== await readFile(new URL('PVPoke-LICENSE.txt', output), 'utf8')) throw new Error('Bundled license differs from pinned source')
const files = { 'evolutions.json': metadata, 'evolution-metadata.json': { source: sourceUrl + 'src/data/gamemaster.json', license: 'PVPoke-LICENSE.txt', ...report } }
for (const [name, value] of Object.entries(files)) {
  const text = JSON.stringify(value, null, 2) + '\n'
  if (process.argv.includes('--check')) {
    if (await readFile(new URL(name, output), 'utf8') !== text) throw new Error(`Stale ${name}`)
  } else await writeFile(new URL(name, output), text)
}
console.log(`${commit}: ${base.length} species; ${report.missingFamilyMetadata.length} unknown family records; ${report.terminalWithFamilyMetadata.length} known terminal records; ${report.omittedShadowEdges.length} omitted Shadow edges`)
