# PokeMog

**PVP IVs on the GO**

PokeMog is a passion project. A local-first Pokemon GO PvP IV calculator with a manual-entry web app and a separate scan-first Android app.

Free to use. All features are available without payment.

- [Web calculator](https://tictactris.github.io/pokemog/)
- [Android downloads](https://tictactris.github.io/pokemog/downloads/index.html)
- [GitHub releases](https://github.com/TicTacTris/pokemog/releases/tag/v0.10.0)
- [Privacy](https://tictactris.github.io/pokemog/privacy/)

## Beta Status

**0.10.0 beta** uses a dedicated release signing key and a non-debuggable Android build. Downloads include the signed APK, the matching Pages web build and SHA-256 checksums. Existing debug-signed installations of `dev.pokemog.android` must be uninstalled first, losing local settings and data. Stop the old overlay before switching. Android 10+ is required. Beta results should be checked, not treated as authoritative; physical-device release validation remains outstanding.

## Use

1. Select the exact species/form and enter Attack, Defense and HP IVs (0-15). Blank IVs are unknown, not zero.
2. Read Great and Ultra League PvP IV percentiles. These compare eligible IV spreads of the same form, not win chance or species strength.
3. Open Advanced settings for observed CP and maximum HP, known base level, current Buddy status, maximum base level and future Best Buddy boost.
4. Expand Details for discrete level/Buddy alternatives, evolution projections, Little League, actual stats, rank and percentage of best stat product.

Pokemon cannot power down. Shadow affects damage dealt/received, not CP, HP, actual stats or IV rank. Missing evolution metadata does not prove a final evolution; Shadow availability may remain unverified. Cup eligibility, encounter IV floors, conditional evolutions, costs and move simulations are not enforced. Bundled data may include unreleased forms.

The browser is manual-only, with no OCR or screenshot upload. Android uses on-device ML Kit recognition and independent Shadow edits; it is not identical to the web UI. Compatible unresolved forms may share calculations without claiming an exact identity. Auto-scan is optional and off by default. Prefer app-only screen sharing and stop full-screen capture before visiting unrelated apps.

## Privacy

No accounts, ads or first-party telemetry backend. Browser calculations stay local; theme preference uses localStorage and the PWA caches public assets. Android OCR input/output is processed on-device, but Google ML Kit can send operational/usage metrics to Google. GitHub receives connection metadata, including IP addresses, for website and data-update requests. Internet permission is not isolated per SDK. Diagnostic sharing is manual and can expose private content; inspect before sharing. See the [privacy notice](public/privacy/index.html) for best-effort retention and deletion limits.

## Optional Support

Ko-fi is strictly for optional tips. Tips are never required to download, use, or access any feature, and do not unlock functionality or promise support.

<a href="https://ko-fi.com/tictactris" target="_blank" rel="noreferrer">Optional tips on Ko-fi</a>

No embedded donation widget or tracker is used. Following the link opens an external service governed by its own privacy policy.

## Develop

Requires Node.js 22.12+.

```sh
npm ci
npm run dev
npm test
npm run lint
npm run build
npx playwright install chromium
npm run test:e2e
```

The default base is `/`, including LAN development. For GitHub Pages:

```sh
POKEMOG_BASE_PATH=/pokemog/ npm run build
POKEMOG_BASE_PATH=/pokemog/ npm run test:e2e
```

The Pages workflow tests and deploys `dist/` to `https://tictactris.github.io/pokemog/`. APKs and checksums are excluded from website output and published as separate GitHub Release assets. The released web ZIP uses the `/pokemog/` base; host it at that path. To host at `/`, rebuild with the default base. Serve builds over HTTP(S), not `file://`. PWA installation/offline behavior requires HTTPS or localhost and an initial online visit. For deliberate LAN testing only, use `npm run dev -- --host 0.0.0.0`; a development server is not public hosting.

See [development guidance](docs/DEVELOPMENT.md), [contributing](CONTRIBUTING.md), [data sources](DATA_SOURCES.md) and [native data contract](DATA_ANDROID.md). Browser tests do not establish Android capture accuracy or device behavior. Current security scope and pending release checks are in [Security Review](docs/SECURITY_REVIEW.md).

## License And Rights

PokeMog's original code is available under the [MIT License](LICENSE), copyright 2026 TicTacTris. Android includes proprietary Google ML Kit, so the complete app is **not fully FOSS**. Preserve the bundled PvPoke MIT and Silkscreen SIL OFL notices. See [Third-Party Notices](THIRD_PARTY_NOTICES.md).

Pokemon, Pokemon GO and Pokeball names/designs remain subject to their respective owners' rights. Original pixel artwork does not grant trademark rights. This is an unofficial fan beta, not affiliated with, authorized by or endorsed by Nintendo, The Pokemon Company, Game Freak, Creatures or Niantic. Fan use or a fair-use disclaimer is not a legal guarantee; trademark/artwork review remains a publication consideration.

Report sensitive issues through [GitHub private vulnerability reporting](https://github.com/TicTacTris/pokemog/security/advisories/new), not public issues containing secrets. See [SECURITY.md](SECURITY.md).
