import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { PNG } from 'pngjs'

const paths = process.argv.slice(2)
if (paths.length !== 2) {
  throw new Error('Usage: node scripts/import-shinx-fixtures.mjs <captured.png> <analyzed.png>')
}

// Decode both inputs and check every bound before writing any fixture. Never copy
// source files or metadata: only these bar-panel pixels may enter the repository.
const crops = [
  { sampling: 'native', sourceWidth: 1440, sourceHeight: 3088, x: 140, y: 2270, width: 560, height: 340 },
  { sampling: 'analyzed', sourceWidth: 933, sourceHeight: 2000, x: 90, y: 1470, width: 360, height: 220 },
].map((spec, i) => {
  const source = PNG.sync.read(readFileSync(paths[i]))
  assert.equal(source.width, spec.sourceWidth)
  assert.equal(source.height, spec.sourceHeight)
  const { x, y, width, height } = spec
  assert(x >= 0 && y >= 0 && width > 0 && height > 0)
  assert(x + width <= source.width && y + height <= source.height)
  const crop = new PNG({ width, height })
  for (let row = 0; row < height; row++) {
    const start = ((y + row) * source.width + x) * 4
    source.data.copy(crop.data, row * width * 4, start, start + width * 4)
  }
  return { ...spec, crop }
})

for (const { sampling, x, y, width, height, crop } of crops) {
  const source = `src/lib/fixtures/shinx-${sampling}-appraisal.png`
  const jsonPath = `android/app/src/test/resources/appraisal-shinx-${sampling}.json`
  const runs = []
  for (let i = 0; i < crop.data.length; i += 4) {
    const [r, g, b, a] = crop.data.subarray(i, i + 4)
    const pixel = (a << 24) | (r << 16) | (g << 8) | b
    if (runs.length && runs.at(-1) === pixel) runs[runs.length - 2]++
    else runs.push(1, pixel)
  }
  // Verify the encoded PNG and Kotlin RLE both preserve every cropped pixel.
  const png = PNG.sync.write(crop)
  assert.deepEqual(PNG.sync.read(png).data, crop.data)
  let offset = 0
  for (let i = 0; i < runs.length; i += 2) {
    for (let j = 0; j < runs[i]; j++) {
      const pixel = runs[i + 1]
      assert.equal(crop.data[offset++], (pixel >>> 16) & 255)
      assert.equal(crop.data[offset++], (pixel >>> 8) & 255)
      assert.equal(crop.data[offset++], pixel & 255)
      assert.equal(crop.data[offset++], pixel >>> 24)
    }
  }
  assert.equal(offset, crop.data.length)
  writeFileSync(new URL(`../${source}`, import.meta.url), png)
  writeFileSync(new URL(`../${jsonPath}`, import.meta.url), `${JSON.stringify({
    source, sampling, width, height,
    expected: { attack: 4, defense: 12, stamina: 15 }, runs,
  })}\n`)
  console.log(`${sampling}: verified crop (${x},${y},${width},${height}); wrote ${source} and ${jsonPath}`)
}
