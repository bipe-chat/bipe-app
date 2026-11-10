# Web spike: matrix-sdk-ffi in the browser

Issue: bipe-chat/bipe#900. Decision: ADR 0216 (spike), ADR 0214 (host `web.bipe.chat`). Sign-in rules: ADR 0116, ADR 0118, ADR 0121.

## Status (8 November 2026)

This file records the spike of 1 November. The web client in `web/src` has grown past it since then (more than 100 commits). The build still carries the 4 SDK patches in `web/patches/` and the prerelease `@ubjs/core` and `@ubjs/wasm` 0.31.0-6 (`package.json`). Those stay until matrix-sdk-ffi and ubrn ship what they patch (bipe-chat/bipe#1121, the SDK upgrade). No agent has run the page in a browser; MZ's hand test is the check (bipe-chat/bipe#1125).

## Result (1 November 2026)

**Builds, and the module runs. Not yet run in a browser.** `matrix-sdk-ffi` at the phones' `sdk-version` builds for `wasm32-unknown-unknown` in CI with 4 small patches, ubrn generates the TypeScript bindings, and the page in `web/` typechecks and bundles against them. Outside a browser, the module opens, answers a sync call and builds a `Client` against production (async Rust futures and HTTP work). No agent ran the page: MZ tests by hand (steps below). The spike verdict ("works" or "use matrix-js-sdk + `matrix-sdk-crypto-wasm`") waits for that test.

| Step | State, 1 November 2026 |
| ---- | ---------------------- |
| Resolve `sdk-version` to an SDK commit in CI | Works |
| Build `matrix-sdk-ffi` (feature `js`, `indexeddb`) for wasm32 | Works, with patches 0001 to 0004 |
| Generate TypeScript bindings (ubrn 0.31.0-6, `wasm2`) | Works: 8 namespaces, typechecks with TypeScript 6 strict |
| Build the page (Vite, React, Tailwind, pnpm) | Works |
| Open the stripped module and call it (Node 24, one-off check, not in CI) | Works: `sdkGitSha()` returns `15a3ce2`; `ClientBuilder().serverNameOrHomeserverUrl("bipe.net.br").inMemoryStore().build()` resolves, and `homeserver()` is `https://matrix.bipe.net.br/` (well-known discovery over `fetch` against production) |
| QR sign-in from the phone (MSC4108) | Not tested |
| Room list (`RoomListService`) | Not tested |
| Timeline, history decrypts | Not tested |
| Send one encrypted text, phone shows it decrypted | Not tested |
| Safari | Not tested |

## How CI finds the SDK commit

The phones do not build the SDK. iOS pins the Swift package `matrix-org/matrix-rust-components-swift` at `exactVersion` = `sdk-version`. Android pins `org.matrix.rustcomponents:sdk-android` at the same version (`tools/sdk-version.mjs` checks that all pins are equal). Both are prebuilt from one matrix-rust-sdk commit. The GitHub release `<sdk-version>` of `matrix-rust-components-swift` names that commit in its body as a `matrix-rust-sdk/tree/<sha>` link.

- `sdk-version` 26.08.11 resolves to matrix-rust-sdk `15a3ce2369a1e96fcad02cb2b1da3198ef23348a` (11 August 2026).
- `.github/workflows/web.yml` reads the release with `gh release view` and fails when the body names no commit.

## Patches (`web/patches/`)

Each patch is a Bipe change against Apache-2.0 matrix-rust-sdk code, written from the CI build error or from the ubrn docs. CI applies them in name order with `git apply`.

| Patch | What | Why |
| ----- | ---- | --- |
| `0001-ffi-no-forced-experimental-search.patch` | `matrix-sdk-ffi/Cargo.toml`: remove `experimental-search` from the forced `matrix-sdk` features. | The ffi crate already has an `experimental-search` feature, but its `matrix-sdk` dependency turns the feature on always. That pulls in tantivy, which does not build for wasm. With the patch, `--no-default-features` turns search off. The ffi code already gates every search item behind the feature. |
| `0002-ffi-ubrn-wasm2-runtime.patch` | `matrix-sdk-ffi`: add `uniffi-runtime-wasm = "=0.31.0-6"` for wasm targets, and `extern crate uniffi_runtime_wasm as _;` in `lib.rs`. | The ubrn `wasm2` player needs `__ubrn_alloc`, `__ubrn_free` and a panic hook exported from the module (ubrn docs, wasm2 reference). `ubrn build wasm2` refuses the crate without the dependency. |
| `0003-sdk-call-status-web-time.patch` | `matrix-sdk/src/automatic_call_status.rs`: `ruma::time::SystemTime` instead of `std::time::SystemTime`. | Build error E0308: `SecondsSinceUnixEpoch::from_system_time` takes the `web_time` type on wasm. `std::time::SystemTime::now()` also panics on wasm32-unknown-unknown. |
| `0004-pin-tracing-appender-0.2.4.patch` | Workspace `Cargo.toml`: `tracing-appender = "=0.2.4"`. | Build error E0425: tracing-appender 0.2.5 calls `symlink::symlink_file`, which the `symlink` crate does not define on wasm. The workspace `[patch]` to the tracing git rev (0.2.3) is unused upstream because the lockfile has 0.2.5. Only the web build gets 0.2.4; the phones keep their prebuilt packages. |

Upstream candidates: 0001 and 0003 are plain fixes. 0004 is better fixed upstream by moving `tracing-appender` to the non-wasm dependencies of `matrix-sdk-ffi`.

## Build

- Toolchain: Rust stable, target `wasm32-unknown-unknown`; uniffi-bindgen-react-native 0.31.0-6 (`cargo install --git ... --tag`); `wasm-bindgen` CLI at the version in the SDK lockfile (0.2.126).
- Features: `js`, `indexeddb`, `unstable-msc4274`, no defaults (no SQLite, no aws-lc-rs, no search). On wasm, reqwest uses the browser's `fetch`.
- Cargo profile `small-release-stripped` (upstream profile for the Apple framework: opt-level `s`, LTO).
- ubrn flavour `wasm2`: one cargo build for wasm32; the bindgen reads the UniFFI metadata from the `.wasm`. The older `web` flavour needs a second native build and a generated shim crate.
- CI time with a warm cache: about 6 minutes for the wasm build, under 10 minutes for the job.

## Bundle size (1 November 2026)

| File | Bytes | gzip -9 | brotli -q 11 |
| ---- | ----- | ------- | ------------ |
| `matrix_sdk_ffi.wasm`, `small-release`, not stripped | 48,658,619 | 10,357,554 | |
| `matrix_sdk_ffi.wasm`, `small-release-stripped` (LTO), not stripped | 48,669,513 | 10,359,478 | 5,914,788 |
| `matrix_sdk_ffi.wasm`, stripped of custom sections (what CI ships now) | 28,458,554 | 8,249,569 | 5,113,575 |
| JS bundle (React, bindings, ubrn player) | 1,476,954 | 210,559 | 160,501 |
| CSS | 7,536 | 2,485 | 2,116 |

- LTO changed the module by less than 0.1 %. The module was 24.5 MB code, 3.6 MB data and 20.2 MB `name` section (function names). CI now strips all custom sections with `wasm-tools strip --all`; panics then show no Rust function names.
- Not yet tried: `wasm-opt -Oz` (binaryen), and features off that a web client does not need (widgets, federation API, content scanner).
- For comparison: matrix-js-sdk's `matrix-sdk-crypto-wasm` holds only the crypto. This module also holds sync, room list, timeline, send queue and the IndexedDB stores.

## Page (`web/`)

- Vite 8, React 19, TypeScript 6, Tailwind 4, pnpm. Static: no server code.
- `src/sdk.ts`: opens the module (`uniffiInitAsync`), builds the `Client` with `serverNameOrHomeserverUrl("bipe.net.br")`, IndexedDB stores (`IndexedDbStoreBuilder("bipe-web")`) and native sliding sync.
- `src/App.tsx`: QR sign-in with `LoginWithQrCodeHandler.generate` (the browser shows the code, ADR 0116), room list with `RoomListService.allRooms().entriesWithDynamicAdapters`, one timeline with `Room.timeline()`, send with `Timeline.send(messageEventContentFromMarkdown(...))`.
- `src/session-store.ts`: the Matrix session (tokens, user, device) in IndexedDB, one record per browser (ADR 0121). The SDK keeps its own state and crypto stores in IndexedDB.
- OAuth client metadata matches the phones: redirect `chat.bipe:/callback`, client URI `https://bipe.chat`. The SDK registers a native client; the QR flow uses the device grant and never opens the redirect.
- Text on the page is spike text in English, not product copy.

## Hand test for MZ

1. Open the last green `web` run of this PR or of `main` and download the artifact `web-bindings`.
2. In `bipe-app/web`: unzip it into `src/generated/`, run `pnpm install`, then `pnpm dev`, and open the address it prints in Chrome.
3. The page shows a QR code. On the phone (Bipe from TestFlight or the tester APK): Settings > Linked devices > Link a computer, scan the code.
4. Type the two-digit code that the phone shows into the page. Approve on the phone.
5. The page shows your chats. Open one chat: older messages must show as text, not "(unable to decrypt)".
6. Send a message from the page. The phone must show it decrypted.
7. Reload the page: it must open the chats again without a new QR code.
8. Repeat 2 to 7 in Safari.

Report what you see on bipe-chat/bipe#900. If step 3 to 6 fails, the browser console (DevTools) has the error.

## Known risks and blockers (1 November 2026)

- **Runtime is unproven.** Upstream CI runs wasm tests for `matrix-sdk-base`, `matrix-sdk-common`, `matrix-sdk-qrcode` and the IndexedDB stores, but only a compile check for `matrix-sdk-ui` (room list, timeline) and none for `matrix-sdk-ffi` on wasm. A call that reaches `std::time::SystemTime::now()` or a thread panics at run time, not at build time.
- **Size.** 5.1 MB brotli, 8.2 MB gzip after stripping. This is a slow first load on a phone network; the web client is for computers (ADR 0116).
- **Store encryption.** The SDK IndexedDB stores have no passphrase in the spike. Production needs a decision on a store key (for example a non-extractable WebCrypto key in IndexedDB).
- **Logs.** `initPlatform` is not called: on wasm it only sets the panic hook, and its file logging does not apply.
- **Hosting.** `web.bipe.chat` is not deployed. The production page needs the strict CSP of ADR 0121 with `wasm-unsafe-eval` for the module, and no `unsafe-eval` (the ubrn player falls back from `new Function` to an interpreter).
- **ubrn `wasm2` is new** (since 0.31.0-3, 2026). The bindings file for `matrix_sdk_ffi` is 2.5 MB of TypeScript.
