# Phone Lyrics Native Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an open-source macOS 14+ native lyrics app whose only playback source is Android QQ Music, with precise one-way state synchronization, discovery, pairing, and real-device verification.

**Architecture:** Import Lyrimuse under `apps/macos`, retain its SwiftUI/AppKit surfaces and Go lyrics collector, and replace local MediaRemote playback with a versioned `PhonePlaybackSource`. Android captures QQ Music MediaSession events, discovers and pairs with the Mac, then sends ordered authenticated events plus heartbeats; the Mac owns lyrics resolution and rendering.

**Tech Stack:** Swift 5.9, SwiftUI/AppKit, Network.framework, Security/Keychain, Go 1.24 toolchain, Kotlin/JVM 17, Android SDK 35, Bonjour/mDNS, HTTP/JSON, JSON Schema, GPL-3.0-or-later.

**Spec:** `docs/superpowers/specs/2026-09-17-phone-lyrics-native-architecture-design.md`

## Global Constraints

- Minimum macOS version is 14.
- Minimum Android version is Android 8.0 / API 26; target SDK remains 35.
- Android QQ Music is the only playback source; never fall back to another Android app or a Mac player.
- Mac follows phone state and never sends playback controls.
- Immediate event delivery plus a 1-second heartbeat; state UI target is 300ms and steady-state lyrics error target is 500ms.
- Three seconds without heartbeat means unstable; eight seconds means offline and old lyrics are cleared.
- Discovery is Bonjour/mDNS with manual host and port fallback.
- Pairing uses an expiring six-digit code and a random device token; secrets and authorization headers never enter logs.
- Distribution remains GPL-3.0-or-later with upstream attribution and corresponding source.
- Existing Electron behavior remains available until the native end-to-end path passes real-device verification.
- Do not claim completion from builds alone; macOS UI and Android-to-Mac behavior require real runtime checks.

## File Structure

```text
apps/macos/lyrimuse/                         upstream Swift application
apps/macos/lyrimuse-collector/               upstream Go lyrics resolver
apps/android/PhoneLyricsRelay/               Android companion
packages/protocol/schema/                    wire protocol source of truth
packages/protocol/fixtures/                  shared valid and invalid JSON cases
legacy/electron/                             previous Electron receiver and UI
docs/setup/                                  install, pairing, build and troubleshooting
docs/upstream/                               Lyrimuse baseline and sync procedure
```

New Swift responsibilities:

- `PhonePlaybackEnvelope.swift`: wire models and validation only.
- `PhonePlaybackStateMachine.swift`: ordering, lifecycle and timeout decisions only.
- `PhoneProgressAnchor.swift`: monotonic-time position extrapolation and drift correction only.
- `PhoneSnapshotStore.swift`: atomic shared snapshot read/write for the Go collector.
- `PhonePairingStore.swift`: pairing code lifecycle and Keychain-backed device authorization.
- `PhoneHTTPServer.swift`: bounded HTTP parsing, routing and auth enforcement.
- `PhoneDiscoveryPublisher.swift`: Bonjour publication lifecycle.
- `PhonePlaybackSource.swift`: observable adapter between transport/state and Lyrimuse UI.

New Android responsibilities:

- `PlaybackEnvelope.kt`: protocol model and JSON encoding.
- `QqPlaybackCapture.kt`: QQ-only MediaSession selection and event derivation.
- `RelayEventQueue.kt`: ordered single-flight event delivery and retry policy.
- `MacDiscoveryManager.kt`: NSD discovery and endpoint updates.
- `PairingClient.kt`: pairing exchange and encrypted token persistence.
- `RelayApiClient.kt`: authenticated health and playback HTTP calls.

---

### Task 1: Import the Lyrimuse Baseline and Establish the Monorepo

**Files:**
- Create: `apps/macos/**` from upstream `Yudaotor/lyrimuse`
- Create: `docs/upstream/lyrimuse.md`
- Modify: `.gitignore`
- Test: upstream Swift selftest and Go test suite

**Interfaces:**
- Consumes: upstream repository `https://github.com/Yudaotor/lyrimuse.git` at a recorded full commit SHA.
- Produces: buildable `apps/macos/lyrimuse` and `apps/macos/lyrimuse-collector` trees whose upstream baseline can be reproduced.

- [ ] **Step 1: Record the pre-import baseline**

Run `git status --short --branch`, `swift --version`, `go version`, `java -version`, and `uname -m`. Save the upstream URL, full SHA, import date, subtree command, local tool versions, and GPL obligations in `docs/upstream/lyrimuse.md`.

- [ ] **Step 2: Import upstream as a squashed subtree**

Run:

```bash
git remote add lyrimuse-upstream https://github.com/Yudaotor/lyrimuse.git
git fetch lyrimuse-upstream main
git subtree add --prefix=apps/macos lyrimuse-upstream main --squash
```

If the remote already exists, verify its URL rather than adding a duplicate. Do not edit upstream code before the baseline tests run.

- [ ] **Step 3: Verify the untouched Swift baseline**

Run:

```bash
cd apps/macos/lyrimuse
swift run lyrimuse-selftest
swift build
```

Expected: selftest exits 0 and Swift build succeeds on macOS 14+.

- [ ] **Step 4: Verify the untouched Go baseline**

Run `cd apps/macos/lyrimuse-collector && go test ./...`.

Expected: all collector packages pass before phone-source changes.

- [ ] **Step 5: Commit the reproducible baseline**

Commit message: `chore: import lyrimuse native baseline`.

---

### Task 2: Define the Versioned Playback Protocol

**Files:**
- Create: `packages/protocol/schema/playback-envelope-v1.schema.json`
- Create: `packages/protocol/fixtures/valid-playing.json`
- Create: `packages/protocol/fixtures/valid-paused.json`
- Create: `packages/protocol/fixtures/valid-track-change.json`
- Create: `packages/protocol/fixtures/invalid-sequence.json`
- Create: `packages/protocol/fixtures/invalid-state.json`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhonePlaybackEnvelope.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse-selftest/PhoneProtocolTests.swift`

**Interfaces:**
- Produces: `PhonePlaybackEnvelope.decodeAndValidate(_ data: Data) throws -> PhonePlaybackEnvelope` and JSON field names shared by Swift and Kotlin.
- Produces: states `playing|paused|stopped` and events `trackChanged|play|pause|seek|stop|heartbeat`.

- [ ] **Step 1: Write protocol selftests before the decoder**

Add selftests that load every fixture and assert: valid messages decode; `sequence <= 0` fails; unknown state fails; blank `sessionId`, `trackId`, `title`, or `deviceId` fails; negative position or duration fails; speed outside `0...4` fails; non-QQ package names fail.

- [ ] **Step 2: Run the selftest and confirm failure**

Run `cd apps/macos/lyrimuse && swift run lyrimuse-selftest`.

Expected: compilation fails because `PhonePlaybackEnvelope` does not exist.

- [ ] **Step 3: Add the JSON Schema and concrete fixtures**

The schema requires `additionalProperties: false`, `protocolVersion: 1`, positive integer sequence, non-empty identifiers, `positionMs >= 0`, `durationMs >= 0`, and a package name matching `^com\\.tencent\\.qqmusic`.

- [ ] **Step 4: Implement the Swift decoder and validation errors**

Use `Codable` structs and an explicit `PhoneProtocolError` enum with cases `unsupportedVersion`, `invalidIdentifier`, `invalidSequence`, `invalidTrack`, `invalidPlayback`, and `invalidSource`. Do not let UI or transport types enter this file.

- [ ] **Step 5: Run focused and full Swift verification**

Run `swift run lyrimuse-selftest` and `swift build` from `apps/macos/lyrimuse`.

Expected: protocol tests and all upstream selftests pass.

- [ ] **Step 6: Commit the protocol contract**

Commit message: `feat: define phone playback protocol v1`.

---

### Task 3: Build the Deterministic Mac Playback State Machine

**Files:**
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneProgressAnchor.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhonePlaybackStateMachine.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse-selftest/PhonePlaybackStateMachineTests.swift`

**Interfaces:**
- Consumes: validated `PhonePlaybackEnvelope`.
- Produces: `PhonePlaybackDecision.accept(snapshot:)`, `.ignoreOutOfOrder`, `.clear(reason:)`.
- Produces: `position(at monotonicMs: Int64) -> Int64` and `heartbeatStatus(at:) -> connected|unstable|offline`.

- [ ] **Step 1: Write tests using an injected integer millisecond clock**

Cover first session acceptance, increasing sequence acceptance, old sequence rejection, new-session reset, play extrapolation, pause freeze, resume, seek hard reset, track-change clear-before-apply, stop clear, 3-second unstable, 8-second offline, and duration clamping.

- [ ] **Step 2: Verify the tests fail before implementation**

Run `swift run lyrimuse-selftest` and confirm missing state-machine symbols.

- [ ] **Step 3: Implement ordering and timeout transitions**

Keep session/sequence state separate from UI fields. A new `sessionId` is accepted only after resetting prior ordering. Offline emits one clear decision instead of clearing on every timer tick.

- [ ] **Step 4: Implement the three drift bands**

For heartbeat correction: `abs(error) <= 150ms` keeps the anchor; `151...600ms` stores a one-second linear correction; `> 600ms`, seek, or track change resets immediately. Paused and stopped states never extrapolate.

- [ ] **Step 5: Run full Swift verification**

Run `swift run lyrimuse-selftest && swift build`.

- [ ] **Step 6: Commit the deterministic engine**

Commit message: `feat: add phone playback state machine`.

---

### Task 4: Add Secure Pairing, HTTP Transport, and Bonjour Discovery on Mac

**Files:**
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhonePairingStore.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneHTTPTypes.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneHTTPRouter.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneHTTPServer.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneDiscoveryPublisher.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse-selftest/PhonePairingTests.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse-selftest/PhoneHTTPRouterTests.swift`

**Interfaces:**
- Produces: `PhonePairingStore.createCode(now:) -> PairingCode`, `pair(code:device:) -> DeviceCredential`, `authorize(bearer:) -> PairedDevice?`, and `revoke(deviceId:)`.
- Produces: routes `GET /api/v1/health`, `GET /api/v1/info`, `POST /api/v1/pair`, and authenticated `POST /api/v1/playback`.
- Produces: Bonjour service `_phonelyrics._tcp` with TXT keys `v=1`, `id=<stableMacId>`, `pairing=0|1`.

- [ ] **Step 1: Write pure pairing and router tests**

Test six-digit format, five-minute expiry, one-time consumption, maximum five failures per minute per peer, 32-byte token generation, token digest comparison, revocation, missing/malformed auth, 64 KiB body limit, route methods, protocol error mapping, and secret redaction.

- [ ] **Step 2: Run selftests and confirm missing symbols**

Run `swift run lyrimuse-selftest`.

- [ ] **Step 3: Implement pairing persistence**

Store token material through macOS Keychain generic-password APIs and store only device metadata plus token digest in the app config. Inject random bytes and time into pure logic so selftests never touch the real Keychain.

- [ ] **Step 4: Implement the pure HTTP router**

Make routing accept `PhoneHTTPRequest` and return `PhoneHTTPResponse`. Only the health, info, and pair routes bypass bearer auth. Convert validation failures to 400, auth failures to 401, rate limits to 429, body overflow to 413, and unknown paths to 404.

- [ ] **Step 5: Implement the Network.framework listener**

Use `NWListener` on a configurable TCP port. Parse one bounded HTTP/1.1 request per connection, set read/write timeouts, dispatch routing off the main actor, and close the connection after the response. Never log headers or raw pair bodies.

- [ ] **Step 6: Implement Bonjour publication lifecycle**

Publish only after the listener has its actual port; update `pairing` TXT state when a code is active; stop publication before listener shutdown.

- [ ] **Step 7: Run Swift tests and a local curl smoke test**

Run the selftest and build, launch a debug listener, verify health returns 200, unauthenticated playback returns 401, oversized payload returns 413, and a paired token can post a valid fixture.

- [ ] **Step 8: Commit Mac transport and pairing**

Commit message: `feat: add mac discovery pairing and transport`.

---

### Task 5: Integrate PhonePlaybackSource with Lyrimuse and the Go Collector

**Files:**
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhoneSnapshotStore.swift`
- Create: `apps/macos/lyrimuse/Sources/LyrimuseCore/Phone/PhonePlaybackSource.swift`
- Modify: `apps/macos/lyrimuse/Sources/lyrimuse/PlaybackCoordinator.swift`
- Modify: `apps/macos/lyrimuse/Sources/lyrimuse/AppDelegate.swift`
- Modify: `apps/macos/lyrimuse-collector/system.go`
- Create: `apps/macos/lyrimuse-collector/phonesnapshot.go`
- Create: `apps/macos/lyrimuse-collector/phonesnapshot_test.go`
- Modify: relevant collector player-selection tests

**Interfaces:**
- Produces: atomic `~/.config/lyrimuse/phone-now-playing.json` with track, playback, receive time, connection state, and no credential material.
- Produces: `PhonePlaybackSource.shared`, exposing the playback and lyric-facing published properties consumed by `PlaybackCoordinator`.
- Consumes: existing collector enrichment cache and Lyrimuse lyric sync engine.

- [ ] **Step 1: Write Go snapshot tests and Swift source integration tests**

Go tests cover valid snapshot, missing file, atomic replacement, stopped/offline state, malformed JSON, and stale receive time. Swift tests cover clear-before-track-change, collector snapshot writes, lyric cache reload, pause freeze, and offline old-lyric clearing.

- [ ] **Step 2: Run both test suites and confirm expected failures**

Run `go test ./...` in the collector and `swift run lyrimuse-selftest` in the Swift app.

- [ ] **Step 3: Implement atomic shared snapshot storage**

Write a temporary file in the same directory, fsync it, and atomically replace the destination. File permissions must prevent other local users from reading playback history. Never write pairing data.

- [ ] **Step 4: Teach the collector to read only phone snapshots**

Replace the normal poller's local-player state acquisition with `readPhoneSnapshot`. Preserve the existing enrichment, cache, lyric source scoring, translation, romanization and artwork pipeline. Do not create an HTTP server in Go.

- [ ] **Step 5: Adapt the Swift playback source**

Reuse the existing lyric cache, sync engine and published UI contract from `LocalPlaybackSource`, but feed it from `PhonePlaybackStateMachine`. Disable local MediaRemote polling, player notifications, seek commands, artwork requests tied to local players, and playback control methods.

- [ ] **Step 6: Rewire PlaybackCoordinator and startup**

Start pairing, listener, discovery, phone source and collector in deterministic order. Shutdown in reverse order. Coordinator public fields used by overlay, menu bar and lyrics window retain their existing names so display code remains stable.

- [ ] **Step 7: Verify Swift and Go together**

Post fixtures with curl and assert the shared snapshot changes, the collector resolves or reports lyrics status, pause freezes position, seek jumps, track change clears old lines first, and eight-second timeout clears state.

- [ ] **Step 8: Commit native pipeline integration**

Commit message: `feat: connect phone source to lyrics pipeline`.

---

### Task 6: Replace Player Settings with Phone Connection UX

**Files:**
- Modify: `apps/macos/lyrimuse/Sources/lyrimuse/SettingsView.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse/Settings/PhoneSettingsView.swift`
- Create: `apps/macos/lyrimuse/Sources/lyrimuse/Settings/PhoneConnectionViewModel.swift`
- Modify: `apps/macos/lyrimuse/Sources/lyrimuse/AppDelegate.swift`
- Modify: overlay and menu-bar views that expose playback controls
- Create: `apps/macos/lyrimuse/Sources/lyrimuse-selftest/PhoneSettingsTests.swift`
- Modify: `apps/macos/lyrimuse/Localization/Localizable.xcstrings`

**Interfaces:**
- Consumes: pairing, listener, discovery and phone source status.
- Produces: user-visible states `waitingForPairing`, `connectedIdle`, `playing`, `paused`, `unstable`, `offline`, `authFailed`, and `portConflict`.

- [ ] **Step 1: Add settings contract tests**

Assert the sidebar has “手机连接” instead of “播放器”, local-player settings keys are no longer searchable, pairing secrets are absent from diagnostics, and each connection state has distinct Chinese and English copy.

- [ ] **Step 2: Confirm settings tests fail**

Run `swift run lyrimuse-selftest`.

- [ ] **Step 3: Build the phone settings page**

Show connection state, current track, discovered endpoint, listening port, active six-digit code with expiry, paired devices, revoke action, manual endpoint help, open-log action and protocol version. Pairing code must not appear in exported diagnostics.

- [ ] **Step 4: Remove Mac playback controls and local-player UX**

Hide player selection, Automation permission, MediaRemote health, launch-player linkage, play/pause, next, previous and seek controls. Preserve lyric timing offset because it corrects lyric files rather than controlling playback.

- [ ] **Step 5: Verify actual macOS windows**

Run the app, inspect settings, menu bar, classic overlay, lyrics window and every state transition. Confirm no Dock icon, no local-player entry, no dead buttons, readable light/dark appearances and persistence after restart.

- [ ] **Step 6: Commit the phone-only UI**

Commit message: `feat: add phone connection settings`.

---

### Task 7: Rebuild Android Capture and Ordered Transport

**Files:**
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/protocol/PlaybackEnvelope.kt`
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/playback/QqPlaybackCapture.kt`
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/network/RelayEventQueue.kt`
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/network/RelayApiClient.kt`
- Modify: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/RelayService.kt`
- Modify: `android/PhoneLyricsRelay/app/build.gradle.kts`
- Create: Android JVM unit tests matching each new class

**Interfaces:**
- Consumes: protocol v1 fixtures and paired endpoint credentials.
- Produces: immediate ordered events and one-second heartbeats with monotonic captured time.

- [ ] **Step 1: Add JUnit and protocol fixture resource wiring**

Configure JVM tests to read `packages/protocol/fixtures` and add JUnit 4.13.2. Do not duplicate fixtures inside the Android project.

- [ ] **Step 2: Write failing model, capture and queue tests**

Cover JSON equality to fixtures, exact QQ package filtering, playing-position extrapolation, paused freeze, metadata change, play/pause/seek/stop derivation, session UUID, increasing sequence, single-flight sending, retry order, backoff ceiling, auth redaction and cancellation.

- [ ] **Step 3: Run unit tests and confirm failure**

Run `cd android/PhoneLyricsRelay && ./gradlew testDebugUnitTest`.

- [ ] **Step 4: Implement protocol and QQ capture**

Compute send position from Android `elapsedRealtime`, `lastPositionUpdateTime` and speed. Register MediaController callbacks and rescan active sessions when QQ Music appears or disappears. Never fall back to a non-QQ session.

- [ ] **Step 5: Implement ordered transport**

Use one coroutine-backed queue or one executor, never one Thread per tick. Coalesce redundant heartbeats but never coalesce track, pause, seek or stop events. Retry transient failures in original order, with a bounded queue and exponential backoff capped at 30 seconds.

- [ ] **Step 6: Refactor RelayService lifecycle**

Start one capture, one queue and one heartbeat scheduler. Update the foreground notification on track or connection changes. Release callbacks, scheduler, queue and WakeLock in `onDestroy`; reacquire WakeLock safely after sticky restart.

- [ ] **Step 7: Run JVM tests and build the APK**

Run `./gradlew testDebugUnitTest assembleDebug`.

Expected: unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` is produced.

- [ ] **Step 8: Commit Android synchronization core**

Commit message: `feat: synchronize qq music playback events`.

---

### Task 8: Add Android Discovery, Pairing, and Reconnection UX

**Files:**
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/discovery/MacDiscoveryManager.kt`
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/pairing/PairingClient.kt`
- Create: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/pairing/CredentialStore.kt`
- Modify: `android/PhoneLyricsRelay/app/src/main/java/com/phonlyrics/relay/MainActivity.kt`
- Modify: `android/PhoneLyricsRelay/app/src/main/res/layout/activity_main.xml`
- Modify: `android/PhoneLyricsRelay/app/src/main/AndroidManifest.xml`
- Create: JVM tests for discovery selection, pairing responses and credential migration

**Interfaces:**
- Consumes: `_phonelyrics._tcp` records and Mac pair endpoint.
- Produces: selected stable Mac ID, live host/port, bearer token and manual endpoint fallback.

- [ ] **Step 1: Write discovery and pairing tests**

Test TXT protocol filtering, stable-ID address updates, duplicate records, service loss, manual override, pairing success, expired code, bad code, rate limit, revoked token and migration from the existing plain IP preference.

- [ ] **Step 2: Confirm tests fail before implementation**

Run `./gradlew testDebugUnitTest`.

- [ ] **Step 3: Implement Android NSD discovery**

Use `NsdManager` to discover `_phonelyrics._tcp`, resolve services, ignore unsupported protocol versions and retain the chosen stable Mac ID across IP changes. Stop discovery with the Activity lifecycle.

- [ ] **Step 4: Implement pairing and encrypted credentials**

Use Android Keystore-backed encryption for bearer tokens. Store endpoint metadata in SharedPreferences and token ciphertext separately. Pairing errors map to actionable UI messages without logging codes or tokens.

- [ ] **Step 5: Replace IP-first UI with device-first UI**

Default screen lists discovered Macs, shows paired/unpaired status, accepts the six-digit code, and exposes manual IP/port in an expandable fallback section. Keep notification access, battery optimization and connection test actions.

- [ ] **Step 6: Verify restart and IP-change recovery**

Build and install the debug APK, pair once, restart both apps, change between Wi-Fi and phone hotspot, and confirm the stable Mac selection updates its address without a new pairing.

- [ ] **Step 7: Commit discovery and pairing**

Commit message: `feat: add android mac discovery and pairing`.

---

### Task 9: Consolidate Repository Layout, Licensing, and Documentation

**Files:**
- Move: `android/PhoneLyricsRelay` to `apps/android/PhoneLyricsRelay`
- Move: `electron`, `src`, `shared`, old `packaging`, `scripts/test-lrc.js`, and old browser demo into `legacy/electron/`
- Modify: `package.json` or move it under `legacy/electron/`
- Rewrite: `README.md`
- Create: `LICENSE`
- Create: `NOTICE`
- Create: `docs/setup/macos.md`
- Create: `docs/setup/android.md`
- Create: `docs/setup/pairing-and-troubleshooting.md`
- Create: `CLAUDE.md`
- Modify: `.gitignore`

**Interfaces:**
- Produces: one documented native build path and a clearly labeled legacy prototype path.

- [ ] **Step 1: Move files with `git mv` only after native verification**

Preserve history and ensure Gradle fixture paths are updated from the new Android location. Do not delete legacy Electron code in this release.

- [ ] **Step 2: Install the GPL and attribution files**

Use the complete GPL-3.0-or-later license text, identify the Lyrimuse upstream URL and baseline SHA, list retained third-party notices, and state that distributed binaries have corresponding source in this repository.

- [ ] **Step 3: Rewrite setup and troubleshooting docs**

Document macOS 14+, Android 8+, build prerequisites, native build commands, APK build/install, notification access, battery exemption, discovery, pairing, manual IP fallback, revocation, logs, port conflicts and network isolation. Remove instructions that present Electron as the product.

- [ ] **Step 4: Add concise project instructions**

`CLAUDE.md` must list the Swift, Go and Android commands; protocol source of truth; real-device requirement; secret-redaction rule; upstream sync command; and directory ownership in fewer than 100 lines.

- [ ] **Step 5: Verify every documented command on a clean shell**

Run commands exactly as written. Correct paths and prerequisites that fail.

- [ ] **Step 6: Commit repository consolidation**

Commit message: `docs: make native phone lyrics the primary product`.

---

### Task 10: Complete Automated, Runtime, and Real-Device Release Gates

**Files:**
- Create: `scripts/verify.sh`
- Create: `docs/verification/2026-09-17-native-phone-lyrics.md`
- Modify: build or packaging scripts only for defects discovered by verification

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reproducible pass/fail evidence and installable Mac app plus Android APK.

- [ ] **Step 1: Create one non-destructive verification entry point**

`scripts/verify.sh` runs protocol fixture validation, Swift selftests/build, Go tests, Android JVM tests and debug APK assembly. It must stop on first failure and print the failing subsystem without printing environment secrets.

- [ ] **Step 2: Run all automated gates from the repository root**

Run `bash scripts/verify.sh` and record tool versions, command results and artifact paths in the verification report.

- [ ] **Step 3: Run Mac runtime acceptance**

Verify menu-bar-only lifecycle, phone settings, pairing code expiry, revocation, overlays, lyrics window, light/dark appearance, click-through/lock, multiple Spaces, app restart and clean shutdown. Capture screenshots for settings and representative overlay states.

- [ ] **Step 4: Run Android-to-Mac real-device matrix**

Test play, pause, resume, seek forward/backward, track change, stop, screen lock, Android background, QQ Music restart, Relay restart, Mac restart, Wi-Fi, phone hotspot, IP change, 3-second instability, 8-second offline, wrong code, revoked token and manual endpoint fallback.

- [ ] **Step 5: Measure synchronization targets**

For at least ten play/pause transitions and ten lyric checkpoints, record Android capture monotonic time, Mac receive time and Mac rendered state time. Report median, p95 and maximum. Pass only if state response is at most 300ms and steady-state lyric error is at most 500ms under normal LAN conditions.

- [ ] **Step 6: Package installable artifacts and test clean installation**

Build the Mac app with the upstream build script, build the Android debug/release candidate APK, install both on clean user state, complete pairing, and play one uncached and one cached track.

- [ ] **Step 7: Fix defects and rerun the affected gate plus the full suite**

Each defect gets a focused regression test before its fix. After focused verification passes, rerun `bash scripts/verify.sh` and the relevant runtime matrix row.

- [ ] **Step 8: Commit release-gate evidence**

Commit message: `test: verify native phone lyrics end to end`.

---

## Final Review

- Verify every P0 item in the design spec maps to at least one task and one acceptance check.
- Scan every task for placeholder language or instructions that lack an exact file, symbol, command, expected result, or acceptance condition; replace each occurrence with concrete execution details.
- Confirm protocol property names match across JSON Schema, Swift and Kotlin.
- Confirm no test, log, screenshot, fixture or diagnostics export contains a bearer token, Authorization header or live pairing code.
- Confirm `git status --short` contains no accidental build outputs or unrelated user changes.
