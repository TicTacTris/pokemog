# Data Sources

The bundled Pokemon GO base stats and CP multipliers come from [PvPoke](https://github.com/pvpoke/pvpoke), copyright (c) 2019 pvpoke, licensed under MIT.

Pinned revision: `04ee0835e80f30c45376415725c0039cbdd12c5e` (2026-09-03). Retrieved 2026-09-08.

| Bundled file | Exact upstream source |
| --- | --- |
| `src/data/pokemon.json` | [src/data/gamemaster.json](https://github.com/pvpoke/pvpoke/blob/04ee0835e80f30c45376415725c0039cbdd12c5e/src/data/gamemaster.json) |
| `src/data/cp-multipliers.json` | The `cpms` array in [src/js/pokemon/Pokemon.js](https://github.com/pvpoke/pvpoke/blob/04ee0835e80f30c45376415725c0039cbdd12c5e/src/js/pokemon/Pokemon.js) |
| `src/data/PVPoke-LICENSE.txt` | Unmodified [LICENSE](https://github.com/pvpoke/pvpoke/blob/04ee0835e80f30c45376415725c0039cbdd12c5e/LICENSE) |

## Reproduction

Run `node scripts/import-pvpoke.mjs` with Node 18+ and network access. The script downloads only the pinned revision, validates the extracted data, and generates the three files above. No runtime network requests are needed.

The dataset contains 1,681 entries. `speciesId` and the full `speciesName` become `id` and `name`; parenthesized name qualifiers become optional `form` (multiple qualifiers joined by comma). `baseStats.atk/def/hp` become `attack/defense/stamina`. Mega, Super Mega, and Primal tags and ID segments are excluded. Regional, size, battle, and Shadow forms remain distinct. Unreleased entries and upstream aliases are retained; inclusion does not imply current in-game availability. Names preserve upstream spelling and Unicode.

The multiplier array contains the first 101 upstream values, indexed by `(level - 1) * 2`, covering every half-level from 1 through 51. Values are not interpolated or rounded. Levels beyond 51 in the upstream table are intentionally omitted.

## Engine Contract

Import functions and types from `src/lib/calculations.ts` or `src/lib/index.ts`. Import the default JSON array from `src/data/pokemon.json`; it is assignable to `Pokemon[]`.

- `stats(pokemon, ivs, level)` returns `{ cp, hp, attack, defense, statProduct }`. CP is `max(10, floor((baseAttack + attackIV) * sqrt(baseDefense + defenseIV) * sqrt(baseStamina + staminaIV) * CPM^2 / 10))`. HP is `max(10, floor((baseStamina + staminaIV) * CPM))`, except Shedinja's fixed 10 HP. Effective attack and defense are not rounded. Stat product is raw `attack * defense * hp`, not divided by 1,000.
- `ivPercent(ivs)` returns the unrounded percentage from 0 to 100, `(attack + defense + stamina) / 45 * 100`.
- `rankIVs(pokemon, cpCap, maxLevel)` considers all 4,096 spreads (no encounter IV floors). Each eligible spread appears once at its highest half-level from 1 through `maxLevel` with CP at or below the cap. Ineligible spreads are omitted; no eligible spreads returns `[]`. Results contain `{ ivs, level, cp, hp, attack, defense, statProduct, rank }`, sorted by descending raw stat product, then ascending attack/defense/stamina IV. Exactly equal unrounded stat products share competition ranks (`1, 1, 3`); no tolerance groups nearly equal products.
- `findIVs(pokemon, cp, hp, minLevel, maxLevel)` searches all 4,096 spreads at every half-level in the inclusive range, returning every exact match as `{ ivs, level, cp, hp }`. Order is ascending level, attack IV, defense IV, stamina IV. Multiple matching levels are retained. No matches returns `[]`.
- Invalid inputs throw `RangeError`: levels must be finite half-levels from 1 to 51; IVs must be integers from 0 to 15; CP, HP, and CP caps must be safe integers at least 10; reversed level ranges are invalid. Pokemon require nonempty string ID/name and positive safe-integer base stats. Functions do not mutate inputs.

Level 51 represents the Best Buddy boost. These are CP/HP and stat-product calculations, not battle simulations: Shadow damage modifiers, moves, encounter restrictions, and species-specific acquisition levels are not modeled. A high finite CP cap (for example 10000) can represent an uncapped league; `Infinity` is rejected.

Tests live in `src/lib/calculations.test.ts` and use Vitest. Run `npm test` to test the engine independently of the UI build configuration. The upstream data license is also bundled in the application and accessible in the footer.

## MIT License

The full upstream notice is preserved here and in `src/data/PVPoke-LICENSE.txt`:

```text
MIT License

Copyright (c) 2019 pvpoke

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Pokemon and Pokemon GO names and related trademarks belong to their respective owners. PvPoke's software license does not grant rights to those trademarks. This project is not an official Pokemon GO service.

The cropped Pokemon GO appraisal PNGs in `src/lib/fixtures/`, their Android
instrumentation copy, and derived pixel fixtures contain third-party copyrighted
game UI, not PvPoke-licensed artwork. They are retained for regression testing and
are not covered by the project's MIT license or a new redistribution grant. See
`DATA_ANDROID.md` for the fixture scope and privacy requirements. Dependency terms
are separate as well: Android includes proprietary Google ML Kit; see
`THIRD_PARTY_NOTICES.md`.
