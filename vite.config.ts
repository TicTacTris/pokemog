import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import { createReadStream } from 'node:fs'
import { stat, readdir, rm } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'

const base = process.env.POKEMOG_BASE_PATH || '/'
if (!/^\/(?:[\w-]+\/)*$/.test(base))
  throw new Error('POKEMOG_BASE_PATH must be / or a path such as /pokemog/')

function downloads(
  request: IncomingMessage,
  response: ServerResponse,
  next: () => void,
  directory = 'public',
) {
  const path = request.url?.split('?')[0] ?? ''
  if (path === `${base}downloads` || path === `${base}downloads/`) {
    response.writeHead(302, { Location: `${base}downloads/index.html` })
    response.end()
    return
  }
  if (
    !path.startsWith(`${base}downloads/`) &&
    !new RegExp(`^${base}api(?:/|$)`).test(path) &&
    !/\.(?:apk|sha256)$/i.test(path)
  )
    return next()
  const name = path.slice(`${base}downloads/`.length)
  const allowed = [
    'index.html',
    'downloads.css',
    'pokemog-debug.apk',
    'pokemog-debug.apk.sha256',
  ]
  const missing = () => {
    const body = 'Download not found. See GitHub Releases.\n'
    response.writeHead(404, {
      'Content-Type': 'text/plain; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
      'Cache-Control': 'no-store',
    })
    response.end(request.method === 'HEAD' ? undefined : body)
  }
  if (
    !path.startsWith(`${base}downloads/`) ||
    !allowed.includes(name) ||
    !['GET', 'HEAD'].includes(request.method ?? '')
  )
    return missing()
  const file = new URL(`./${directory}/downloads/${name}`, import.meta.url)
  void stat(file).then((info) => {
    const type = name.endsWith('.apk')
      ? 'application/vnd.android.package-archive'
      : name.endsWith('.sha256')
        ? 'text/plain; charset=utf-8'
        : name.endsWith('.css')
          ? 'text/css; charset=utf-8'
          : 'text/html; charset=utf-8'
    response.writeHead(200, {
      'Content-Type': type,
      'Content-Length': info.size,
      'Cache-Control': 'no-store',
      ...(name.endsWith('.apk')
        ? { 'Content-Disposition': `attachment; filename="${name}"` }
        : {}),
    })
    if (request.method === 'HEAD') response.end()
    else
      createReadStream(file)
        .on('error', (error) => response.destroy(error))
        .pipe(response)
  }, missing)
}

export default defineConfig({
  base,
  plugins: [
    {
      name: 'download-routing',
      transformIndexHtml(html, context) {
        // Vite's development preamble/HMR needs inline script and WebSocket access.
        return context.server
          ? html.replace(
              /<meta\s+http-equiv="Content-Security-Policy"[\s\S]*?\/>/,
              '',
            )
          : html
      },
      configureServer(server) {
        server.middlewares.use((request, response, next) =>
          downloads(request, response, next),
        )
      },
      configurePreviewServer(server) {
        server.middlewares.use((request, response, next) =>
          downloads(request, response, next, 'dist'),
        )
      },
      async closeBundle() {
        // Binaries belong on Releases, never in the Pages or web ZIP artifact.
        const directory = new URL('./dist/downloads/', import.meta.url)
        for (const name of await readdir(directory)) {
          if (/\.(?:apk|sha256)$/i.test(name))
            await rm(new URL(name, directory))
        }
      },
    },
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: 'script',
      manifest: {
        id: base,
        start_url: base,
        scope: base,
        name: 'PokeMog',
        short_name: 'PokeMog',
        description: 'PVP IVs on the GO',
        theme_color: '#B6422D',
        background_color: '#FBF5EC',
        display: 'standalone',
        icons: [
          { src: `${base}icon-192.png`, sizes: '192x192', type: 'image/png' },
          { src: `${base}icon-512.png`, sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        clientsClaim: true,
        skipWaiting: true,
        globPatterns: ['**/*.{js,css,html,svg,png,woff2,ttf}', 'fonts/*.txt'],
        globIgnores: ['ocr/**', '**/*.apk', '**/*.sha256'],
        importScripts: [`${base}sw-migration.js`],
        navigateFallback: `${base}index.html`,
        navigateFallbackDenylist: [
          new RegExp(`^${base}downloads(?:/|\\?|$)`),
          new RegExp(`^${base}api(?:/|\\?|$)`),
          /\.(?:apk|sha256)(?:\?|$)/i,
        ],
      },
    }),
  ],
})
