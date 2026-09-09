// Retire only the former web OCR runtime cache, leaving user storage intact.
self.addEventListener('activate', (event) => {
  event.waitUntil(caches.delete('iv-lab-ocr-v1'))
})
