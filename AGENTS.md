# bipe-app

This repo holds Bipe's own messenger client. `ios/` holds the iOS app (SwiftUI). `android/` holds the Android app (Jetpack Compose). Both apps use matrix-rust-sdk through `matrix-sdk-ffi`. The apps replace the Element X forks (bipe-ios, bipe-android).

Decisions: [ADR 0143](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0143-own-client-on-matrix-rust-sdk.md), [ADR 0144](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0144-app-store-permission-for-bipe-code.md), [ADR 0145](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0145-own-client-setup.md). Plan: [client-rewrite-plan.md](https://github.com/bipe-chat/bipe/blob/main/docs/client-rewrite-plan.md). The tracker is `bipe-chat/bipe`.

## Clean room

These rules come from the plan, section 2. They are mandatory.

- The new repos never contain Element X code, and agents never read Element X sources while they work on them. Agent sessions for the new clients start in the new repo, not in `~/Dev/Bipe`, and do not open `bipe-ios`, `bipe-android` or `bipe-tokens` outside the allowed paths below.
- Allowed reading: BipeKit (`bipe-ios/Components/BipeKit`), `bipe-android/libraries/bipe*`, bipe-shared, bipe-api, the docs in bipe, the Matrix spec, matrix-rust-sdk source and docs (Apache-2.0).
- Bipe features that live inside Element files today (228 iOS files, 199 Android files) are re-specified from the running app, the issue and the ADR. Nobody reads the fork diff to port them: the diff shows Element code around the Bipe lines.
- Never fork, clone or seed a new repo from bipe-ios, bipe-android or bipe-tokens.

Rules for sessions:

- Start a session in `~/Dev/BipeApp/` (only `bipe-app` and `bipe-shared` checked out) or in a cloud session that clones only these two repos.
- A session that worked on a fork issue today does not work in this repo. It gives the issue text to a new session.
- Do not read any `element-hq` repository. Do not read `~/Dev/Bipe/bipe-ios`, `~/Dev/Bipe/bipe-android`, `~/Dev/Bipe/bipe-tokens`, `~/Dev/Bipe/.worktrees` or `~/Dev/Bipe/.factory`. These rules apply to shell commands too (`cat`, `grep`, `find`, `ls`, `git`), not only to the Read tool.
- `.claude/settings.json` denies Read and Edit on the fork checkouts. In Claude Code a deny rule wins over an allow rule, so the settings cannot allow the BipeKit and `libraries/bipe*` subfolders inside a denied folder. Read BipeKit and `libraries/bipe*` from GitHub only, at these paths and nowhere else in the fork repos:
  - `gh api repos/bipe-chat/bipe-ios/contents/Components/BipeKit/<path>`
  - `gh api repos/bipe-chat/bipe-android/contents/libraries/<bipe-module>/<path>`
- If you are not sure that a source is allowed, do not open it. Ask in the issue.

## Allowed reading list

- This repo.
- bipe-shared (screen specs, tokens, contracts, test vectors, `tools/gen.mjs`).
- bipe-api.
- The docs in bipe (`bipe-chat/bipe`, folder `docs/`).
- BipeKit (`bipe-ios/Components/BipeKit`) and `bipe-android/libraries/bipe*`, through GitHub only (see above).
- The Matrix spec (spec.matrix.org).
- matrix-rust-sdk source and docs, `matrix-sdk-ffi` included (Apache-2.0).
- Platform docs from Apple, Google and the Android Open Source Project.

## Licence and file header

Bipe code is AGPL-3.0-only with an additional permission for app stores (ADR 0144, [APP-STORE-PERMISSION.md](APP-STORE-PERMISSION.md)).

Every `.swift` and `.kt` file starts with this header:

```
// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors
```

Other source files carry the same three lines in their own comment syntax.

## Dependencies

- Allowed dependency licences: Apache-2.0, MIT, BSD, ISC, MPL-2.0. Any other licence needs an ADR. [ADR 0150](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0150-google-play-libraries-android-play-build.md) names the one exception: Google Block Store and Firebase Cloud Messaging in the Android `play` flavour (`"scope": "play"` in `tools/licences.json`).
- No analytics SDKs. No crash reporters that send data out of Brazil.
- Icons are Phosphor (MIT) on both platforms, with two exceptions. The tab bar uses native icons ([ADR 0149](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0149-native-tab-bar-icons.md)): SF Symbols in the standard SwiftUI tab bar on iOS and Material icons (`material-icons-extended`) in the standard Material 3 navigation bar on Android, with no custom size or style. On 23 October 2026, MZ explicitly requested SF Symbols for iOS context-menu options; native chat/message context and overflow menu labels use those system symbols. Other icons keep Phosphor. `tools/phosphor.json` lists the icons; `node tools/phosphor.mjs <unpacked @phosphor-icons/core package>` writes them into `ios/Phosphor` (Swift package, `PhosphorIcon`) and `android/libraries/phosphor` (module, `PhosphorIcon`) with Phosphor's LICENSE. Do not hand-edit the output.
- Colours come from `BipeColor` (iOS) and `BipeTheme.colors` (Android), generated from bipe-shared `design/tokens.json`. Do not write colour literals in UI code.
- CI runs the dependency deny-list, a licence check of every dependency, the strings check and the similarity scan (see "Checks").

## Checks

The workflow `.github/workflows/licence.yml` runs these jobs on every PR. All of them are required on `main`. The tools are Node scripts in `tools/` (Node 24, built-ins only). Unit tests: `node --test tools/*.test.mjs` (job `tool-tests`).

- `deny-list`: `node tools/deny-list.mjs`. Fails when `ios/project.yml`, `ios/**/Package.swift`, `ios/**/Package.resolved`, an Android Gradle file or `android/gradle/libs.versions.toml` names an Element package (`element-x-ios`, `element-x-android`, `compound-ios`, `compound-android`, `compound-design-tokens`, `matrix-rich-text-editor`, `element-call`, `element-hq/`, `io.element`).
- `deny-list` also runs `node tools/release-gate.mjs` (bipe-chat/bipe#432): a release build has no developer options and no unlock gesture. Developer-only paths read the build type at compile time (iOS `AppConfig.isDeveloperBuild` under `#if DEBUG`, Android `BuildConfig.DEBUG`), never a stored value. The check fails on a tap gesture of 3 or more taps, a tap or click counter, a string literal that names a developer switch or feature flag on a line without the build type, settings flags not taken from the build type, `isDeveloperBuild = true` outside `#if DEBUG`, DEBUG in the Release configuration of `ios/project.yml`, and a debuggable Android release. The long press on the version text that copies the device ID stays (support needs it). Limits are in the file.
- `licences`: `node tools/licences.mjs`. Reads every `ios/**/Package.resolved` pin and the `[libraries]` of `android/gradle/libs.versions.toml`. Each dependency must be in `tools/licences.json` (SPDX licence, source URL, date checked) with Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC or MPL-2.0. To add a dependency, add its row to `tools/licences.json` in the same PR. An entry with `"unverified": true` prints a warning, not a failure.
- Open source licences screen: `node tools/oss-licences.mjs` writes `ios/Bipe/Resources/OpenSourceLicences.json` and `android/app/src/main/assets/open_source_licences.json` from `tools/licences.json` and `tools/licence-texts/<SPDX>.txt`. After a change to `tools/licences.json`, run it in the same PR; `tool-tests` fails when the files are stale. A new SPDX id needs its standard text in `tools/licence-texts/`.
- `headers`: `node tools/headers.mjs`. Every `.swift` and `.kt` file outside folders named `Generated` or `generated` starts with the header above. A `Package.swift` starts with its `// swift-tools-version` line, and the header follows on line 2.
- `strings`: `node tools/strings.mjs`. Fails on a string literal passed to SwiftUI `Text`, `Button`, `Label`, `.navigationTitle` or Compose `Text`, `text =`. UI text comes from the generated bipe-shared strings. The check is a line-based regex; its limits are in the file.
  - The same job runs `node tools/a11y.mjs` (bipe-chat/bipe#495): a control that shows only an icon needs an accessibility label (SwiftUI `.accessibilityLabel`, Compose `contentDescription`), and a SwiftUI `.onTapGesture` needs a button trait, an accessibility action or a `// a11y: <why>` comment. Tap targets and text size (bipe-chat/bipe#498): an icon-only SwiftUI control outside a toolbar puts `.bipeTapTarget()` (44 pt, `ios/Bipe/Common/TapTarget.swift`) on its label; a Compose click modifier in a chain with a size under 48.dp needs `bipeTapTarget()` (48 dp, `common/TapTarget.kt`) first in the chain; no `.font(.system(size: <number>))` (use a text style or `@ScaledMetric`), no `.dp.toSp()` and no forced `fontScale = 1f`. Gestures (bipe-chat/bipe#499): a SwiftUI `.onLongPressGesture` or `.swipeActions` needs an `.accessibilityAction(s)` next to it; a Compose `combinedClickable` with `onLongClick` needs `onLongClickLabel`, and a swipe or long press gesture needs `customActions` (or `setProgress` for a seek bar). Reduce Motion (bipe-chat/bipe#496): SwiftUI animations go through `withBipeAnimation`, `.bipeAnimation` and `.bipeMove` (`ios/Bipe/Common/Motion.swift`) or check `accessibilityReduceMotion`; Compose animations check `LocalReduceMotion` or use `bipeMotion()` (`common/Motion.kt`, provided by `BipeTheme`). Contrast (bipe-chat/bipe#497): colours switch to the hc tokens by themselves (`BipeColor` follows Increase Contrast; `BipeTheme` follows the Android contrast and High contrast text settings); a blur uses `.bipeMaterial(in:)` (`ios/Bipe/Common/Contrast.swift`), solid under Reduce Transparency; a state is never shown by colour alone. `PhosphorIcon.image` is decorative on iOS; an icon that says something on its own uses `PhosphorIcon.labelledImage(_:)`. Limits are in the file.
- `similarity`: CI only. Runs JPlag 6.3.0 (SHA-256 pinned in the workflow, Java 25) with the language modules `swift` and `kotlin`. It compares each file in `ios/` outside `Generated` folders with each Swift file of `element-hq/element-x-ios`, and each file in `android/` outside `generated` folders with each Kotlin file of `element-hq/element-x-android`, at their latest release tag. A self-test first proves that a copied file fails. Do not run the scan locally: it clones Element code. On a pull request it scans only the platforms whose non-generated source the PR changes, and runs the iOS and Android scans at the same time; a push to `main` scans both. `provenance` is its own workflow, so editing a PR body reruns only that check.
  - **Threshold: 0.5 (50 %) average similarity and at least 100 matched tokens.** JPlag's own default threshold is 0.0 (it keeps every pair), so it cannot be a gate. 0.5 means half of the two files' tokens are in shared matches: low enough to catch a copied file with renamed symbols. A small file of plain Compose or SwiftUI setup also reaches 0.5 against many unrelated files: on bipe-app#5 a 49-token `MainActivity.kt` scored 0.51 to 0.55 against 46 Element files. So a pair fails only when it also shares at least 100 tokens (`MIN_MATCHED_TOKENS`; matched = average × (tokens of both files) / 2, token counts from JPlag's `submissionFileIndex.json`). A pair above 0.5 under that minimum, or with maximum similarity above 0.5 (a small file mostly inside a large one), prints a warning to review, not a failure. JPlag tokens are coarse: a 150-line Compose screen has about 90 Kotlin tokens, so a copy of a file that small only warns. Read the warnings. Change the values in `tools/similarity.mjs` and here together, with the reason.
  - A file that JPlag cannot parse (its Swift grammar is Swift 5.4, beta) is listed as a warning, not compared.
- `sdk-version`: `node tools/sdk-version.mjs`. The file `sdk-version` at the repo root holds the matrix-rust-sdk release for both platforms. Fails when the `exactVersion` in `ios/project.yml`, the pin in `ios/Package.resolved` or `matrix-rust-sdk` in `android/gradle/libs.versions.toml` differs from it. To update the SDK, change all four in one PR.
- `provenance`: `PR_BODY="..." node tools/provenance.mjs`. Fails unless the PR body has a line that starts with `Provenance:`.

## Commits

- Sign off every commit with the DCO: `git commit -s`. See the org [CONTRIBUTING.md](https://github.com/bipe-chat/.github/blob/main/CONTRIBUTING.md).
- Never use `git commit -a` in a shared checkout. Add files by name.

## Build and gates

- No local builds. GitHub CI is the gate ([builds.md](https://github.com/bipe-chat/bipe/blob/main/docs/agents/builds.md)). Agents write code and do not run `xcodebuild`, Gradle or any other compile step on the Mac.
- CI runs iOS on a macOS runner and Android on a Linux runner.
- A red PR is fixed by a new push, not by a local build.
- iOS uses XcodeGen (`ios/project.yml`). Android uses a Gradle version catalog. Do not hand-edit project files.
- `.github/workflows/ios.yml` (the `xcode-27` macOS runner, Xcode 27.1 selected with `DEVELOPER_DIR`) runs `xcodegen generate` and builds Debug and Release for `generic/platform=iOS` with `CODE_SIGNING_ALLOWED=NO`. The tester archive selects the same Xcode and iOS 27.1 SDK for iPhone Duo support. The deployment target stays iOS 18. The build uses the pins in `ios/Package.resolved` and fails when SwiftPM resolves other pins. `Bipe.xcodeproj` and `ios/SupportFiles/` (Info.plist and entitlements) are XcodeGen output and are not committed.
- `.github/workflows/android.yml` (Linux runner, JDK 17) runs `./gradlew assembleDebug assembleRelease` in `android/` and uploads the `play` debug APK as the artifact `bipe-debug-apk`. The app has two flavours (ADR 0150): `play` (tester and store builds, with Google Block Store) and `foss` (no Google Play services, built so it keeps compiling). Google libraries go on `playImplementation` lines only, behind an interface in `libraries/bipe`.
- IDs ([ADR 0147](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0147-own-client-tester-builds.md)): tester builds sit next to the fork on one phone. iOS bundle `chat.bipe.app.next`, extensions `chat.bipe.app.next.nse` and `chat.bipe.app.next.share`, app group `group.chat.bipe.next`. Android application ID `chat.bipe.app.next`, debug suffix `.debug`. At the switch the apps take the fork's IDs (ADR 0061).
- Keychain (bipe-chat/bipe#531, bipe/docs/team-transfer-recovery.md): the app writes its keychain items (hidden password, recovery token, SDK session, app lock, invites) into the app-group keychain group (`BIPE_APP_GROUP`), which has no Team ID and survives the App Store transfer (ADR 0139). The Team-ID group and the fork's group are read-only; `KeychainMove` copies their items at launch (never deletes) and reports `POST /v1/me/keychain-move` per account and device. Never write a new keychain item into a Team-ID group. Android keeps its Keystore and Block Store: no team problem.
- Name: tester builds are named "Bipe Next" under the icon (ADR 0147), the release name stays "Bipe". iOS reads `BIPE_DISPLAY_NAME` in `ios/project.yml`; Android reads `app_launcher_name` in `android/app/src/main/res/values/launcher_name.xml`.

## Tester builds

Every push to main runs `.github/workflows/testflight.yml` (also on demand: `gh workflow run testflight.yml --repo bipe-chat/bipe-app --ref main`). The version name is bipe-shared `product/version.json` version and the build number is its build plus the run number, on both platforms (ADR 0088). Other builds keep the placeholder version `0.1.0`.

- iOS: the build goes to TestFlight (app "Bipe Next", internal testing group). The run also keeps an ad hoc IPA for the devices registered in team `992N457T8D` as the artifact `bipe-next-ipa`. `tools/asc.mjs` does the App Store Connect API steps. The API key is in variables `ASC_KEY_ID`, `ASC_ISSUER_ID` and secret `ASC_KEY_P8`. Signing is manual with the team's existing "Apple Distribution" certificate (secrets `DIST_CERT_P12`, `DIST_CERT_PASSWORD`; bipe-chat/bipe#585): the job imports it into a temporary keychain, `tools/asc.mjs signing` makes the App Store and ad hoc profiles again on each run, and the job never makes a certificate. Automatic signing made a development certificate on each run until the team reached Apple's limit. A new device needs only its registration in the team: the next run puts it in the ad hoc profiles.
- Sign in with Apple (linked Apple account for recovery, ADR 0096): `project.yml` has no Sign in with Apple entitlement, because a profile without the capability fails the archive. `testflight.yml` adds it after `xcodegen generate` only when the repository variable `BIPE_APPLE_SIGN_IN` is `true`; the owner sets it after turning on Sign in with Apple for the app ID. The app shows Apple only when the API offers it (`GET /v1/links/providers`).
- Android: the run keeps a debug-signed APK of `chat.bipe.app.next` as the artifact `bipe-next-apk`.

Install over USB-C (MZ), after unzipping the artifact from the run page:

```
xcrun devicectl device install app --device <UDID> Bipe-Next-<n>.ipa
adb install -r Bipe-Next-<n>.apk
```

`xcrun devicectl list devices` shows the UDID. CI makes a new debug key on each run, so `adb install -r` over an older tester APK can fail with a signature mismatch; then run `adb uninstall chat.bipe.app.next` first.

## Store release

`.github/workflows/store-release.yml` (bipe-chat/bipe#604) sends a build with the fork's IDs (`chat.bipe.app`, ADR 0061) to internal TestFlight on the "Bipe" record and to the Play internal track. It runs on demand only, in the GitHub environment `store`, and its first job fails unless the repository variable `BIPE_SWITCH_READY` is `true`; MZ sets it when the readiness gate bipe-chat/bipe#601 passes. Never set it, run it or widen its track. With `BIPE_ID_SET=store`, `tools/asc.mjs` never registers a bundle ID, changes a capability or creates an app record. `node tools/asc.mjs store-ids [<n>]` reads the fork's bundle IDs, record, last build and versions, and fails when build `<n>` is not higher; `testflight.yml` writes that report into its job summary on every main push. `tools/play.mjs` uploads to the internal track only, after it checks that the versionCode is higher than every uploaded one. The secrets and variables it needs are listed in the workflow header.

## Pull requests

- One PR per issue. The PR covers iOS and Android together ([ADR 0031](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0031-platform-lockstep.md)).
- The PR body links `bipe-chat/bipe#N`.
- The PR body carries a `Provenance:` line. The line names every source of the change: a spec id in bipe-shared `product/screens`, a Matrix spec section, a `matrix-sdk-ffi` API, an ADR, or a Bipe file. Example: `Provenance: bipe-shared product/screens/chat-list; Matrix spec client-server 10.2 (sync); matrix-sdk-ffi RoomListService`.

## Specs and generated files

- Screen specs live in bipe-shared `product/screens`. The code follows the spec. If the code must differ from the spec, change the spec first.
- The app's default language is pt-BR; English is the second language. All UI text comes from bipe-shared strings.
- Generated files come from bipe-shared `tools/gen.mjs`. Never hand-edit a generated file. Change the source in bipe-shared and run the generator again.

## No questions to MZ

MZ works with many agents at once. A question in an agent session blocks that agent until MZ opens it, and MZ does not watch every session.

- Never use the question tool and never wait for an answer in your session.
- For a reversible choice (names, layout, defaults, scope inside the issue, library choice within the licence allow-list), pick the safe default, write `Default chosen: <what>, because <why>. MZ can overrule.` in the PR body, and keep going.
- Only a choice that is hard to undo, outward-facing, costs money or needs an account or secret that only MZ has goes to MZ: write one comment on bipe-chat/bipe#538 that starts with `MZ decision:`, names the issue, gives the options and your default, then continue with other work. The controller session asks MZ and answers on that comment.
- A tool or permission prompt that blocks you is reported the same way, then you continue with work that does not need it.
