# Contributing

PokeMog is a passion project. Contributions are welcome; all features remain free to use. Contributions to original code are submitted under the repository's MIT license. Do not submit material you lack permission to distribute.

1. Discuss substantial behavior changes in an issue first. Keep sensitive security reports private as described in [SECURITY.md](SECURITY.md).
2. Use Node.js 22.12+, run `npm ci`, and follow [development guidance](docs/DEVELOPMENT.md).
3. Keep changes focused. Preserve calculation invariants, accessibility, offline behavior and root/subpath hosting. Add regression tests for changed behavior.
4. Run unit tests, lint, build and browser tests at both `/` and `/pokemog/`. State checks actually run and any platform/device gaps.
5. Preserve dependency licenses and data provenance. Never include credentials, signing keys, private captures, local paths, generated binaries or machine-specific logs in a contribution.

Use synthetic or safely licensed fixtures. Do not weaken conservative form resolution or capture consent to make a test pass. Data releases and application releases are separate; do not silently change their compatibility contract. Release signing, package versions, checksums and Pages publishing need maintainer verification.
