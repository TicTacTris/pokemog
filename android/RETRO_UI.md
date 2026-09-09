# PokeMog 0.8.0 Retro UI

## Main Screen Integration

Keep the exact "PVP IVs on the GO" heading, existing layout, and small league arcs.
No hero artwork, background patterns, or decorative animation is required.

Version 0.8.0 (versionCode 8) uses **Current stats** for the source form and **After evolution, no power-ups** for evolved forms. Keep these separate from league optima and fully powered-up stats. Show actual possible effective levels and separate base-level / active Buddy alternatives, not a continuous range. IV percentile and percentage of best stat product are distinct metrics. These labels match the manual web calculator; Android remains scan-only, not UI/input feature parity.

The download page uses the same self-hosted `/fonts/Silkscreen-Regular.ttf` and `/fonts/Silkscreen-OFL.txt` paths as the web app, with flat pixel frames rather than rounded pills. Native fonts remain bundled, with no network dependency.

- `PixelShape: Shape` (alias of `RetroShape`) supplies two square steps within each 4dp corner. Pass `shape = PixelShape` to Compose Cards, Buttons, Surfaces, and the drawer.
- Use `BorderStroke(2.dp, MaterialTheme.colorScheme.outline)` for flat framed panels and secondary actions. Set tonal/shadow elevation to zero. Filled primary actions can use `onPrimary` for a contrasting border.
- `PokeMogTheme(dark, content)` installs bundled pixel typography for all fifteen Material roles, preserving their sizes and line heights. This includes body text and small labels. System-owned surfaces retain Android fonts; unsupported glyphs may fall back.
- `pokemogTypography(pixel: FontFamily): Typography` exposes the same role mapping for previews/tests or a custom theme.
- `ShadowToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit)` is unchanged. Do not add a parent click target. Its only target is `shadow-toggle`, with switch role, Shadow description, and On/Off state. A static 7x7 ghost and visible ON/OFF label supplement color.

## Native Overlay

- `retroBackground(fill: Int, outline: Int, density: Float): Drawable` returns a `PixelFrameDrawable` with the same stepped corners and an inset 2dp frame. Density is `resources.displayMetrics.density`, not dpi. It does not provide clipping for child content; keep content padded inside the frame.
- `retroTypeface(context: Context): Typeface` loads and caches the bundled Silkscreen Regular face. There is no runtime font download. All native overlay labels and both valid/unavailable arc states use this face.
- `ShadowStyle.on: ShadowPalette` exposes `fill`, `text`, and `outline`; `ShadowStyle.colors(checked, palette)` resolves ON or normal OFF colors. The existing ten-field `PokeMogPalette` constructor is unchanged.
- `RetroShadowButton(context, checked, enabled, palette, onCheckedChange)` is the native equivalent. Initial binding and programmatic checked-state changes are silent; only enabled activation calls the callback. It reports an Android Switch accessibility class and checkable/checked state, plus explicit On/Off state descriptions on API 30+.
- Both shadow controls use the same surface, text, outline, ghost, 14sp menu label, 21dp glyph, and minimum 48dp touch height. Disabled controls are faded without changing their state.

The style layer does not alter assessment rules (including Shadow's 1.2 equivalent),
window placement, controls, or orb gestures. OCR processing optimizations are documented separately.
The existing scanning-only orb progress animation remains; no animation was added.

The action footer is now `OverlayActionFooter`: add two plain buttons and let it size them from the font metrics. It owns row/stack spacing and 48dp minima. `OverlayCardLayout` explicitly pins it under the scrolling body; do not add the former third Stop button or weighted child widths. Stop remains available through the menu and notification.

## Font Provenance

Bundled unmodified `app/src/main/assets/Silkscreen-Regular.ttf` (32,220 bytes):

- Official source: https://github.com/google/fonts/tree/main/ofl/silkscreen
- Download: https://raw.githubusercontent.com/google/fonts/main/ofl/silkscreen/Silkscreen-Regular.ttf
- SHA-256: `c845473330b94c2079ce9af01c51ac8ba2d99c24f4d14c039843bbb8e642ebd8`
- License: `app/src/main/assets/Silkscreen-OFL.txt`, SIL Open Font License 1.1, Copyright 2001 The Silkscreen Project Authors.
- License source: https://raw.githubusercontent.com/google/fonts/main/ofl/silkscreen/OFL.txt
- The ghost is original code-drawn pixel artwork in `ShadowStyle.ghost`; no external icon asset is used.

Preserve the font and license together in distributions. `RetroUiTest` verifies
offline loading, caching, and bundled license markers. `PokeMogThemeTest` covers text
and frame contrast plus readable body typography. `ShadowToggleTest` covers
whole-button activation, disabled input, semantics, and existing dataset cases.
Native switch binding and activation are covered by `RetroUiTest`.

The main app, drawer and diagnostic controls now use the shared shapes. The native overlay deliberately uses platform widgets on API 29+ with explicit backgrounds/tints, rather than an AppCompat theme; the corresponding custom-view lint recommendation is narrowly suppressed for that control.

Gradle compilation and JVM tests are run by the integration build; device tests remain unexecuted without a connected device/emulator. Check narrow screens and large font settings;
pixel headings are wider than the former system face and should wrap naturally.
