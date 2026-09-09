import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'

const [source, ffmpeg, java = 'java'] = process.argv.slice(2)
if (!source || !ffmpeg) {
  throw new Error(
    'Usage: node scripts/import-giratina-fixtures.mjs <source.jpg> <ffmpeg> [java]',
  )
}
// Decode the full JPEG in memory. Only the bar panel may be persisted, never the
// original file, its metadata, date/location, or the rest of the screenshot.
const decoded = PNG.sync.read(
  execFileSync(
    ffmpeg,
    [
      '-f',
      'image2pipe',
      '-c:v',
      'mjpeg',
      '-i',
      source,
      '-frames:v',
      '1',
      '-c:v',
      'png',
      '-f',
      'image2',
      '-update',
      '1',
      'pipe:1',
    ],
    { maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] },
  ),
)
assert.equal(decoded.width, 540)
assert.equal(decoded.height, 1170)
const crop = new PNG({ width: 215, height: 150 })
for (let y = 0; y < crop.height; y++) {
  const start = ((y + 875) * decoded.width + 50) * 4
  decoded.data.copy(
    crop.data,
    y * crop.width * 4,
    start,
    start + crop.width * 4,
  )
}
const imageio = PNG.sync.read(
  execFileSync(
    java,
    [fileURLToPath(new URL('./CropGiratina.java', import.meta.url)), source],
    { maxBuffer: 16 * 1024 * 1024 },
  ),
)
assert.equal(imageio.width, crop.width)
assert.equal(imageio.height, crop.height)
for (const [decoder, image] of [
  ['ffmpeg', crop],
  ['imageio', imageio],
]) {
  const runs = []
  for (let i = 0; i < image.data.length; i += 4) {
    const [r, g, b, a] = image.data.subarray(i, i + 4)
    const pixel = (a << 24) | (r << 16) | (g << 8) | b
    if (runs.length && runs.at(-1) === pixel) runs[runs.length - 2]++
    else runs.push(1, pixel)
  }
  const png = PNG.sync.write(image)
  assert.deepEqual(PNG.sync.read(png).data, image.data)
  let offset = 0
  for (let i = 0; i < runs.length; i += 2) {
    for (let j = 0; j < runs[i]; j++) {
      const pixel = runs[i + 1]
      assert.equal(image.data[offset++], (pixel >>> 16) & 255)
      assert.equal(image.data[offset++], (pixel >>> 8) & 255)
      assert.equal(image.data[offset++], pixel & 255)
      assert.equal(image.data[offset++], pixel >>> 24)
    }
  }
  assert.equal(offset, image.data.length)
  writeFileSync(
    new URL(
      `../src/lib/fixtures/giratina-${decoder}-appraisal.png`,
      import.meta.url,
    ),
    png,
  )
  writeFileSync(
    new URL(
      `../android/app/src/test/resources/appraisal-giratina-${decoder}.json`,
      import.meta.url,
    ),
    `${JSON.stringify({
      decoder,
      width: image.width,
      height: image.height,
      crop: { x: 50, y: 875, width: 215, height: 150 },
      expected: { attack: 8, defense: 11, stamina: 6 },
      runs,
    })}\n`,
  )
  console.log(`${decoder}: verified 215x150 bar-only PNG and ARGB RLE`)
}
