import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const apk = await readFile(
  new URL('android/app/build/outputs/apk/debug/app-debug.apk', root),
)
const output = new URL('public/downloads/', root)
await mkdir(output, { recursive: true })
const digest = createHash('sha256').update(apk).digest('hex')
const name = 'pokemog-debug.apk'
await writeFile(new URL(name, output), apk)
await writeFile(new URL(`${name}.sha256`, output), `${digest}  ${name}\n`)
console.log(
  `PokeMog APK: /downloads/pokemog-debug.apk (${(apk.length / 1024 / 1024).toFixed(1)} MB)\nSHA-256: ${digest}\nPersonal testing only; signed with the existing Android debug certificate.`,
)
