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

- Allowed dependency licences: Apache-2.0, MIT, BSD, ISC, MPL-2.0. Any other licence needs an ADR.
- No analytics SDKs. No crash reporters that send data out of Brazil.
- Icons are Phosphor (MIT) on both platforms. Do not use SF Symbols.
- CI runs the dependency deny-list, a licence check of every dependency, the strings check and the similarity scan (see "Checks").

## Checks

The workflow `.github/workflows/licence.yml` runs these jobs on every PR. All of them are required on `main`. The tools are Node scripts in `tools/` (Node 24, built-ins only). Unit tests: `node --test tools/*.test.mjs` (job `tool-tests`).

- `deny-list`: `node tools/deny-list.mjs`. Fails when `ios/project.yml`, `ios/**/Package.swift`, `ios/**/Package.resolved`, an Android Gradle file or `android/gradle/libs.versions.toml` names an Element package (`element-x-ios`, `element-x-android`, `compound-ios`, `compound-android`, `compound-design-tokens`, `matrix-rich-text-editor`, `element-call`, `element-hq/`, `io.element`).
- `licences`: `node tools/licences.mjs`. Reads every `ios/**/Package.resolved` pin and the `[libraries]` of `android/gradle/libs.versions.toml`. Each dependency must be in `tools/licences.json` (SPDX licence, source URL, date checked) with Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC or MPL-2.0. To add a dependency, add its row to `tools/licences.json` in the same PR. An entry with `"unverified": true` prints a warning, not a failure.
- `headers`: `node tools/headers.mjs`. Every `.swift` and `.kt` file outside folders named `Generated` or `generated` starts with the header above.
- `strings`: `node tools/strings.mjs`. Fails on a string literal passed to SwiftUI `Text`, `Button`, `Label`, `.navigationTitle` or Compose `Text`, `text =`. UI text comes from the generated bipe-shared strings. The check is a line-based regex; its limits are in the file.
- `similarity`: CI only. Runs JPlag 6.3.0 (SHA-256 pinned in the workflow, Java 25) with the language modules `swift` and `kotlin`. It compares each file in `ios/` outside `Generated` folders with each Swift file of `element-hq/element-x-ios`, and each file in `android/` outside `generated` folders with each Kotlin file of `element-hq/element-x-android`, at their latest release tag. A self-test first proves that a copied file fails. Do not run the scan locally: it clones Element code.
  - **Threshold: 0.5 (50 %) average similarity.** JPlag's own default threshold is 0.0 (it keeps every pair), so it cannot be a gate. 0.5 means half of the two files' tokens are in shared matches: well above the overlap that SwiftUI and Compose boilerplate cause, and low enough to catch a copied file with renamed symbols. A pair with maximum similarity above 0.5 (a small file mostly inside a large one) prints a warning to review, not a failure. Change the value in `tools/similarity.mjs` and here together, with the reason.
  - A file that JPlag cannot parse (its Swift grammar is Swift 5.4, beta) is listed as a warning, not compared.
- `provenance`: `PR_BODY="..." node tools/provenance.mjs`. Fails unless the PR body has a line that starts with `Provenance:`.

## Commits

- Sign off every commit with the DCO: `git commit -s`. See the org [CONTRIBUTING.md](https://github.com/bipe-chat/.github/blob/main/CONTRIBUTING.md).
- Never use `git commit -a` in a shared checkout. Add files by name.

## Build and gates

- No local builds. GitHub CI is the gate ([builds.md](https://github.com/bipe-chat/bipe/blob/main/docs/agents/builds.md)). Agents write code and do not run `xcodebuild`, Gradle or any other compile step on the Mac.
- CI runs iOS on a macOS runner and Android on a Linux runner.
- A red PR is fixed by a new push, not by a local build.
- iOS uses XcodeGen (`ios/project.yml`). Android uses a Gradle version catalog. Do not hand-edit project files.

## Pull requests

- One PR per issue. The PR covers iOS and Android together ([ADR 0031](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0031-platform-lockstep.md)).
- The PR body links `bipe-chat/bipe#N`.
- The PR body carries a `Provenance:` line. The line names every source of the change: a spec id in bipe-shared `product/screens`, a Matrix spec section, a `matrix-sdk-ffi` API, an ADR, or a Bipe file. Example: `Provenance: bipe-shared product/screens/chat-list; Matrix spec client-server 10.2 (sync); matrix-sdk-ffi RoomListService`.

## Specs and generated files

- Screen specs live in bipe-shared `product/screens`. The code follows the spec. If the code must differ from the spec, change the spec first.
- Generated files come from bipe-shared `tools/gen.mjs`. Never hand-edit a generated file. Change the source in bipe-shared and run the generator again.
