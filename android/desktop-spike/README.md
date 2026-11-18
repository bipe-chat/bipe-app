# Windows SDK/core feasibility

This standalone Kotlin/JVM harness is the first checkpoint of [bipe#1019](https://github.com/bipe-chat/bipe/issues/1019), under ADRs 0238, 0267 and 0288. It uses the Android version catalogue and Gradle wrapper without applying an Android plugin or requiring the Android SDK.

`windows-spike.yml` builds the unmodified Matrix SDK at the commit corresponding to the other clients' `sdk-version`, plus `bipe-core`. UniFFI generates Kotlin bindings for both DLLs. The SDK binding configuration keeps its namespace and library name, with Java cleanup instead of Android cleanup. Existing independent badge vectors run before and after SDK calls in the same JVM.

After building the DLL, change only `android_cleaner` to false in each upstream component's binding configuration. UniFFI's command-line `--config` overrides every component in the library and puts their support types in the same Kotlin package. Each component must keep its own namespace. CI checks package names and refuses Android-only imports before compiling the harness. The SDK DLL cache is keyed by its exact source revision, Rust version, target and build profile; the JVM test still checks the SDK revision. The artifact records both native hashes.

The native test creates an encrypted SQLite store with a random in-memory key, restores a synthetic session, closes and reopens it, and requires a typed SDK failure with a different key. Temporary files must be released and removed. The session is synthetic; the harness does not sign in or sync with a server. It contains no account credentials and makes no real-client acceptance claim.

On Windows, native SQLite workers can release file handles after client destruction returns. The harness retries removal for at most ten seconds, records the observed cleanup time and emits its success marker only after the directory is gone. It does not force garbage collection or accept cleanup only at process exit.

Builds run only in GitHub CI. The job `windows-sdk-core-x64` must pass before this checkpoint can merge. Its `bipe-windows-sdk-spike-x64` artifact records source revisions, the shared version/build, native DLLs and test output. It is a diagnostic artifact, not an installable Bipe application. Windows stays planned in the parity ledger.

## Next checkpoints

- Native Windows secure-storage adapter, encrypted-store key lifetime, missing-key refusal and per-account isolation: source is being written in [`desktop-runtime`](../desktop-runtime/README.md), with native tests queued. It has not been built or validated; MZ requested a coding-only checkpoint on 10 November. Never persist the synthetic harness approach in a production client.
- Compose for Desktop packaging, generated Bipe strings/tokens/registries and the same navigation hierarchy as the other clients.
- Phone-approved QR linking and encrypted sync/history, with released iOS and Android; same-device restart, phone-offline use, revocation and reconnect.
- Windows on Arm build/runtime proof, native dependencies, signed installer/update path and real Windows 11 device acceptance.

Mac features are paused at build712. Their open acceptance and parity gaps remain in #1018; the pause does not mark them passed.
