import { copyFile } from 'node:fs/promises'

// The same bundled/licensed font is also a resource for platform-themed app text.
await copyFile(
  new URL('../android/app/src/main/assets/Silkscreen-Regular.ttf', import.meta.url),
  new URL('../android/app/src/main/res/font/silkscreen.ttf', import.meta.url),
)
