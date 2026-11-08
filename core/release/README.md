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
