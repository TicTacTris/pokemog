# Development

All paths below are relative to the repository. See the root README for web setup and root/subpath verification commands. Install Playwright Chromium and its supported system dependencies if needed (`npx playwright install --with-deps chromium`).

## Source Map

| Location                                                           | Purpose                                                          |
| ------------------------------------------------------------------ | ---------------------------------------------------------------- |
| `src/App.tsx`, `src/App.css`, `src/index.css`                      | Manual calculator UI, local pixel font, theme and disclosures    |
| `src/PokemonSearch.tsx`                                            | Accessible species/form search                                   |
| `src/lib/calculations.ts`, `src/lib/projections.ts`                | Stats, rankings, level/Buddy inference and evolution projections |
| `src/data/`                                                        | Pinned data, provenance and PvPoke license                       |
| `src/lib/*.test.ts`, `e2e/app.spec.ts`                             | Unit and desktop/mobile browser regression tests                 |
| `vite.config.ts`, `public/sw-migration.js`                         | Base-aware PWA, scoped legacy cache cleanup and download routing |
| `public/downloads/`, `public/privacy/`                             | Static release landing page and privacy notice                   |
| `scripts/prepare-web-assets.mjs`                                   | Self-hosted font/license and generated pixel icons               |
| `android/app/src/main/java/dev/pokemog/android/`                   | Native calculations, scanning, overlay and updater               |
| `scripts/prepare-web-data.mjs`, `scripts/prepare-android-data.mjs` | Shared data/parity generation                                    |

## Calculation Invariants

- Blank IVs are unknown, never zero. Match the exact form's stats and special rules.
- Shadow is independent of form identity. Damage dealt/received is multiplied by 1.2; CP, HP, actual ATK/DEF and IV ranking remain unchanged. Label the damage-adjusted ATK equivalent separately. Unverified Shadow paths must stay unverified.
- Competition ranks cover eligible spreads among 4,096 IV combinations. Percentile is `100 * (eligibleSpreads - rank) / (eligibleSpreads - 1)`, or 100% for a singleton. Percentage of best stat product is a different quantity. Neither predicts wins.
- Keep discrete effective levels and `(activeBuddy, baseLevels)` alternatives; do not turn separate possibilities into continuous ranges. Current stats, after-evolution/no-power-up stats, league optimum and fully powered-up stats are distinct. Pokemon cannot power down.
- Shared-form calculations do not identify a form. Require complete valid evidence, matching name groups/stats/rules, known metadata and identical forward identities and Shadow paths. Retain unresolved candidate identities and labels; a representative is calculation-only. Unknown metadata is not a final evolution.
- Default projections use maximum base level 50 with no future Buddy boost. Web and Android share specifications/parity data, not identical input features.

## Data Contract

`npm run data:update` imports a pinned revision, not upstream HEAD. Review provenance and shared evolution validation before adopting new data. Preserve PvPoke MIT and font OFL notices. Native data packs may legitimately be newer than bundled fixtures and differ in record count.

The separate [data repository](https://github.com/TicTacTris/pokemog-data) publishes data, not APKs. Preserve manifest/schema compatibility, bounded HTTPS downloads, hash validation, catalog/CPM/evolution invariants, atomic activation and last-good/bundled fallback. Existing results retain immutable snapshots. Transport and hashes are not an independent publisher signature. Read [DATA_ANDROID.md](../DATA_ANDROID.md) before modifying the contract.

## Android Checks

Configure JDK 17 through `JAVA_HOME` and Android SDK 35 through standard SDK configuration. From `android/`:

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

`POKEMOG_DATA_SMOKE=1` opts into live data-release validation. Compiled instrumentation is not executed instrumentation. Device tests are needed for consent, Stop, overlay geometry, OCR accuracy/latency, rotation, large fonts, auto-scan and diagnostic deletion. Preserve cancellation, stale-generation protections and bitmap ownership. Never publish debug keys or private diagnostic evidence.

## Web Publishing

`POKEMOG_BASE_PATH` defaults to `/`; use `/pokemog/` for Pages. Vite rewrites the application's public absolute font URL with the configured base. Static public HTML/CSS uses relative links. Manifest identity, start URL, scope, icons, migration script and navigation fallback share the base. Only the named legacy OCR cache is retired; unrelated origin caches remain intact.

Builds remove APK/checksum copies from generated `dist/downloads/`, never from `public/downloads/`. Keep APKs separate from the website ZIP and Pages artifact. Local debug downloads are intentionally not the public default. The preview middleware is a local convenience, not GitHub Pages infrastructure. Pages cannot use that middleware or repository-defined response headers; see the security review.
