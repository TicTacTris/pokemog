import type { IVs, Pokemon } from './calculations'

function normalize(text: string): string {
  return text
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/♀/g, ' female ')
    .replace(/♂/g, ' male ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

/** Exact normalized names only. Dialogue and nicknames are not proof of species or
 * form: these are candidates, never an automatic selection. All forms are retained.
 * English CP/HP labels are required; conflicting readings and OCR digit repairs
 * are deliberately unsupported. HP is maximum HP, not the current injured HP.
 */
export function parseScreenshotText(
  text: string,
  pokemon: Pokemon[],
): {
  candidates: Pokemon[]
  cp: number | null
  hp: number | null
} {
  const words = ` ${normalize(text)} `
  const candidates = pokemon.filter((p) => {
    // The bundled catalog repeats form metadata in parenthesized display suffixes.
    const name = normalize(
      p.name.replace(/(?:\s+\([^()]+\))+$/, (suffix) =>
        p.form && normalize(suffix) === normalize(p.form) ? '' : suffix,
      ),
    )
    return name.length > 0 && words.includes(` ${name} `)
  })
  const number = '(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)'
  const value = `(${number})(?:[ \\t]*/[ \\t]*(${number}))?`
  const readings = (label: 'CP' | 'HP'): number | null => {
    const values: number[] = []
    const patterns =
      label === 'CP'
        ? [new RegExp(`\\bCP[ \\t:]*(${number})(?![\\w,./])`, 'gi')]
        : [
            new RegExp(`\\bHP[ \\t:]*${value}(?![\\w,./]|[ \\t]*/)`, 'gi'),
            new RegExp(`(?<![\\w,./])${value}[ \\t]*HP\\b`, 'gi'),
          ]
    for (const pattern of patterns) {
      for (const match of text.matchAll(pattern)) {
        const current = Number(match[1]!.replace(/,/g, ''))
        const maximum = Number((match[2] ?? match[1])!.replace(/,/g, ''))
        if (!Number.isSafeInteger(maximum) || maximum < 10 || current > maximum)
          return null
        values.push(maximum)
      }
    }
    return values.length && values.every((n) => n === values[0])
      ? values[0]!
      : null
  }
  return { candidates, cp: readings('CP'), hp: readings('HP') }
}

/** A deliberately conservative raster heuristic, not a trained UI recognizer.
 * Requires opaque orange/red fill, neutral pale-gray tracks, two visible gaps
 * dividing each bar into equal thirds, and three equally spaced aligned bars.
 * Rounded edges may be ignored, but there must be at least two stable core rows.
 * Cropping, heavy compression, tinted tracks, tiny bars, overlays and gapless
 * themes can return null. All-zero tracks cannot establish appraisal identity.
 * An unrelated graphic with identical geometry/colors is indistinguishable without
 * label anchors. The real crop regression does not establish general accuracy.
 */
export function detectAppraisalBars(image: {
  data: Uint8ClampedArray
  width: number
  height: number
}): IVs | null {
  const { data, width, height } = image
  if (
    !Number.isSafeInteger(width) ||
    !Number.isSafeInteger(height) ||
    width < 1 ||
    height < 1 ||
    !Number.isSafeInteger(width * height * 4) ||
    data.length !== width * height * 4
  )
    return null

  type Bar = {
    x: number
    end: number
    y: number
    bottom: number
    stable: boolean
    iv: number
    gaps: number[]
  }
  const bars: Bar[] = []
  const color = (x: number, y: number): number => {
    const i = (y * width + x) * 4
    const r = data[i]!,
      g = data[i + 1]!,
      b = data[i + 2]!
    if (data[i + 3]! < 240) return 0
    if (
      r >= 190 &&
      g >= 35 &&
      g <= 195 &&
      b >= 25 &&
      b <= 150 &&
      r - g >= 40 &&
      r - b >= 65
    )
      return 2
    if (r >= 180 && r <= 240 && Math.max(r, g, b) - Math.min(r, g, b) <= 12)
      return 1
    return 0
  }
  for (let y = 0; y < height; y++) {
    const runs: { x: number; end: number; fill: number; valid: boolean }[] = []
    for (let x = 0; x < width;) {
      if (!color(x, y)) {
        x++
        continue
      }
      const start = x
      let fill = 0,
        gray = false,
        valid = true
      while (x < width && color(x, y)) {
        if (color(x, y) === 2) {
          if (gray) valid = false
          fill++
        } else gray = true
        x++
      }
      const previous = runs.at(-1)
      // JPEG chroma bleed at a fill/track boundary is not a segment divider.
      // Bridge only a short, non-white transition from fill into neutral track.
      let oldBridge = true
      if (
        previous &&
        previous.fill === previous.end - previous.x &&
        fill === 0 &&
        start - previous.end <=
          Math.max(
            1,
            (x - previous.x) / 30,
            Math.min(4, (x - previous.x) / 15),
          ) &&
        Array.from({ length: start - previous.end }, (_, j) => {
          const offset = (y * width + previous.end + j) * 4
          const r = data[offset]!,
            g = data[offset + 1]!,
            b = data[offset + 2]!
          oldBridge &&= r <= 245
          return (
            data[offset + 3]! >= 240 &&
            r >= 180 &&
            (r <= 245 || r - b >= 16) &&
            g >= 150 &&
            b >= 100 &&
            r >= g &&
            g >= b
          )
        }).every(Boolean)
      ) {
        oldBridge &&= start - previous.end <= Math.max(1, (x - previous.x) / 30)
        // Newly admitted blends straddle the endpoint. Keep half-pixels rather
        // than treating the entire transition as empty or relaxing IV tolerance.
        if (!oldBridge) previous.fill += (start - previous.end) / 2
        previous.end = x
      } else runs.push({ x: start, end: x, fill, valid })
    }
    for (let i = 0; i + 2 < runs.length; i++) {
      const parts = runs.slice(i, i + 3)
      const first = parts[0]!,
        second = parts[1]!,
        third = parts[2]!
      const lengths = parts.map((p) => p.end - p.x)
      const length = lengths.reduce((a, b) => a + b, 0)
      const gaps = [second.x - first.end, third.x - second.end]
      if (
        length < 90 ||
        // Segment boundaries are integer pixels, including after JPEG resampling.
        Math.max(...lengths) - Math.min(...lengths) >
          Math.max(2, Math.round(length / 90)) ||
        gaps.some((g) => g < 1 || g > length / 30) ||
        Math.abs(gaps[0]! - gaps[1]!) > Math.max(1, length / 150) ||
        parts.some((p) => !p.valid)
      )
        continue
      let gray = false,
        valid = true
      for (const p of parts) {
        if (gray && p.fill > 0) valid = false
        if (p.fill < p.end - p.x) gray = true
      }
      const raw = (parts.reduce((sum, p) => sum + p.fill, 0) / length) * 15
      const iv = Math.round(raw)
      // Do not round a visibly intermediate endpoint to a convenient IV.
      if (!valid || Math.abs(raw - iv) > 0.18) continue
      const previous = bars.find(
        (b) =>
          // JPEG rounding can erase two additional core rows between fragments.
          y - b.bottom <= Math.max(1, length / 50) + 2 &&
          Math.abs(b.x - first.x) <= Math.max(1, Math.ceil(length / 100)) &&
          Math.abs(b.end - third.end) <= Math.max(1, Math.ceil(length / 100)) &&
          b.iv === iv &&
          b.gaps.every(
            (g, j) => Math.abs(g - gaps[j]!) <= Math.max(1, length / 150),
          ),
      )
      if (previous) {
        // Keep the widest core geometry, not each rounded/antialiased row fragment.
        // Missing rows may join fragments, but cannot establish stability alone.
        previous.stable ||= previous.bottom === y - 1
        previous.bottom = y
        if (third.end - first.x > previous.end - previous.x) {
          previous.x = first.x
          previous.end = third.end
          previous.gaps = gaps
        }
      } else
        bars.push({
          x: first.x,
          end: third.end,
          y,
          bottom: y,
          stable: false,
          iv,
          gaps,
        })
      // Excessive competing structures are noise, not a reason to guess.
      if (bars.length > 200) return null
    }
  }
  const stable = bars.filter(
    (b) => b.stable && b.bottom - b.y + 1 <= (b.end - b.x) / 10,
  )
  const matches: IVs[] = []
  for (let a = 0; a < stable.length; a++) {
    for (let b = a + 1; b < stable.length; b++) {
      for (let c = b + 1; c < stable.length; c++) {
        const triple = [stable[a]!, stable[b]!, stable[c]!].sort(
          (l, r) => l.y - r.y,
        )
        const [top, middle, bottom] = triple as [Bar, Bar, Bar]
        const w = top.end - top.x,
          h = Math.max(...triple.map((bar) => bar.bottom - bar.y + 1))
        if (
          triple.some(
            (bar) =>
              Math.abs(bar.x - top.x) > Math.max(1, w / 150) ||
              Math.abs(bar.end - top.end) > Math.max(1, w / 150) ||
              bar.gaps.some(
                (g, j) => Math.abs(g - top.gaps[j]!) > Math.ceil(w / 150),
              ),
          )
        )
          continue
        // Different fill colors expose different core heights after resampling.
        const spacing = (middle.y + middle.bottom - top.y - top.bottom) / 2
        if (
          spacing < h * 2 ||
          spacing > w * 0.6 ||
          Math.abs(
            (bottom.y + bottom.bottom - middle.y - middle.bottom) / 2 - spacing,
          ) > Math.max(2, h / 2)
        )
          continue
        if (triple.every((bar) => bar.iv === 0)) continue
        matches.push({ attack: top.iv, defense: middle.iv, stamina: bottom.iv })
        if (matches.length > 1) return null
      }
    }
  }
  return matches[0] ?? null
}
