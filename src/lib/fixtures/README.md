# Appraisal Regression Fixtures

## Giratina JPEG Regression

Expected Attack/Defense/HP IVs: **8/11/6**. The original 540 x 1170 JPEG
was decoded independently by FFmpeg and JDK 17 ImageIO before cropping at
**(50, 875, 215, 150)**. `giratina-ffmpeg-appraisal.png` and
`giratina-imageio-appraisal.png` preserve each decoder's actual pixels, not
redrawn tracks or a JPEG recompression of a PNG. The crop was visually inspected:
only Attack/Defense/HP labels, three complete bars and panel background remain.
No full screenshot, private source path, caught date/location or metadata is stored.

```sh
node scripts/import-giratina-fixtures.mjs /path/to/source.jpg /path/to/ffmpeg /path/to/java
```

The importer validates dimensions, verifies lossless PNG and signed ARGB RLE
round trips, and writes matching `appraisal-giratina-{ffmpeg,imageio}.json`
Android test resources. ImageIO pixels are JVM-decoded, not Android BitmapFactory
pixels; passing these fixtures does not establish on-device decoder parity.

JPEG warm fill-to-track transitions can split a segment and undercount its fill.
Newly admitted narrow transitions contribute half their width (including odd
half-pixels); existing bridge behavior and the 0.18-IV endpoint tolerance remain.
Two-pixel core-center spacing quantization is allowed. ImageIO also splits Defense
into stable fragments separated by four missing rows, so grouping permits two
additional missing core rows while retaining same-IV, alignment, segment-gap and
maximum-height checks. Both test suites cover duplicate/missing/shifted bars,
synthetic transition/spacing boundaries, separated or conflicting fragments and
overly thick bars.

Manual verification of the full original JPEG passed through the web detector
with FFmpeg and Chromium canvas pixels, and through freshly compiled Kotlin with
ImageIO pixels. Chromium JPEG recompression at qualities 95 and 80 also returned
8/11/6; quality 90 returned null. Compression quality is not a monotonic guarantee
of recognizable geometry. No Android device/instrumentation run was available.

`s23-ultra-appraisal.png` is a native-resolution crop of the appraisal bars from
a user-provided Galaxy S23 Ultra JPEG. It contains only the bar panel, excluding
the Pokemon screen, capture date, and location. Expected Attack/Defense/HP: 1/15/14.

The source screenshot is 1440 x 2963. The crop starts at (150, 2160) and is
540 x 405 pixels. Preserve original JPEG pixel artifacts; do not redraw the bars.
Tests also exercise the app's downscaling and additional JPEG compression.

## Shinx Capture Regression

Expected Attack/Defense/HP: **4/12/15** for both actual-pixel crops:

| Fixture | Source dimensions | Crop (x, y, width, height) |
| --- | --- | --- |
| `shinx-native-appraisal.png` | 1440 x 3088 | (140, 2270, 560, 340) |
| `shinx-analyzed-appraisal.png` | 933 x 2000 | (90, 1470, 360, 220) |

The user authorized these crops from `captured.png` and `analyzed.png`.
The analyzed image is the actual sRGB/opaque/bilinear/2000px preprocessing
output, not a test-generated resize of the native crop. Both crops were visually
inspected: only the three bars, Defense/HP labels, and panel background remain.
No caught location, date, full screenshots, or source metadata are stored.
The Attack label is outside the specified crop; all three bars are intact.

Regenerate from the private source files with:

```sh
node scripts/import-shinx-fixtures.mjs /path/to/captured.png /path/to/analyzed.png
```

The script checks exact source dimensions and crop bounds before writing,
copies decoded RGBA pixels without resizing or redrawing, verifies the PNG
round trip, and verifies every pixel of the signed ARGB run-length encoding.
It also writes `android/app/src/test/resources/appraisal-shinx-native.json`
and `appraisal-shinx-analyzed.json`, using the existing fixture schema:
`source`, `sampling`, `width`, `height`, `expected`, and flat `[count, argb, ...]`
`runs`. No private source paths or metadata are included in generated files.

Before the fix, `npm test` with these regressions reported native 4/12/15 but
analyzed null (63 passed, 1 failed). Rounded lower Attack/Defense row fragments
crossed the fractional horizontal grouping tolerance and produced an extra
matching triple. Rounding only the grouping x/end tolerances upward keeps those
fragments together; the multiple-panel rejection rule is unchanged. Both web
and Kotlin tests cover the unmodified crops plus duplicated identical panels,
removed HP bars, and HP bars shifted horizontally by 10 pixels. The mutations
are generated in memory, not stored as extra fixtures.
