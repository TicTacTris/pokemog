import assert from 'node:assert/strict'
import {
  copyFile,
  mkdir,
  readFile,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises'
import { PNG } from 'pngjs'

const output = new URL('../public/', import.meta.url)
const android = new URL('../android/app/src/main/', import.meta.url)
const source = await readFile(
  new URL('java/dev/pokemog/android/PixelPokeball.kt', android),
  'utf8',
)
const size = Number(source.match(/const val SIZE = (\d+)/)?.[1])
const rows = [...source.matchAll(/"([.H#RLWC]+)"/g)].map((match) => match[1])
const palette = Object.fromEntries(
  [...source.matchAll(/'([^']+)' to 0xFF([\dA-F]{6})\.toInt\(\)/g)].map(
    ([, pixel, hex]) => [pixel, hex],
  ),
)
assert.equal(size, 16)
assert.equal(rows.length, size)
for (const row of rows) {
  assert.equal(row.length, size)
  for (const pixel of row) assert.ok(pixel === '.' || palette[pixel])
}

await mkdir(new URL('fonts/', output), { recursive: true })
for (const name of ['Silkscreen-Regular.ttf', 'Silkscreen-OFL.txt']) {
  await copyFile(
    new URL(`assets/${name}`, android),
    new URL(`fonts/${name}`, output),
  )
}

const rectangles = rows.flatMap((row, y) =>
  [...row].flatMap((pixel, x) =>
    palette[pixel]
      ? [
          `<rect x="${x}" y="${y}" width="1" height="1" fill="#${palette[pixel]}"/>`,
        ]
      : [],
  ),
)
await writeFile(
  new URL('favicon.svg', output),
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges">${rectangles.join('')}</svg>\n`,
)

for (const width of [192, 512]) {
  const image = new PNG({ width, height: width })
  // Integer scaling and centered padding preserve the original hard pixel edges.
  const cell = Math.floor(width / (size + 4))
  const origin = Math.floor((width - size * cell) / 2)
  for (let y = 0; y < width; y++) {
    for (let x = 0; x < width; x++) {
      const pixel =
        rows[Math.floor((y - origin) / cell)]?.[Math.floor((x - origin) / cell)]
      const hex = palette[pixel] ?? 'FBF5EC'
      const offset = (y * width + x) * 4
      image.data[offset] = Number.parseInt(hex.slice(0, 2), 16)
      image.data[offset + 1] = Number.parseInt(hex.slice(2, 4), 16)
      image.data[offset + 2] = Number.parseInt(hex.slice(4, 6), 16)
      image.data[offset + 3] = 255
    }
  }
  const encoded = PNG.sync.write(image)
  assert.deepEqual(PNG.sync.read(encoded).data, image.data)
  await writeFile(new URL(`icon-${width}.png`, output), encoded)
}

// Deliberately scoped to generated web OCR files, never Android assets or fixtures.
await rm(new URL('ocr/', output), { recursive: true, force: true })
console.log(
  'Prepared local Silkscreen font/license and pixel Pokeball icons; removed public/ocr.',
)
for (const name of [
  'fonts/Silkscreen-Regular.ttf',
  'fonts/Silkscreen-OFL.txt',
  'favicon.svg',
  'icon-192.png',
  'icon-512.png',
]) {
  console.log(`/${name}: ${(await stat(new URL(name, output))).size} bytes`)
}
