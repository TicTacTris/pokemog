# PokeMog Android 0.9.1

A scan-first Kotlin/Jetpack Compose app for Android 10+ (API 29), targeting API 35. Version 0.9.1 / versionCode 11 retains application ID `dev.pokemog.android`. Updating an existing installation in place requires the same signing key; a local debug build may use a different key. Stop capture before updating. Pre-0.9.0 builds used a different package and require a fresh install without transferring preferences, permissions or cached data. Android sources live in `android/` relative to the repository root. The web reference calculator remains available independently.

## Scan-Only Interface

- Home shows **PVP IVs on the GO**, one **Start/Stop overlay** toggle, **Import screenshot**, and scan results. The decorative home icon and promotional text are removed; the launcher and floating Pokeball artwork remain.
- The hamburger drawer contains Dark mode, optional Auto-scan, instructions, calculation explanations, privacy/diagnostics, and About/licenses. Auto-scan defaults off. General notices no longer fill the home/results screen. Result-specific failures and over-cap/uncertain-level labels remain visible.
- No manual species, IV, CP, HP, level, or league entry and no confirmation form. Complete scans go directly to clearly labeled **scanned estimates**.
- **Shadow** is the only editable calculation input and is available for every identified form and approved calculation-equivalent group, even without a separate Shadow catalog record. It is one whole-button toggle, with no checkbox square: ON turns the whole control deep purple with a lavender pixel border, a static ghost, and an explicit ON label. Other unknown/ambiguous identities cannot be assigned calculations.
- Shadow uses canonical base stats and level inference, so the condition cannot change CP, HP, real Attack/Defense, IV rank, or percentages. Exact matching Shadow records supply display names/IDs where available; otherwise the canonical form receives a hypothetical damage-modifier projection. Missing Shadow records or unconfirmed evolution paths are labeled **Shadow availability unverified** in Details. This does not claim those forms are obtainable in-game.
- Missing bars remain unknown, never zero. Unresolved forms normally show candidate names and a rescan message; the app does not identify the first candidate. The narrowly checked shared-calculation exception below preserves unresolved identity.
- Complete CP/HP and IV readings narrow species/form candidates using level consistency. Both active-buddy and unboosted scenarios are considered; base-level ambiguity is retained rather than guessed.
- Compact Great/Ultra home cards show one **PvP IV percentile** per arc. Name, IV spread, Shadow control and essential conditional warnings remain visible. Rank and stat-product percentage are available only in Details.
- **Current CP, maximum HP, effective level**, actual battle stats and base/Buddy alternatives now live only in explicitly expanded Details. The header no longer shows technical stats. CP/HP parsing, inference and all calculations are unchanged. The drawer's Scan details page remains removed; result Details and failed-capture inspection remain.
- Little League, evolved builds, and fully powered-up stats sit behind **Details**. Version 0.8.0 labels the source form **Current stats** and evolved forms **After evolution, no power-ups**, separately from league optima and maximum builds. Ambiguous effective levels list actual alternatives, not an implied continuous range; base-level alternatives are grouped by active Buddy boost in details and text summaries.
- Details groups have thin theme-aware borders and section dividers for Pokemon names, league builds, current stats, and maximum stats. Open details remain open during a Shadow update; committed results remain internally consistent while recalculating.
- Read-only build assumptions: maximum base level **50**, no future Best Buddy boost. Unknown current level or uncertain reachability is shown explicitly. A theoretical optimum is not a recommendation to power a Pokemon down.

### Percentages

PvP IV percentile is `100 * (eligibleSpreads - rank) / (eligibleSpreads - 1)`, using competition ranks for ties. Rank 1 is 100%; a sole eligible spread is 100%; no eligible build has no percentage. Stat-product percentage is the selected spread's optimal stat product divided by that form's best stat product. They are separate metrics, not overall species-strength or win-rate ratings. Both use two decimal places.

Shadow applies a x1.2 **damage-adjusted Attack equivalent**, not an actual Attack, CP, HP, or IV-rank bonus. Shadow also receives 20% more damage. Actual move damage still depends on moves, the opponent, and rounding.

## Shared Form Calculations (0.8.1)

Main and overlay headers, including the compact result panel before expanding Details, show **Form unresolved; shared IV calculations**. Details repeat the label and list candidate names/IDs. The source projection uses the generic species name with `(form unresolved)`, never the calculation representative's form label. `ScanSummary.pokemon` remains null, while `unresolvedCandidateIdentities` retains the sorted canonical candidates. The original `ScanResult.candidates` and raw auto-scan calibration evidence are not replaced.

The reusable policy requires complete valid IVs, CP and maximum HP compatible with every remaining candidate; the same normalized catalog base name (only parenthesized form suffixes removed); identical Attack/Defense/Stamina and species-specific HP rule; known evolution metadata for every reachable node; and identical ordered forward target IDs. Shared nonterminal roots must therefore lead to the same actual descendant identities, not merely equal-stat alternatives. Linked Shadow roots must all exist or all be absent, and existing Shadow graphs must be known and have identical forward IDs. Malformed links reject grouping. Both switch states are checked before allowing either, so Shadow availability never chooses a form. Missing Shadow records/paths still display unverified availability. Metadata omissions remain unknown, not terminal.

The reported **10/11/15, CP 1436, maximum HP 117** scan now shares results across `toxtricity`, `toxtricity_amped`, and `toxtricity_low_key`, including subsets with only named forms. Effective level is **20**, with separate unboosted base **20** and active-Buddy base **19** alternatives. The same policy covers other catalog groups, including Meowstic, with no Toxtricity-specific production branch. Different stats/rules, unrelated species names, divergent evolution/Shadow paths, missing metadata and invalid/incomplete shared-group readings still reject. A single identified form retains the existing unknown-level theoretical-potential policy.

No capture/OCR thresholds, image heuristics, manual form picker, or private full-screen fixtures were added. Regression evidence consists of parsed readings and bundled catalog records. Phone capture/OCR and native layout behavior still need on-device verification.

## Compressed JPEG Handling

The supplied 540x1170 Giratina JPEG failed because JPEG color blends split partially filled segments, then underestimated their fill endpoints. Version 0.5.0 retains the original bridge rules and adds a bounded warm-transition fallback, preserving fractional edge contributions rather than globally relaxing the 0.18-IV endpoint check. It also tolerates two pixels of center-spacing disagreement and short interruptions in otherwise consistent core rows, while preserving adjacency, alignment, prefix-fill, maximum-height, and multiple-panel rejection checks.

The exact original file returns **8/11/6** with FFmpeg, ImageIO/Kotlin, and Chromium decoding in the developer checks. Its visible **1820 CP / 173 HP** is consistent with effective level **20**. Lossless appraisal-only crops from independent decoders are retained as regression fixtures; no location/date panel or full private screenshot is included. Shinx and earlier S23 cases remain covered. This is not universal JPEG support: aggressive recompression or ambiguous edges may still return Unknown instead of a guessed IV.

Native text parsing also corrects the two historical fixture expectations `CP\n123` and `HP\n100`. `scripts/update-android-text-fixture.mjs` records those intentional changes while preserving the other baseline cases. Spatial parsing is tested separately from text-only fixture parity.

## Overlay

### Start/Stop Toggle

The home button observes process-local `OverlaySession` state rather than assuming a tap started capture:

| State | Button | Description |
| --- | --- | --- |
| Off | Start overlay | Tap to enable the floating scanner. |
| Permission pending | Starting... | Complete the Android permission prompt. |
| Service starting | Starting... | Starting scanner... |
| On | Stop overlay | Scanner active. Tap to stop. |
| Stopping | Stopping... | Ending screen capture. |

Repeated starts are blocked. Service startup, notification/overlay Stop, projection revocation, and cleanup update the same state. Request IDs reject stale callbacks. The permission request ID survives Activity recreation, but capture tokens are not persisted. A process-scoped watchdog handles a dispatched service startup that does not complete within 12 seconds; it does not time out a user reading a permission prompt. A fresh process starts Off.

The idle overlay is a **pixel-art Pokeball within a 48dp art area / 56dp touch target**, not a rectangular control panel. An original 16x16 pattern is shared with the adaptive launcher icon and a monochrome notification silhouette. Whole-pixel rendering keeps the overlay artwork crisp; only the progress ring animates.

1. Start the overlay from PokeMog. Grant appear-on-top permission and return, then approve Android screen capture. Allow notifications for an easily accessible Stop action.
2. Select Pokemon GO for single-app sharing where available, or explicitly share the screen. Open the appraisal screen.
3. **Tap the icon once** to scan. The overlay briefly hides before acquiring a fresh frame. A subtle progress ring indicates work; tap while busy to cancel.
4. Results appear in a panel centered horizontally and anchored within the **bottom half of the usable screen**, above navigation/cutout insets. The Pokeball remains independently draggable; opening or closing results does not move it. Only the Shadow button changes calculation inputs.
5. Use **Rescan**, **Collapse**, or the details expander. **Drag** the icon to reposition it; it snaps to the nearest edge. **Long press** for Stop and the last result. Stop is also available in the notification and results panel.

Great/Ultra are represented by distinct league badges and CP-style **semicircular percentage arcs**. The arc represents **PvP IV percentile**, not in-game CP level progress or win chance. It is the only percentage on each default card, with IV numbers beside the Pokemon name. Rank, projected CP, all levels, stat-product percentage, HP, Attack/Defense, evolutions and maximum builds are under closed-by-default Details. Form unresolved, Shadow unverified and conditional feasibility warnings remain visible. An unavailable build has no percentage fill, rather than a fake zero result.

The results panel is at most 380dp wide and no taller than the usable lower half, with its body scrollable for large fonts or landscape. The footer now explicitly measures and places two pinned actions, **Rescan** and **Collapse**, before assigning the remaining height to results. Natural pixel-font label widths determine row versus stacked layout; there are no weighted zero-width buttons or font shrinking. Stop remains in the long-press controls, notification, and main toggle. Badges/arcs are side by side when space permits and stack otherwise. Android 30+ uses full-display WindowMetrics insets; Android 29 conservatively reserves display bar/cutout bounds. Neither overlay window requests keyboard focus.

## Optional Auto-scan

Enable **Auto-scan** in the side menu, start an overlay session, and perform one complete manual scan with the floating icon. A usable first scan needs recognized species/form, IVs, CP/HP, inferred level, and readable text/bar regions. The service calibrates against the exact native captured pixels, not resized-image masks. If calibration is unavailable, manual scanning still works and the menu indicates that another clear scan is needed.

While enabled and armed, the service samples the chosen capture stream at up to four checks per second. It copies bounded name/CP/HP foreground masks and a lower-left thumbnail (at most 192x400) while each Image is owned on main, then analyzes the copy on a worker. Moving Pokemon artwork is outside the comparison. Only a changed, stable appraisal requests full OCR, with a 500ms settling requirement and at least 1200ms between automatic attempts. Sampling pixels never substitute for full-resolution recognition results.

There is only one in-flight scan. Empty/covered fields break stability, old observations expire, and a post-capture observation must still match before automatic results publish. A static shared app can be asked for one fresh validation frame using the existing virtual display. Visibility/menu/drag interruptions discard stale work and allow a fresh settle after resume; completed failures are not retried continuously on identical signatures. Stop, toggle-off, resize, and session replacement clear the baseline. Rotation requires a new manual calibration scan.

On a recognized profile change, Shadow resets to off. If an automatic check returns identical species/form, IVs, CP and HP, the existing Shadow choice is preserved. Identical visible fields cannot prove a different Pokemon; use manual Rescan for those cases. Automatic failures do not repeatedly open error panels. The last failure and its inspector remain available through controls.

Use **Pokemon GO-only sharing** where Android offers it. With full-screen sharing, the overlay may obscure the appraisal bars and pause detection until collapsed; other apps are included in the authorized capture. The service also pauses while locked, non-interactive, or when the selected captured app reports being hidden. No usage-access, Accessibility permission, simulated taps, or automatic screenshot export was added. Auto-scan is a beta that still needs real-device capture validation and consumes some additional battery/CPU while armed.

## Theme

A **Dark mode** toggle in the hamburger drawer controls both the main app and active overlay. The initial theme follows the phone until explicitly changed. The preference is saved locally and active overlay surfaces update without restarting capture or calculation jobs.

The 8-bit interface uses stepped pixel corners, flat 2dp frames, and bundled **Silkscreen** for all app-owned text roles, including body text, diagnostics, small labels, and unavailable results. Sizes and line heights are preserved; unsupported glyphs can use platform fallback. Android's permission dialogs, notifications, toasts and share chooser keep system typography. The native app theme also references the bundled font resource, synchronized from the licensed asset using `node scripts/sync-android-font.mjs` at the repository root. Light uses cream/coral/amber; dark uses warm charcoal/coral/gold. Shadow ON uses a purple palette across the entire button. No decorative background animation or blur was added.

## Performance

- One OCR engine is retained per ViewModel/service owner and warmed once with a synthetic white 64x64 image when the app/session opens. No real screen is captured for warm-up, and there are no dummy scan results.
- Full-precision image preparation and bar-recognition rules remain unchanged. ML Kit text recognition starts before CPU bar analysis, allowing those stages to overlap.
- Cancelled native OCR cannot accumulate analyzed images: a one-scan gate is released only after both native processing and scan cleanup finish. Owner shutdown closes the recognizer after active tasks complete.
- Pokemon-name normalization and immutable parser regexes are reused, rather than rebuilding them for every frame.
- CP-only ranking probes avoid temporary Stats objects and redundant square roots. A thread-safe, immutable-result LRU retains at most 32 rankings per calculator, reduced to eight on low-RAM devices. Caches do not retain screenshots.
- The **250ms overlay hide/settle guard remains** to avoid reintroducing capture artifacts. No claim of instantaneous or sub-second phone OCR has been established in this workspace.

Diagnostic timing infrastructure measures capture/decode, image preparation, bar analysis, OCR, text parsing, assessment and result-ready times. Overlay layout construction is also measured. These are monotonic processing timings, not physical display presentation times; bar analysis and OCR overlap, so stages cannot be added together. Timings are memory-only and never uploaded. The old sidebar timing page is no longer exposed.

Host benchmarks show large reductions in repeated ranking/assessment and name parsing, but exclude actual Android ML Kit and capture latency. See `../PERF_CALCULATIONS.md` and `../PERF_OCR.md` for workloads, before/after values, memory tradeoffs and reproduction commands. The host benchmark source is deliberately outside Android's test source set.

## Privacy And Capture Lifecycle

English ML Kit OCR and fallback Pokemon data are bundled. Version 0.9.0 **has Internet permission** for public GitHub data downloads. OCR is on-device; screenshots and recognized text are not sent by the updater. GitHub receives ordinary connection metadata, including IP address. Internet permission is application-wide, not a separate per-SDK sandbox. Historical 0.8.x releases removed Internet permission; that restriction does not apply to this release. Capture-consent tokens are never saved; appearance preferences persist within this new app. Diagnostic images are only written to cache after an explicit **Share diagnostic ZIP** action, described below.

Startup immediately uses validated last-good or bundled data and enqueues a process-scoped background IO check. Success is throttled for six hours, failures for 15 minutes; checks happen on startup, not a periodic Android worker. Validated new packs activate atomically and existing results keep their captured repository snapshot. About shows active version/source/publication, status and result data versions. Failed/offline checks retain local data. The HTTPS endpoint is `https://api.github.com/repos/TicTacTris/pokemog-data/releases/latest`; bounded downloads, hashes and schema/graph validation provide integrity checks, not an independent publisher signature.

The separate data repository is https://github.com/TicTacTris/pokemog-data. Its inspected `.github/workflows/data.yml` runs every six hours, tests packs and verifies uploaded release assets; it never builds APKs. Manual trigger: `gh workflow run data.yml --repo TicTacTris/pokemog-data`. New dataset deployment remains separately owned and was not triggered by the app rename.

Capture runs in a user-started foreground service with a notification. Each session uses fresh MediaProjection consent, one virtual display, and bounded capture surfaces. OCR is manual unless optional Auto-scan is enabled and calibrated; no automated game interaction occurs. Stop before visiting unrelated apps when sharing the entire display. Process death, screen locking, or permission revocation can end a session; it is never restarted automatically with a stale token.

## Single-App Capture And Diagnostics

Version 0.3.0 no longer forces normal phone captures to a 2000px virtual display. The source app's native dimensions are retained up to 8 megapixels / 4096px longest edge, or 3 megapixels / 2560px on low-RAM devices. Resizing callbacks update the existing display and reader together. Owned pixel copies account for RGBA order, crop bounds, pixel stride, row padding, and truncated final-row padding.

Capture and import both go through `ScanImages.prepare`: opaque sRGB software rendering with the same filtered resize to a 2000px longest edge. Import decoding preserves native pixels within the same device limits instead of using a separate early decode resize. No extra waiting or looser bar thresholds were introduced as a substitute for investigating the capture path.

If recognition or assessment fails after a frame was prepared, or CP/HP/current level remains unreadable, the overlay offers **Inspect failed capture**. The inspector displays the actual captured frame and exact analyzed image, plus source/output/crop sizes, strides, timestamp, data space, alpha/color information, parsed CP/HP, and original recognized text. It does not pretend preprocessing succeeded if no analyzed image exists. Memory limits can prevent diagnostic retention.

The latest failure stays in memory until a new scan, successful recovery, or session stop. An open inspector holds its own lease so session cleanup cannot recycle an image being displayed. Nothing is saved simply by opening the inspector.

**Share diagnostic ZIP** explicitly writes `captured.png`, `analyzed.png`, and `capture-details.txt` into a ZIP under private `cache/diagnostics/`, then opens Android's share chooser with a temporary read grant through a non-exported, narrowly scoped FileProvider. The preview warns that images may include location, trainer details, notifications, or other screen content. Do not share anything private. Cancelled/failed exports that never reach the chooser are deleted. Successfully handed-off ZIPs remain available to the receiving app; files older than 24 hours are cleaned on a later share attempt, or can be removed by clearing app cache. The selected receiving app controls any subsequent upload.

The supplied **Shinx diagnostics** reproduced a concrete detector fault: the original 1440x3088 capture returned 4/12/15, but the 933x2000 analyzed image split rounded bar edges into duplicate matches. Version 0.4.0 rounds only the within-bar endpoint grouping tolerance upward to whole pixels. Both full files now return **4/12/15** in the compiled Kotlin detector. Actual bar crops, excluding location/date information, are kept as regression fixtures; separate duplicate panels, missing HP, and misaligned bars still reject. Bar color thresholds and the multiple-panel ambiguity rule are unchanged.

Scatterbug/Volbeat have not been reproduced from their exact MediaProjection frames. Live overlay/UI behavior still needs phone verification; the inspector remains available for other cases.

## Build And Download

Use JDK 17, Android SDK platform 35, build-tools 35.0.0, and platform-tools. Set `JAVA_HOME` and `ANDROID_HOME`, or use an untracked `local.properties` for `sdk.dir`.

From `android/`:

```sh
POKEMOG_DATA_SMOKE=1 ./gradlew --no-daemon clean :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

On Windows use `gradlew.bat`. The wrapper pins Gradle 8.13 and its distribution checksum. For a POSIX shell, configure your own installed toolchain before building:

```sh
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Version 0.9.1 uses versionCode 11 and package `dev.pokemog.android`. In-place updates require the same signing key. Stop capture before updating. A debug signing certificate is **not a production release signature**. Do not commit signing keys or local toolchain configuration.

From the repository root, `npm run android:publish-debug` updates only `public/downloads/pokemog-debug.apk` and its SHA-256 checksum. Run `npm run build` afterward to refresh the website artifacts. While the dev server runs, `/downloads/index.html` provides download and installation instructions. APKs are not cached by the web service worker.

From `android/`, `node scripts/generate-pokemog-icons.mjs --check` verifies that all generated icon resources match the Kotlin pixel pattern and adaptive mask safe area. Run without `--check` to regenerate after editing the pattern.

Install by opening the APK on your phone, or:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Allow installation from your browser/file manager only if you trust the build, and revoke that installer permission afterward. No phone security settings are changed by the application.

## Data And Verification

0.9.1 verification: **165 JVM tests passed**, including the opt-in production downloader/live release validator; debug app and instrumentation APKs assembled, Android lint **0 errors / 48 warnings**. Instrumentation includes compact header/card absence assertions, shared-form/Shadow warnings, sidebar navigation and exact version assertions, but was compiled only. `DataPackTest.kt` and `StartupDataCheckTest.kt` cover validation, atomic storage/snapshot consistency and nonblocking startup scheduling. Live checks accept newer valid releases with at least 1,000 catalog records rather than requiring equality with the static 1,681-record bundle. The root handoff records the published APK checksum and unchanged signer.

`npm run android:data` from the repository root regenerates pinned offline evolution data, TypeScript/Kotlin parity fixtures, and cropped instrumentation-test assets. Normal Gradle builds use committed/generated assets and do not need Node or the data-source server. See `../DATA_ANDROID.md` for missing families and source exceptions. The PvPoke MIT license is bundled and accessible under **About & data license**.

JVM tests additionally cover automatic probe bounds/calibration, animation/noise rejection, settling/cooldowns, failed and interrupted attempts, returning to prior profiles, stale observations, cache behavior, parser parity, and timing presentation. Instrumentation tests cover actual footer widths/heights and font scaling, glyph containment, pinned placement, whole Shadow controls, all text roles, OCR lifecycle, and the saved auto-scan setting. Tests use supplied/test pixels and simulated signals, not a live Pokemon GO capture. Native raw calibration was also checked against the provided full Shinx and Giratina images with manually specified field boxes; actual ML Kit anchor selection still needs phone verification. With a connected device/emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

**Physical-device and emulator UI tests have not been run in this workspace.** Compiled instrumentation tests and JVM checks do not verify actual compositor capture, native OCR execution, overlay gestures, keyboard/system interaction, manufacturer background limits, or rotation. This remains a debug test build.

## Limits

- English OCR/UI only. Rescan unreadable or inconsistent images; manual correction is intentionally unavailable.
- Artwork-based regional-form identification is not implemented. Approved equivalent forms share calculations but remain explicitly unresolved; all other unresolved forms require a rescan. Unparenthesized catalog aliases are not guessed into a species group.
- Best Buddy status is not visually identified; observed/base-level ambiguity is preserved. Projected league builds do not include a future buddy boost.
- No purification, candy/stardust costs, move simulations, or enforcement of conditional/event-only evolution, encounter, and cup rules. Some evolution families and Shadow targets are absent from the snapshot.
- Protected content cannot be captured. Permission revocation, screen locking, OEM rules, and selected-app visibility affect screen capture.
- Calculation/scan state is in memory. A new profile defaults Shadow to off so the previous Pokemon's condition is not silently carried forward; identical automatic results preserve the choice. Theme and the opt-in Auto-scan setting are saved locally.
- Android and web share calculation specifications/data and parity fixtures, but do not claim UI feature parity. The 0.8.0 web app is manual entry only, with no Tesseract/OCR or screenshot import. It permits known-level, Buddy, and power-up settings; Android retains scan-only inputs and fixed level-50/no-future-Buddy assumptions. Both distinguish current stats, evolution without power-ups, league optima, and fully powered-up builds, with separate IV percentile/stat-product percentages and explicit level alternatives.
