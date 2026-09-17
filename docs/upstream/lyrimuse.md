# Lyrimuse upstream baseline

- Upstream: `https://github.com/Yudaotor/lyrimuse.git`
- Branch: `main`
- Baseline commit: `e6bdf6a4563de91f88621c37645b8d11072cb822`
- Imported: 2026-09-17
- Destination: `apps/macos/`
- Import command: `git subtree add --prefix=apps/macos lyrimuse-upstream main --squash`
- Update command: `git fetch lyrimuse-upstream main && git subtree pull --prefix=apps/macos lyrimuse-upstream main --squash`

## Local baseline toolchain

- Architecture: Apple Silicon (`arm64`)
- Swift: Apple Swift 6.2.4
- Go: 1.26.2
- Java: Amazon Corretto 17.0.19

## License obligations

Lyrimuse is distributed under GPL-3.0. This repository retains the upstream license, copyright notices, and third-party notices. Any distributed modified binaries must be accompanied by the corresponding source under the same GPL terms. Project-specific changes must not remove upstream attribution.

## Sync policy

Fetch and review upstream changes before pulling them into `apps/macos/`. Run the Swift selftest, Swift build, and full Go test suite immediately after every upstream sync. Resolve conflicts in favor of the phone-only product requirements only where upstream local-player behavior conflicts with `docs/superpowers/specs/2026-09-17-phone-lyrics-native-architecture-design.md`.
