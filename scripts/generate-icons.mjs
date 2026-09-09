import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const svg = await readFile(
  new URL('../public/favicon.svg', import.meta.url),
  'utf8',
)
const browser = await chromium.launch()
try {
  for (const size of [192, 512]) {
    const page = await browser.newPage({
      viewport: { width: size, height: size },
    })
    await page.setContent(
      `<style>body{margin:0;background:#244d40}svg{width:100%;height:100%}</style>${svg}`,
    )
    await page.screenshot({
      path: new URL(`../public/icon-${size}.png`, import.meta.url).pathname,
    })
    await page.close()
  }
} finally {
  await browser.close()
}
