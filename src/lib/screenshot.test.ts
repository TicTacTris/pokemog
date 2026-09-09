import { readFileSync } from 'node:fs'
import { PNG } from 'pngjs'
import { describe, expect, it } from 'vitest'
import pokemonData from '../data/pokemon.json'
import type { Pokemon } from './calculations'
import { detectAppraisalBars, parseScreenshotText } from './screenshot'

const pokemon: Pokemon[] = [
  { id: 'stunfisk', name: 'Stunfisk', attack: 1, defense: 1, stamina: 1 },
  {
    id: 'stunfisk_galarian',
    name: 'Stunfisk (Galarian)',
    form: 'Galarian',
    attack: 1,
    defense: 1,
    stamina: 1,
  },
  { id: 'mr_mime', name: 'Mr. Mime', attack: 1, defense: 1, stamina: 1 },
  { id: 'nidoran_f', name: 'Nidoran♀', attack: 1, defense: 1, stamina: 1 },
]

describe('parseScreenshotText', () => {
  it('reads appraisal dialogue and retains all forms without deriving IVs', () => {
    expect(
      parseScreenshotText(
        'Sparky\nCP 1,234\n42 / 120 HP\nYour STUNFISK is tiny!',
        pokemon,
      ),
    ).toEqual({ candidates: pokemon.slice(0, 2), cp: 1234, hp: 120 })
    expect(
      parseScreenshotText('Galarian Stunfisk', pokemon).candidates,
    ).toEqual(pokemon.slice(0, 2))
  })
  it('normalizes punctuation, accents, whitespace and gender without fuzzy matching', () => {
    expect(
      parseScreenshotText('MR.   MÍME and Nidoran♀', pokemon).candidates,
    ).toEqual(pokemon.slice(2))
    for (const text of [
      'Stunfisks',
      'Superstunfisk',
      'Stunflsk',
      'Nidoran',
      'Nidoran♂',
      '',
    ]) {
      expect(parseScreenshotText(text, pokemon).candidates).toEqual([])
    }
  })
  it.each(['Stunfisk', 'Giratina', 'Darmanitan', 'Bulbasaur'])(
    'returns every bundled form for %s',
    (name) => {
      const expected = pokemonData.filter(
        (p) => p.name === name || p.name.startsWith(`${name} (`),
      )
      expect(expected.length).toBeGreaterThan(1)
      expect(
        parseScreenshotText(`Your ${name} is tiny!`, pokemonData).candidates,
      ).toEqual(expected)
    },
  )
  it('preserves ambiguity between species, including possible nicknames', () => {
    expect(
      parseScreenshotText('Mr Mime\nYour Stunfisk', pokemon).candidates,
    ).toEqual(pokemon.slice(0, 3))
  })
  it('requires labels, handles injured HP, and rejects conflicting or corrupt readings', () => {
    expect(parseScreenshotText('HP: 0/100\nCP: 555', [])).toEqual({
      candidates: [],
      cp: 555,
      hp: 100,
    })
    expect(parseScreenshotText('1234 100', [])).toEqual({
      candidates: [],
      cp: null,
      hp: null,
    })
    for (const text of [
      'CP 123 CP 456',
      'CP 12O',
      'CP 1,23',
      'CP 12.5',
      'CP 9',
    ]) {
      expect(parseScreenshotText(text, []).cp).toBeNull()
    }
    for (const text of [
      'HP 100 HP 120',
      '120/100 HP',
      'HP 12O',
      '100/12O HP',
      'HP 100 / 12O',
    ]) {
      expect(parseScreenshotText(text, []).hp).toBeNull()
    }
    expect(parseScreenshotText('CP 123 CP 123\n100 HP', [])).toEqual({
      candidates: [],
      cp: 123,
      hp: 100,
    })
  })
})

type Raster = { data: Uint8ClampedArray; width: number; height: number }
const orange = [240, 145, 65, 255]
const red = [235, 75, 80, 255]
const gray = [220, 222, 221, 255]
function rectangle(
  image: Raster,
  x: number,
  y: number,
  w: number,
  h: number,
  rgba: number[],
) {
  for (let row = y; row < y + h; row++) {
    for (let col = x; col < x + w; col++)
      image.data.set(rgba, (row * image.width + col) * 4)
  }
}
function raster(scale = 1): Raster {
  const image = {
    width: 300 * scale,
    height: 260 * scale,
    data: new Uint8ClampedArray(300 * 260 * scale ** 2 * 4),
  }
  rectangle(image, 0, 0, image.width, image.height, [255, 255, 255, 255])
  return image
}
function bar(
  image: Raster,
  iv: number,
  y: number,
  scale = 1,
  x = 35,
  color = orange,
) {
  // Three 50px tracks separated by 3px gaps; each segment contains five IV units.
  for (let segment = 0; segment < 3; segment++) {
    rectangle(
      image,
      (x + segment * 53) * scale,
      y * scale,
      50 * scale,
      6 * scale,
      gray,
    )
    rectangle(
      image,
      (x + segment * 53) * scale,
      y * scale,
      Math.max(0, Math.min(5, iv - segment * 5)) * 10 * scale,
      6 * scale,
      color,
    )
  }
}

describe('detectAppraisalBars', () => {
  describe.each(['ffmpeg', 'imageio'])(
    'actual Giratina JPEG via %s',
    (decoder) => {
      it.each(['none', 'duplicate', 'missing HP', 'shifted HP'])(
        'handles %s without guessing',
        (fault) => {
          const source = PNG.sync.read(
            readFileSync(
              new URL(
                `./fixtures/giratina-${decoder}-appraisal.png`,
                import.meta.url,
              ),
            ),
          )
          const image: Raster = {
            width: source.width,
            height: source.height * (fault === 'duplicate' ? 2 : 1),
            data: new Uint8ClampedArray(
              source.data.length * (fault === 'duplicate' ? 2 : 1),
            ),
          }
          image.data.set(source.data)
          if (fault === 'duplicate')
            image.data.set(source.data, source.data.length)
          if (fault === 'missing HP' || fault === 'shifted HP') {
            rectangle(
              image,
              0,
              120,
              image.width,
              image.height - 120,
              [255, 255, 255, 255],
            )
            if (fault === 'shifted HP') {
              for (let y = 120; y < image.height; y++) {
                image.data.set(
                  source.data.subarray(
                    y * image.width * 4,
                    (y * image.width + image.width - 10) * 4,
                  ),
                  (y * image.width + 10) * 4,
                )
              }
            }
          }
          expect(detectAppraisalBars(image)).toEqual(
            fault === 'none' ? { attack: 8, defense: 11, stamina: 6 } : null,
          )
        },
      )
    },
  )
  it.each([1, 2, 3, 4, 5])(
    'bounds warm JPEG transitions to four pixels: %s',
    (width) => {
      for (const kind of [
        'warm',
        'white',
        'near-white',
        'transparent',
        'endpoint',
        'non-prefix',
      ]) {
        const image = raster()
        for (let row = 0; row < 3; row++) {
          const y = 70 + row * 30
          for (let segment = 0; segment < 3; segment++) {
            const x = 35 + segment * 63
            rectangle(image, x, y, 60, 2, gray)
            if (segment === 0) rectangle(image, x, y, 60, 2, orange)
            if (segment === 1) {
              const fill =
                36 - Math.ceil(width / 2) + (kind === 'endpoint' ? 5 : 0)
              rectangle(image, x, y, fill, 2, orange)
              rectangle(
                image,
                x + fill,
                y,
                width,
                2,
                kind === 'white'
                  ? [255, 255, 255, 255]
                  : kind === 'near-white'
                    ? [255, 248, 240, 255]
                    : [252, 220, 185, kind === 'transparent' ? 239 : 255],
              )
              if (kind === 'non-prefix')
                rectangle(image, x + 55, y, 5, 2, orange)
            }
          }
        }
        expect(detectAppraisalBars(image), `${width}px ${kind}`).toEqual(
          width <= 4 && kind === 'warm'
            ? { attack: 8, defense: 8, stamina: 8 }
            : null,
        )
      }
    },
  )
  it.each([1, 3])(
    'preserves a half-pixel at an odd %spx transition',
    (width) => {
      for (const spacingError of [1, 2, 3]) {
        const image = raster()
        for (let row = 0; row < 3; row++) {
          const y = 70 + row * 30 + (row === 2 ? spacingError : 0)
          for (let segment = 0; segment < 3; segment++) {
            const x = 35 + segment * 62
            rectangle(image, x, y, 59, 2, gray)
            if (segment === 0) rectangle(image, x, y, 59, 2, orange)
            if (segment === 1) {
              // Effective total 92.5/177*15 = 7.839; truncating to 92 fails.
              const fill = 33 - Math.floor(width / 2)
              rectangle(image, x, y, fill, 2, orange)
              rectangle(image, x + fill, y, width, 2, [252, 220, 185, 255])
            }
          }
        }
        expect(
          detectAppraisalBars(image),
          `spacing error ${spacingError}`,
        ).toEqual(
          spacingError <= 2 ? { attack: 8, defense: 8, stamina: 8 } : null,
        )
      }
    },
  )
  it('keeps the original narrow-bridge endpoint convention', () => {
    for (const r of [237, 252]) {
      const image = raster()
      for (const y of [70, 100, 130]) {
        for (let segment = 0; segment < 3; segment++) {
          rectangle(image, 35 + segment * 63, y, 60, 2, gray)
        }
        rectangle(image, 35, y, 14, 2, orange)
        rectangle(image, 49, y, 2, 2, [r, 220, 192, 255])
      }
      // Old fill 14/180*15 = 1.167 passes. Applying the new half-blend
      // convention globally would make it 1.25 and incorrectly reject it.
      expect(detectAppraisalBars(image)).toEqual(
        r === 237 ? { attack: 1, defense: 1, stamina: 1 } : null,
      )
    }
  })
  describe.each(['native', 'analyzed'])('actual Shinx %s crop', (sampling) => {
    it.each(['none', 'duplicate', 'missing HP', 'shifted HP'])(
      'handles %s without guessing',
      (fault) => {
        const source = PNG.sync.read(
          readFileSync(
            new URL(
              `./fixtures/shinx-${sampling}-appraisal.png`,
              import.meta.url,
            ),
          ),
        )
        const image: Raster = {
          width: source.width,
          height: source.height * (fault === 'duplicate' ? 2 : 1),
          data: new Uint8ClampedArray(
            source.width * source.height * 4 * (fault === 'duplicate' ? 2 : 1),
          ),
        }
        image.data.set(source.data)
        if (fault === 'duplicate')
          image.data.set(source.data, source.data.length)
        if (fault === 'missing HP' || fault === 'shifted HP') {
          // Only the HP bar band, below the label; preserve Attack and Defense.
          const top = sampling === 'native' ? 280 : 180
          rectangle(
            image,
            0,
            top,
            image.width,
            image.height - top,
            [255, 255, 255, 255],
          )
          if (fault === 'shifted HP') {
            for (let y = top; y < image.height; y++) {
              image.data.set(
                source.data.subarray(
                  y * image.width * 4,
                  (y * image.width + image.width - 10) * 4,
                ),
                (y * image.width + 10) * 4,
              )
            }
          }
        }
        expect(detectAppraisalBars(image)).toEqual(
          fault === 'none' ? { attack: 4, defense: 12, stamina: 15 } : null,
        )
      },
    )
  })
  it.each(['native', 'nearest', 'bilinear'])(
    'reads the JPEG-derived S23 Ultra crop with %s sampling',
    (sampling) => {
      const source = PNG.sync.read(
        readFileSync(
          new URL('./fixtures/s23-ultra-appraisal.png', import.meta.url),
        ),
      )
      let image: Raster = {
        width: source.width,
        height: source.height,
        data: new Uint8ClampedArray(source.data),
      }
      if (sampling !== 'native') {
        // The full 1440x2963 photo is resized to a 2000px long side before detection.
        const scale = 2000 / 2963
        image = {
          width: Math.round(source.width * scale),
          height: Math.round(source.height * scale),
          data: new Uint8ClampedArray(
            Math.round(source.width * scale) *
              Math.round(source.height * scale) *
              4,
          ),
        }
        for (let y = 0; y < image.height; y++) {
          for (let x = 0; x < image.width; x++) {
            const sx = (x + 0.5) / scale - 0.5,
              sy = (y + 0.5) / scale - 0.5
            const left = Math.floor(sx),
              top = Math.floor(sy)
            const pixel = (px: number, py: number, channel: number) =>
              source.data[
                (Math.min(source.height - 1, py) * source.width +
                  Math.min(source.width - 1, px)) *
                  4 +
                  channel
              ]!
            for (let channel = 0; channel < 4; channel++) {
              const dx = sx - left,
                dy = sy - top
              image.data[(y * image.width + x) * 4 + channel] =
                sampling === 'nearest'
                  ? pixel(Math.round(sx), Math.round(sy), channel)
                  : pixel(left, top, channel) * (1 - dx) * (1 - dy) +
                    pixel(left + 1, top, channel) * dx * (1 - dy) +
                    pixel(left, top + 1, channel) * (1 - dx) * dy +
                    pixel(left + 1, top + 1, channel) * dx * dy
            }
          }
        }
      }
      expect(detectAppraisalBars(image)).toEqual({
        attack: 1,
        defense: 15,
        stamina: 14,
      })
    },
  )
  it.each([1, 2, 3])('recognizes three full red bars at scale %s', (scale) => {
    const image = raster(scale)
    for (const y of [70, 100, 130]) bar(image, 15, y, scale, 35, red)
    expect(detectAppraisalBars(image)).toEqual({
      attack: 15,
      defense: 15,
      stamina: 15,
    })
  })
  it.each([0, 2])(
    'allows pixel-quantized JPEG segment widths but not larger mismatches: %s',
    (extraWidth) => {
      const image = raster(2)
      // Quality-80 portrait JPEG + canvas downscale exposes 109/106/110px HP
      // core segments. A fractional 3.61px tolerance accidentally allows only 3px.
      const lengths = [109, 106, 110 + extraWidth]
      for (const [row, fills] of [
        [22, 0, 0],
        [109, 106, 110],
        [109, 106, 87],
      ].entries()) {
        let x = 35
        for (const [segment, length] of lengths.entries()) {
          rectangle(image, x, 140 + row * 60, length, 12, gray)
          rectangle(
            image,
            x,
            140 + row * 60,
            fills[segment]!,
            12,
            row === 1 ? red : orange,
          )
          x += length + 6
        }
      }
      expect(detectAppraisalBars(image)).toEqual(
        extraWidth ? null : { attack: 1, defense: 15, stamina: 14 },
      )
    },
  )
  it.each([
    [0, 7, 13],
    [1, 0, 15],
    [5, 10, 0],
    [14, 2, 9],
  ])(
    'reads partial fills %s/%s/%s with zero tracks and unrelated colors',
    (attack, defense, stamina) => {
      const image = raster()
      rectangle(image, 15, 10, 80, 18, orange)
      rectangle(image, 220, 80, 25, 60, red)
      for (const [i, iv] of [attack, defense, stamina].entries())
        bar(image, iv, 70 + 30 * i)
      expect(detectAppraisalBars(image)).toEqual({ attack, defense, stamina })
    },
  )
  it('reads every IV endpoint on scaled tracks with rounded outer corners', () => {
    for (let iv = 0; iv <= 15; iv++) {
      const image = raster(2)
      for (const [i, value] of [iv, 15 - iv, 7].entries()) {
        const y = 70 + 30 * i
        bar(image, value, y, 2)
        for (const edge of [y * 2, y * 2 + 11]) {
          rectangle(image, 70, edge, 2, 1, [255, 255, 255, 255])
          rectangle(image, 380, edge, 2, 1, [255, 255, 255, 255])
        }
      }
      expect(detectAppraisalBars(image)).toEqual({
        attack: iv,
        defense: 15 - iv,
        stamina: 7,
      })
    }
  })
  it('rejects empty, transparent, all-gray and deterministic noisy images', () => {
    expect(detectAppraisalBars(raster())).toBeNull()
    const image = raster()
    for (const y of [70, 100, 130]) bar(image, 0, y)
    expect(detectAppraisalBars(image)).toBeNull()
    image.data.fill(0)
    expect(detectAppraisalBars(image)).toBeNull()
    let seed = 12345
    for (let i = 0; i < image.data.length; i += 4) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
      image.data.set([orange, red, gray, [255, 255, 255, 255]][seed >>> 30]!, i)
    }
    expect(detectAppraisalBars(image)).toBeNull()
  })
  it('rejects inconsistent alignment, spacing, widths, and missing bars', () => {
    for (const fault of ['alignment', 'spacing', 'width', 'missing']) {
      const image = raster()
      bar(image, 8, 70)
      bar(image, 10, 100)
      if (fault !== 'missing')
        bar(
          image,
          12,
          fault === 'spacing' ? 145 : 130,
          1,
          fault === 'alignment' ? 45 : 35,
        )
      if (fault === 'width')
        rectangle(image, 185, 130, 6, 6, [255, 255, 255, 255])
      expect(detectAppraisalBars(image)).toBeNull()
    }
  })
  it('rejects gapless red stripes, non-prefix fills and uncertain endpoints', () => {
    const stripes = raster()
    for (const y of [70, 100, 130]) rectangle(stripes, 35, y, 156, 6, red)
    expect(detectAppraisalBars(stripes)).toBeNull()
    for (const fault of ['hole', 'endpoint']) {
      const image = raster()
      for (const y of [70, 100, 130]) bar(image, 8, y)
      if (fault === 'hole') rectangle(image, 45, 100, 10, 6, gray)
      else rectangle(image, 118, 100, 5, 6, orange)
      expect(detectAppraisalBars(image)).toBeNull()
    }
  })
  it('rejects multiple valid triples even when they report identical IVs', () => {
    const image = raster()
    for (const y of [40, 70, 100, 160, 190, 220]) bar(image, 15, y)
    expect(detectAppraisalBars(image)).toBeNull()
  })
  it.each(['blend', 'white', 'transparent', 'wide'])(
    'bridges only narrow opaque fill-to-gray blends: %s',
    (kind) => {
      const image = raster(2)
      for (const [i, iv] of [1, 15, 14].entries())
        bar(image, iv, 70 + i * 30, 2)
      const transition =
        kind === 'white'
          ? [255, 255, 255, 255]
          : [237, 220, 192, kind === 'transparent' ? 0 : 255]
      rectangle(image, 90, 140, kind === 'wide' ? 8 : 2, 12, transition)
      expect(detectAppraisalBars(image)).toEqual(
        kind === 'blend' ? { attack: 1, defense: 15, stamina: 14 } : null,
      )
    },
  )
  it.each([false, true])(
    'requires adjacent core rows within fragments: %s',
    (isolated) => {
      const image = raster()
      for (const y of [70, 100, 130]) {
        bar(image, 8, y)
        for (const row of isolated ? [1, 2, 4, 5] : [2, 4])
          rectangle(image, 35, y + row, 156, 1, [255, 255, 255, 255])
      }
      expect(detectAppraisalBars(image)).toEqual(
        isolated ? null : { attack: 8, defense: 8, stamina: 8 },
      )
    },
  )
  it.each(['joined', 'far', 'different IV', 'thick'])(
    'bounds JPEG core-fragment grouping: %s',
    (fault) => {
      const image = raster()
      for (const y of [70, 100, 130]) {
        bar(image, 8, y)
        rectangle(image, 35, y + 2, 156, 4, [255, 255, 255, 255])
        const lower = y + (fault === 'far' ? 8 : 6)
        bar(image, fault === 'different IV' ? 9 : 8, lower)
        rectangle(image, 35, lower + 2, 156, 4, [255, 255, 255, 255])
        if (fault === 'thick') {
          for (let row = y; row < y + 22; row++) bar(image, 8, row)
        }
      }
      expect(detectAppraisalBars(image)).toEqual(
        fault === 'joined' ? { attack: 8, defense: 8, stamina: 8 } : null,
      )
    },
  )
  it('rejects malformed image dimensions and truncated buffers', () => {
    for (const [width, height] of [
      [0, 0],
      [-1, 10],
      [1.5, 10],
      [Infinity, 10],
      [10, 10],
    ]) {
      expect(
        detectAppraisalBars({
          width: width!,
          height: height!,
          data: new Uint8ClampedArray(0),
        }),
      ).toBeNull()
    }
  })
})
