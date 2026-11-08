<!--
  SPDX-License-Identifier: AGPL-3.0-only
  Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
  Copyright 2026 Bipe contributors
-->
# bipe-core releases

ADR 0267, bipe-chat/bipe#1277 item 4. Clients take bipe-core from a GitHub release, never from a CI workspace.

## Make a release

```bash
gh workflow run core-release.yml --repo bipe-chat/bipe-app --ref main -f version=<x.y.z>
```

`core-release.yml` runs the jobs of `core.yml` on that commit (every harness must pass), then publishes the release
`core-<x.y.z>` as a prerelease, never "latest". A version is never replaced. Assets:

| Asset | Client | Use |
| ----- | ------ | --- |
| `bipe_coreFFI-<v>.xcframework.zip` | iOS, Mac | SwiftPM `binaryTarget(url:checksum:)` |
| `bipe_core-<v>.swift` | iOS, Mac | the Swift bindings, a source file of the package |
| `bipe-core-<v>.aar` | Android | Gradle, Kotlin bindings and arm64-v8a, armeabi-v7a, x86_64 |
| `bipe-core-jvm-<v>.jar` | Windows, Linux (JVM) | Kotlin bindings and the natives in JNA's layout |
| `bipe-core-web-<v>.tgz` | web | ubrn wasm2 TypeScript bindings and the wasm module |
| `bipe-core-<v>.json` | all | version, source commit, CI run, Rust, UniFFI and SDK versions, SHA-256 of each asset |
| `SHA256SUMS` | all | the same sums |

The AAR and the jar have no POM. A consumer adds `net.java.dev.jna:jna:5.17.0` (`@aar` on Android) and
`kotlinx-coroutines-core` itself.

## Pin a release

`release.json` in this folder is the manifest of the pinned release. Each consumer pins the same checksum.

| Consumer | Pin |
| -------- | --- |
| SwiftPM, `swift/Package.swift` | `version`, `checksum` of the zip, and `Sources/BipeCore/generated/bipe_core.swift` (the release file) |
| Gradle, `android/` | ivy repository on the release downloads, `gradle/verification-metadata.xml` with the AAR's SHA-256 |
| JVM, web | `core.yml` downloads the asset and checks it with `sha256sum` against `release.json` |

`tools/core-release.test.mjs` (job `tool-tests`) fails when a pin differs from `release.json`. The `core-release-*`
jobs of `core.yml` load each pinned artifact from the release and run the vectors: SwiftPM on macOS arm64, macOS
x86_64 (Rosetta) and the iOS simulator; the bench APK on the x86_64 emulator; the jar on Linux and Windows; the web
bundle in Node.

To move to a new release: copy its `bipe-core-<v>.json` to `release.json`, put its version and zip checksum in
`swift/Package.swift`, its `bipe_core-<v>.swift` in `swift/Sources/BipeCore/generated/bipe_core.swift`, and its AAR
version and SHA-256 in `android/gradle/verification-metadata.xml`.

## Phone bench (bipe-chat/bipe#1277 item 8)

`android/bench` is a small app on the release AAR. It runs the vectors on the phone and shows the p95 of one
verification against the 5 ms budget. `core-release-android` builds it signed with the tester key (artifact
`core-bench-apk`) and runs it once on the x86_64 emulator.


## iPhone bench (bipe-chat/bipe#1277 item 5)

`ios/bench` is the same bench on the release XCFramework, through `swift/` (XcodeGen, bundle ID
`chat.bipe.core.bench`, never shipped). `core-release-ios-bench` registers the bundle ID on its first run
(`BIPE_ID_SET=bench node tools/asc.mjs signing <serial>`), makes its ad hoc profile again with every enabled iPhone of
team 992N457T8D, and keeps the IPA as the artifact `core-bench-ipa`. With the iPhone on USB:

```bash
xcrun devicectl device install app --device <UDID> bipe-core-bench-<v>.ipa
xcrun devicectl device process launch --console --device <UDID> chat.bipe.core.bench
```

The console prints one `BipeCoreBench:` line with the verdict and the `BIPE_CORE_METRICS` line.
