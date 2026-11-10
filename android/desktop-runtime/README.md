# Windows account runtime

Source checkpoint for [bipe#1345](https://github.com/bipe-chat/bipe/issues/1345), built on the merged x64 SDK/core proof in app PR589. This module has no Android plugin, `Context`, Keystore or phone UI dependency. `desktop-spike` includes it; CI generates the pinned SDK and core Kotlin bindings into its ignored `generated/` directory.

**10 November 2026: source only. MZ requested coding without builds. No native tests of this adapter have run; no installer or tester is available. Do not push or dispatch CI until that hold is lifted.** PR589's passing SDK harness does not validate this new module.

## Storage and ownership

- Each link starts in a random UUID slot under the current Windows user's local application data, before the account is known. The slot is never renamed while native handles exist. Data, cache, drafts and media have separate directories within that slot.
- The SDK's random 32-byte store key lives in a current-user Credential Manager generic credential. `CRED_PERSIST_LOCAL_MACHINE` persists that user's credential on this machine; it does not grant other Windows users access.
- The complete session is an atomic DPAPI current-user ciphertext file, bound to the slot key through DPAPI optional entropy. OAuth data may exceed Credential Manager's blob limit. The plaintext key/session is never a file or a log entry. Temporary plaintext/native byte buffers are cleared; SDK session strings still have the SDK/JVM's memory lifetime.
- A pending session can receive SDK token refreshes. Only a completed phone-link operation promotes it to linked. Promotion serializes across processes and rejects an account already linked in another slot. A pending record after a crash needs explicit recovery/discard, never automatic signed-in status.
- Opening a linked slot requires its existing key and nonempty SDK state/crypto databases. It does not generate a replacement key or open an empty store. Corrupt envelopes, mismatched slots, changed device identities, missing directories and redirected account directories fail before restore.
- `AccountLease` is captured by that account's SDK delegates. Its retired owner cannot save again, including after another owner opens the slot. A file lock excludes overlapping owners across threads/processes.
- `AccountLifetime` owns tasks, SDK callback handles, sync and client resources. It retires the epoch, cancels/joins tasks, releases resources in reverse order, waits for actual native Windows handle release, then releases the slot lock. A cleanup failure retains ownership for retry. There is no forced GC or process-exit cleanup acceptance.
- Unlink/revoke must first retire callbacks and persist a tombstone/remove the credential. `RevocationResult.Blocked` must remain a visible failure, not a completed unlink. File removal is allowed only after durable revocation, credential removal and complete SDK teardown. This local adapter does not replace server-side device revocation.

## Validation queued for CI

`windows-spike.yml` is prepared to run `:desktop-runtime:test` before the existing SDK/core harness. It is not dispatched during the coding hold. JVM fixtures cover pending/linked restore, key/store loss, two owners, slot/identity mismatch, cross-account records, duplicate linking, stale callbacks, partial revocation and resource-close retry. The fake protection adapter is only an opaque in-memory fixture, never product encryption.

Windows-only tests exercise the actual `CREDENTIALW` x64 ABI, Credential Manager create/read/delete, DPAPI roundtrip/wrong entropy/corruption, a large OAuth envelope, and native SDK reopen with stable synthetic device identity. They make no real login/sync requests. Before merge, all applicable CI gates, including Android and `windows-sdk-core-x64`, must pass on the exact head.

Still required: a second real Windows user/profile permission test, abrupt-exit/restart acceptance, actual phone approval, revoke/reconnect and media cleanup with a real account, Windows 11 physical acceptance, ARM64, signed installation/update delivery. These remain tracked in #1345/#1346/#1019. Windows remains planned in the parity matrix.

Provenance: Bipe's `AccountSession.kt`, `MacAccount.swift` and `MacSessionVault.swift`; shared account lifecycle and ADRs0238/0267/0288; pinned Apache-2.0 Matrix SDK FFI client/store/session APIs; Microsoft `CREDENTIALW`/`CredReadW`/`CredWriteW`/`CredDeleteW`, DPAPI and `CreateFileW`; Apache-2.0 JNA Win32 API declarations. No Element source is used.
