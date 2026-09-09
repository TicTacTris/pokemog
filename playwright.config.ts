import { defineConfig, devices } from '@playwright/test'

const baseURL = `http://127.0.0.1:4173${process.env.POKEMOG_BASE_PATH || '/'}`

export default defineConfig({
  testDir: './e2e',
  use: { baseURL },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1',
    url: baseURL,
    reuseExistingServer: false,
  },
})
