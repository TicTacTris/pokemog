# Security Review: 0.10.0 Beta

Defensive source, configuration and dependency review, September 9, 2026. This is not an independent audit, penetration-test certification, legal opinion or guarantee that vulnerabilities are absent. No offensive exploitation was performed.

## Remediation

| Finding                                         | Implemented control                                                                                                                                                                                          |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Debug distribution                              | Dedicated local-only release key; non-debuggable release; separate `.debug` application ID; packaging checks the signer, version and flags. No signing secrets in Git or pull-request jobs.                  |
| Publisher/client contract drift                 | Matching ID/name/type/counterpart rules and shared contract fixtures. Maximum 20,000 records, out-degree 32, path depth 16 with root depth 1, and 32 reachable nodes including root.                         |
| Different bundled/downloaded metadata semantics | Both loaders treat omitted-only evolution sources conservatively; parity regression added.                                                                                                                   |
| Expensive graph calculations                    | Iterative bounded graph validation and runtime projection/target limits.                                                                                                                                     |
| Overlapping imported-image decoding             | Process-wide acquisition/decode/OCR gate, 20 MiB encoded-input limit, cancellation checks and memory-failure cleanup. Existing image dimension limits remain.                                                |
| Diagnostic retention                            | Three-file / 100 MiB cache budget, one-hour expiry policy, lifecycle and process-timer cleanup, explicit deletion, grant revocation and secure inspector windows.                                            |
| Notification denial                             | Capture guidance checks notification/channel availability and points to main-app and overlay Stop controls.                                                                                                  |
| Incomplete network deadlines                    | Request deadlines shared across redirects, scheduled disconnects, and an overall update budget checked before activation.                                                                                    |
| Publisher ingestion/workflow                    | Bounded HTTPS streaming and redirects, iterative graphs, main-only publishing, job timeouts, nonpersistent checkout credentials and SHA-pinned Actions.                                                      |
| Privacy disclosure                              | Separates local OCR from ML Kit metrics, GitHub connection metadata and manually shared diagnostic contents. Ko-fi is a link only, with no embedded tracking widget.                                         |
| Pages paths/download fallback                   | Base-aware assets and scoped worker; download/checksum paths excluded from SPA fallback; APKs are release assets, not website cache entries.                                                                 |
| Source disclosure                               | Ignore rules exclude keys, local configuration, builds, diagnostics and private captures. Candidate-source scan found no secrets; cropped fixture images contain no visible personal fields or PNG metadata. |

## Verification

- Android: 181 JVM tests passed with live data smoke enabled during remediation; instrumentation APK compiled. Debug/release lint reports zero errors and 48 warnings. Physical device tests were not executed.
- Website: 96 unit tests and 20 browser tests for each of `/` and `/pokemog/`; lint and production builds passed.
- Publisher: 40 tests passed in its hardening run, including frozen numerical cases and shared acceptance/rejection fixtures; publication workflow passed without replacing unchanged data.
- npm audit reported zero vulnerabilities at the release-preparation check. This does not cover all Maven/native dependencies or future advisories.
- Gitleaks 8.24.3 found no secrets in the ignore-filtered source candidate, exact 194-file initial staged snapshot or initial Git history before pushing. GitHub's source scan also passed. Repeat staged-file and history checks for publication changes.
- Gradle dependency verification records SHA-256 artifacts and was enforced locally. Its bootstrap is trust-on-first-use, not independent proof of artifact provenance; dependency locking remains deferred.
- Signed APK and website ZIP checksums and certificate verification are produced by the release packaging scripts. GitHub CI checks unsigned release construction without accessing the release key.

## Residual Risks And Limits

- No connected Android device or emulator was available. Capture consent, notification denial, low memory, native blocking calls, diagnostic sharing/deletion and auto-scan transitions need device testing.
- Some providers/native decoders can ignore cancellation. The import gate prevents overlapping imports, but a stalled call can retain the gate and temporary data until it actually finishes.
- Deadlines use bounded reads and scheduled disconnects; they are not a proven hard wall-clock limit on every Android/network implementation.
- Diagnostic expiration is best-effort. Process death, blocked work and failed deletion can delay cleanup. An inspector can retain leased images until closed; recipient copies are beyond app control.
- HTTPS and manifest hashes establish consistency with the configured GitHub publisher, not an independent publisher signature. A compromised authorized publisher/upstream remains a trust risk. Previous-data recovery intentionally permits fallback rather than enforcing irreversible anti-rollback.
- Sudden-power-loss behavior and real Android `AtomicFile` failure modes have not been exhaustively tested.
- Meta CSP is defense in depth, not an XSS proof. It cannot enforce `frame-ancestors`; GitHub Pages controls HTTP security headers. Inline styles remain permitted for React numeric styles. Use a dedicated application path and never expose Vite as a production server.
- ML Kit is proprietary and can send metrics/service requests. On-device recognition is not a promise of zero SDK networking.
- Pokemon names, artwork and cropped test fixtures retain third-party rights. MIT covers original project code, not those rights. Fan use and disclaimers do not guarantee legal permission.
- The release key must be backed up securely offline; never add it, its passwords or an encoded copy to Git. Losing it can prevent compatible APK updates.

Report sensitive issues through the process in [SECURITY.md](../SECURITY.md), not public issues containing private captures or secrets.
