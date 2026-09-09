# Release Procedure

The first public release is **0.10.0**, Android versionCode **12**, tag
`v0.10.0`, repository `TicTacTris/pokemog` (MIT original source). Both the
Pages web bundle and APK must come from the same reviewed source commit and
package version. This procedure does not create a repository, commit, push,
tag, upload, or publish anything automatically.

## Trust Boundaries

- CI receives no signing secrets and builds an **unsigned** release for checks only.
- Signing and packaging are local-only. There is deliberately no remote signing
  or release-publishing workflow. Do not publish CI APKs or debug APKs as releases.
- See [SIGNING.md](SIGNING.md) for key storage, backup, and installation identity.
- GitHub-generated source archives accompany a release once a maintainer creates
  its tag. They are not the web bundle and contain no generated APK or key.
- Action commits are SHA-pinned; Dependabot proposes weekly updates, without
  automatic merging. Gitleaks 8.24.3 downloads are checked against that release's
  HTTPS-hosted checksums, not an independent signature/provenance verification.

## Before Building

Use Node 22, Java 17, the checked-in Gradle wrapper, Android SDK platform 35 and
build-tools 35.0.0, and the JDK's `jar` tool. Set `JAVA_HOME`, `ANDROID_HOME`, and `PATH` for
your installed tools. Never put signing passwords in shell commands or logs.

```sh
npm ci
npm audit --audit-level=high
npm run lint
npm test
npm run build
npx playwright install chromium
npm run test:e2e
POKEMOG_BASE_PATH=/pokemog/ npm run build
POKEMOG_BASE_PATH=/pokemog/ npm run test:e2e
```

Review the source and release diff for secrets/private evidence before making
the repository public. CI scans the checked-out source with redacted Gitleaks;
this is not a substitute for reviewing the entire history before publication.
Packaging writes a local `release/.gitignore` that ignores all generated files;
the root ignore list also excludes `/release/` and `/releases/`. Keep the release source
checkout unchanged between signing, web packaging, tagging, and Pages deployment.

## Local Signed Build

Generate the dedicated key once using the signing guide. Then:

```sh
node scripts/release-signing.mjs certificate
node scripts/release-signing.mjs build
```

The build runs `:app:verifyReleaseSigning`, `:app:assembleRelease`,
`:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest`, and `:app:lintRelease`
without a persistent Gradle daemon or configuration cache. It rebuilds the
Pages site last with `POKEMOG_BASE_PATH=/pokemog/`, then packages only after
`apksigner` verifies the dedicated certificate and `aapt` verifies the production
package, version, code, and absence of debuggable flags. Instrumentation is
**compiled**, not executed; physical-device testing remains a release gate.

Generated files:

- `release/pokemog-0.10.0.apk`
- `release/pokemog-0.10.0.apk.sha256` (hash and APK basename only)
- `release/pokemog-web-0.10.0.zip`
- `release/pokemog-web-0.10.0.zip.sha256`
- `release/SHA256SUMS`
- `release/SIGNING-CERTIFICATE.txt` (public certificate fingerprint only)

Existing versioned artifacts are not overwritten. Move previous artifacts out
of `release/` deliberately before another build. The web zip is sorted, uses
fixed timestamps and modes, omits host ownership attributes, and contains only
relative built-site paths, including fonts/licenses. It rejects APKs, keys,
source maps, dotfiles, symlinks, and README files. This makes the archive
deterministic for identical input files; it is **not** a claim of byte-for-byte
reproducible Android or web builds across machines. Host the web bundle at
`/pokemog/`, not at a domain root.

```sh
# Run from release/ after packaging:
sha256sum --check SHA256SUMS
```

## Publication Gate

After audit, a maintainer separately creates the repository and enables branch
protection, dependency alerts, and Pages with GitHub Actions as its source.
Restrict the `github-pages` environment to `main` and configure required reviewers
if desired. Protect `main`; require CI before merging. Pages only deploys `main`
and independently tests its build; deployment permissions exist only on the
deployment job. Pages does not publish the APK.

Before publication, run a physical-device install/upgrade and capture/stop smoke
test. The initial beta is published with this device check explicitly outstanding,
not represented as passed. Verify the SHA-256 values and signing certificate independently. Tag the
reviewed commit as `v0.10.0`; attach the six public files listed above to a release
on that exact commit. The APK basename matches the public downloads link.
Publish this beta as a GitHub prerelease. Public links use `/releases/tag/v0.10.0`,
because `/releases/latest` excludes prereleases.
Deploy Pages from the same commit and retain that commit identity in release
notes. Later main changes can move Pages ahead of a released APK; maintainers
must coordinate version bumps and release publication. Never claim a CI
attestation for the locally signed bytes; none is generated by these workflows.

## Dependency Verification

`android/gradle/verification-metadata.xml` was bootstrapped on 2026-09-09 with
Gradle 8.13 using the complete CI task set plus `:app:lintRelease` and
`--write-verification-metadata sha256`. It covers artifact and metadata checksums
resolved through the existing Google Maven, Maven Central, and Gradle Plugin
Portal repositories. This is trust-on-first-use, not independently authenticated.
Normal builds enforce this metadata; CI never regenerates it. Review repository
origins and checksum changes before accepting dependency updates. Do not bypass
a failure by blindly accepting new checksums. The Gradle distribution also has
a checksum in the existing wrapper properties. Dependency locking is deferred;
verification pins accepted bytes but is not a dependency-resolution lockfile.

Initial GitHub CI additionally resolved JUnit BOM 5.9.2/5.10.2 Gradle module
metadata and the kotlinx-coroutines BOM 1.8.0 POM, absent from the local cache's
inventory. Their added SHA-256 entries were calculated from the exact artifacts
at `https://repo.maven.apache.org/maven2/` and matched against that repository's
published `.sha256` files. No existing checksum was replaced or verification
disabled; HTTPS-hosted checksums are not an independent signature.
