import { test, expect, type Page } from '@playwright/test'

const base = process.env.POKEMOG_BASE_PATH || '/'

test('upgrade retires only the legacy OCR cache', async ({ page }) => {
  await page.goto(`${base}fonts/Silkscreen-OFL.txt`)
  await page.evaluate(async (path) => {
    const old = await caches.open('iv-lab-ocr-v1')
    await old.put(`${path}ocr/legacy-marker`, new Response('old OCR asset'))
    await caches.open('unrelated-preserved-cache')
  }, base)
  await page.goto(base)
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
  })
  await expect
    .poll(() => page.evaluate(() => caches.has('iv-lab-ocr-v1')))
    .toBe(false)
  expect(
    await page.evaluate(() => caches.has('unrelated-preserved-cache')),
  ).toBe(true)
  await page.getByRole('button', { name: 'Open menu' }).click()
  await expect(
    page.getByRole('link', { name: 'Download PokeMog for Android' }),
  ).toHaveAttribute('href', `${base}downloads/index.html`)
})

async function select(page: Page, name: string) {
  const search = page.getByRole('combobox', { name: 'Pokemon species & form' })
  await search.fill(name)
  await page.getByRole('option', { name, exact: true }).click()
}

test('default results show only primary percentiles; technical details are opt-in', async ({
  page,
}) => {
  await page.goto(base)
  await expect(page.getByLabel('Observed CP')).not.toBeVisible()
  await expect(page.getByLabel('Maximum HP', { exact: true })).not.toBeVisible()
  await expect(page.locator('.detail-drawer')).not.toHaveAttribute('open', '')
  await expect(page.getByTestId('percentile')).toHaveCount(2)
  await expect(
    page.getByText('PvP IV percentile', { exact: true }),
  ).toHaveCount(2)
  for (const text of [
    'Effective level',
    'Current stats',
    'ATK',
    'DEF',
    'Rank #',
    '% of best',
  ]) {
    await expect(
      page
        .locator('.results')
        .getByText(text, { exact: false })
        .filter({ visible: true }),
    ).toHaveCount(0)
  }
  await expect(
    page.getByTestId('actual-stats').filter({ visible: true }),
  ).toHaveCount(0)
  await page.locator('.detail-drawer > summary').click()
  await expect(
    page.getByText('Current stats', { exact: true }).first(),
  ).toBeVisible()
  await expect(page.getByTestId('actual-stats').first()).toBeVisible()
  await expect(
    page.getByText('Neither is a win chance.', { exact: false }),
  ).toBeVisible()
  await page.locator('.detail-drawer > summary').click()
  await expect(
    page.getByTestId('actual-stats').filter({ visible: true }),
  ).toHaveCount(0)
})

test('manual calculator, keyboard search and canonical Shadow selection', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.goto(base)
  await expect(
    page.getByText('PVP IVs on the GO', { exact: true }),
  ).toBeVisible()
  await expect(page.locator('input[type=file]')).toHaveCount(0)
  const search = page.getByRole('combobox', { name: 'Pokemon species & form' })
  await search.fill('bulbasaur')
  await search.press('ArrowDown')
  await search.press('ArrowDown')
  await search.press('Enter')
  await expect(search).toHaveValue('Bulbasaur')
  await expect(page.getByRole('switch')).toBeChecked()
  await search.fill('no-such-pokemon')
  await expect(page.getByRole('status')).toContainText('No matching Pokemon')
  await search.press('Escape')
  await expect(search).toHaveValue('Bulbasaur')
  await search.fill('')
  await expect(page.getByRole('listbox').getByRole('option')).toHaveCount(50)
  await search.press('ArrowUp')
  await expect(
    page.getByRole('listbox').getByRole('option').last(),
  ).toHaveAttribute('aria-selected', 'true')
  await search.press('Tab')
  await expect(search).toHaveAttribute('aria-expanded', 'false')
  await select(page, 'Stunfisk (Galarian)')
  await page.getByRole('switch').click()
  await expect(search).toHaveValue('Stunfisk (Galarian)')
  await page.locator('.detail-drawer > summary').click()
  await expect(
    page
      .getByText(
        'Shadow availability / evolution path unverified. Theoretical stats only.',
      )
      .first(),
  ).toBeVisible()
  expect(errors).toEqual([])
})

test('Scatterbug levels, evolution stats, Shadow invariance and settings', async ({
  page,
}) => {
  await page.goto(base)
  await select(page, 'Scatterbug')
  await page.getByLabel('Attack IV').fill('15')
  await page.getByLabel('Defense IV').fill('13')
  await page.getByLabel('HP IV', { exact: true }).fill('13')
  await page.getByText('Advanced settings', { exact: true }).click()
  await page.locator('.detail-drawer > summary').click()
  await page.getByLabel('Observed CP').fill('206')
  await expect(page.locator('.level-note')).toContainText(
    'Current level unknown',
  )
  await page.getByLabel('Maximum HP').fill('66')
  await expect(page.locator('.level-note')).toContainText(
    'Observed effective level 15.',
  )
  await expect(page.locator('.level-alternative')).toHaveText([
    'Without active Buddy boost: base level 15',
    'With active Buddy boost (+1): base level 14',
  ])
  await expect(
    page
      .getByRole('heading', { name: 'After evolution, no power-ups' })
      .first(),
  ).toBeVisible()
  const before = await page.getByTestId('actual-stats').allTextContents()
  const ranks = await page.getByTestId('percentile').allTextContents()
  await page.getByRole('switch').click()
  await expect(
    page
      .locator('.shadow-note')
      .filter({ hasText: 'Shadow ATK equivalent' })
      .first(),
  ).toBeVisible()
  expect(await page.getByTestId('actual-stats').allTextContents()).toEqual(
    before,
  )
  expect(await page.getByTestId('percentile').allTextContents()).toEqual(ranks)
  await expect(
    page.getByLabel('Maximum base level', { exact: true }),
  ).toHaveValue('50')
  await expect(page.getByLabel('Allow future Best Buddy')).not.toBeChecked()
  await page
    .getByLabel('Maximum base level', { exact: true })
    .selectOption('40')
  await page.getByLabel('Allow future Best Buddy').check()
  await expect(page.locator('.level-note')).toContainText(
    'maximum base level 40, with a future Buddy boost',
  )
  await page.getByLabel('Known base level').fill('50')
  await expect(page.locator('.level-note')).toContainText('contradict')
  await page.getByText('Little League / 500 CP', { exact: true }).click()
  await expect(
    page.getByText('Little League cup eligibility varies.', { exact: false }),
  ).toBeVisible()
})

test('invalid numeric strings never silently become zero; no power-down warning', async ({
  page,
}) => {
  await page.goto(base)
  for (const value of ['', '16', '-1', 'NaN', '1.5']) {
    await page.getByLabel('Attack IV').fill(value)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByTestId('percentile')).toHaveCount(0)
  }
  await page.getByLabel('Attack IV').fill('0')
  await expect(page.getByTestId('percentile')).toHaveCount(2)
  await page.getByText('Advanced settings', { exact: true }).click()
  await page.getByLabel('Maximum HP').fill('abc')
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByLabel('Maximum HP').fill('')
  await select(page, 'Dragonite')
  await page.getByLabel('Known base level').fill('50')
  await expect(page.locator('.card-warning').first()).toContainText(
    'cannot power down',
  )
})

test('native menu focus, persistent themes and 320-1440 layout', async ({
  page,
}, testInfo) => {
  await page.goto(base)
  await page.getByRole('button', { name: 'Open menu' }).click()
  const modal = page.getByRole('dialog')
  await expect(modal).toBeVisible()
  await expect(
    modal.getByRole('button', { name: 'Close', exact: true }),
  ).toBeFocused()
  await expect(
    modal.getByRole('link', { name: 'SIL Open Font License' }),
  ).toHaveAttribute('href', `${base}fonts/Silkscreen-OFL.txt`)
  await page.keyboard.press('Escape')
  await expect(modal).not.toBeVisible()
  await expect(page.getByRole('button', { name: 'Open menu' })).toBeFocused()
  await page.getByRole('button', { name: 'Dark theme' }).click()
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  for (const width of [320, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true)
  }
  await page.screenshot({
    path: testInfo.outputPath('desktop-dark.png'),
    fullPage: true,
  })
  await page.getByRole('button', { name: 'Dark theme' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({
    path: testInfo.outputPath('mobile-light.png'),
    fullPage: true,
  })
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

test('offline cache includes calculator, search, pixel font and license', async ({
  page,
  context,
}) => {
  const external: string[] = []
  page.on('request', (r) => {
    if (/^https?:/.test(r.url()) && new URL(r.url()).hostname !== '127.0.0.1')
      external.push(r.url())
  })
  await page.goto(base)
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
    if (!navigator.serviceWorker.controller)
      await new Promise<void>((resolve) =>
        navigator.serviceWorker.addEventListener(
          'controllerchange',
          () => resolve(),
          { once: true },
        ),
      )
  })
  await context.setOffline(true)
  await page.reload()
  await select(page, 'Skarmory')
  await expect(page.getByTestId('percentile')).toHaveCount(2)
  await page.getByLabel('Attack IV').fill('5')
  await page.getByLabel('Attack IV').fill('0')
  expect(
    await page.evaluate(async () => {
      await document.fonts.ready
      return document.fonts.check('12px Silkscreen')
    }),
  ).toBe(true)
  expect(
    await page.evaluate(
      async (path) => (await fetch(`${path}fonts/Silkscreen-OFL.txt`)).ok,
      base,
    ),
  ).toBe(true)
  expect(external).toEqual([])
  await context.setOffline(false)
})

test('unavailable localStorage does not prevent calculations or theme changes', async ({
  page,
}) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'localStorage', {
      get() {
        throw new Error('Storage blocked')
      },
    })
  })
  await page.goto(base)
  await page.getByRole('button', { name: 'Dark theme' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  await expect(page.getByTestId('percentile')).toHaveCount(2)
})

test('public pages, optional tips, fonts and PWA use the deployment base', async ({
  page,
  request,
}) => {
  const failures: string[] = []
  const external: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') failures.push(message.text())
  })
  page.on('pageerror', (error) => failures.push(error.message))
  page.on('response', (response) => {
    if (response.status() >= 400) failures.push(response.url())
  })
  page.on('request', (r) => {
    if (new URL(r.url()).hostname !== '127.0.0.1') external.push(r.url())
  })
  await page.goto(base)
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
    await document.fonts.ready
  })
  expect(
    await page.evaluate(() =>
      [...document.fonts].some(
        (font) => font.family === 'Silkscreen' && font.status === 'loaded',
      ),
    ),
  ).toBe(true)
  await expect(page.getByLabel('PokeMog home')).toHaveAttribute('href', base)
  const manifest = await (
    await request.get(`${base}manifest.webmanifest`)
  ).json()
  expect([manifest.id, manifest.start_url, manifest.scope]).toEqual([
    base,
    base,
    base,
  ])
  for (const icon of manifest.icons)
    expect(icon.src.startsWith(base)).toBe(true)
  expect(
    await page.evaluate(
      async () => new URL((await navigator.serviceWorker.ready).scope).pathname,
    ),
  ).toBe(base)
  await page.getByRole('button', { name: 'Open menu' }).click()
  const tip = page.getByRole('link', { name: 'Optional tips on Ko-fi' })
  await expect(tip).toHaveAttribute('href', 'https://ko-fi.com/tictactris')
  await expect(tip).toHaveAttribute('target', '_blank')
  await expect(tip).toHaveAttribute('rel', 'noreferrer')
  await page.getByRole('link', { name: 'Download PokeMog for Android' }).click()
  await expect(page).toHaveURL(new RegExp(`${base}downloads/index.html$`))
  await expect(
    page.getByRole('link', { name: 'View Android releases' }),
  ).toHaveAttribute(
    'href',
    'https://github.com/TicTacTris/pokemog/releases/tag/v0.10.0',
  )
  await expect(
    page.getByRole('link', { name: 'Download 0.10.0 beta APK' }),
  ).toHaveAttribute(
    'href',
    'https://github.com/TicTacTris/pokemog/releases/download/v0.10.0/pokemog-0.10.0.apk',
  )
  await page.getByRole('link', { name: 'privacy notice' }).click()
  await expect(
    page.getByRole('heading', { name: 'Android OCR and SDK metrics' }),
  ).toBeVisible()
  await page.getByRole('link', { name: 'Calculator', exact: true }).click()
  await expect(page.getByTestId('percentile')).toHaveCount(2)
  expect(failures).toEqual([])
  expect(external).toEqual([])
})

test('missing downloads never become SPA HTML, including worker navigation and HEAD', async ({
  page,
  request,
}) => {
  await page.goto(base)
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready
    if (!navigator.serviceWorker.controller)
      await new Promise<void>((resolve) =>
        navigator.serviceWorker.addEventListener(
          'controllerchange',
          () => resolve(),
          { once: true },
        ),
      )
  })
  for (const path of [
    'downloads/missing.apk?download=1',
    'downloads/missing.apk.sha256?check=1',
    'downloads/missing',
    'downloads/pokemog-debug.apk',
    'downloads/pokemog-debug.apk.sha256?check=1',
    'api/missing?query=1',
    'absent.sha256?check=1',
  ]) {
    const url = `${base}${path}`
    const response = await request.get(url)
    expect(response.status()).toBe(404)
    expect(response.headers()['content-type']).toContain('text/plain')
    expect(response.headers()['cache-control']).toBe('no-store')
    const head = await request.head(url)
    expect(head.status()).toBe(404)
    expect(head.headers()['content-length']).toBe(
      response.headers()['content-length'],
    )
    expect((await head.body()).length).toBe(0)
    expect((await page.goto(url))?.status()).toBe(404)
    await expect(page.locator('#root')).toHaveCount(0)
  }
})
