# Windows phone-link source checkpoint

Part of [bipe#1346](https://github.com/bipe-chat/bipe/issues/1346), stacked on the local #1345 secure-storage branch. **Source only, uncompiled, with no live phone approval.** MZ requested coding without builds on 10 November 2026. Neither branch has been pushed and no CI has been dispatched. These changes do not make Windows installable or change its planned parity status.

## Implemented in source

`PhoneLinkAttempt` reserves one fresh pending runtime, exposes one observable phase and ignores repeat starts/check-code submissions. The SDK's generated QR login reports QR, check code, approval and secret import. Only a successful return from the native protocol followed by durable account promotion can expose `Linked`. Progress `.Done` alone does not do it. A retired account or cancelled attempt cannot commit a late result. Errors are finite enum values; SDK messages, OAuth codes, QR content and parser bodies never reach UI text or logs.

`SdkPhoneLinkProtocol` uses the pinned SDK's `newLoginWithQrCodeHandler`/`generate` and `CheckCodeSender.send`, as the own Mac client does. It releases QR objects, check-code senders and handlers explicitly. It preserves binary bytes, supports 00–99 check codes and keeps approval on the phone. The existing released phone scanner/approval contract is the target; no phone update is assumed.

`BinaryLinkQr` uses the already pinned Apache-2.0 ZXing dependency. It emits one byte-mode segment without ECI, preserves the complete MATRIX prefix and high/zero bytes, and includes a four-module quiet zone. The Compose renderer must use whole pixel modules without interpolation and a stable light QR surface. `copyText` is RFC4648 base64 for the phone paste fallback, never the QR renderer input. A roundtrip test checks decoded byte segments, the symbology identifier and every byte value; it has not run.

API review caught a compatibility trap before build: ZXing 3.5.3 adds an ECI segment whenever `CHARACTER_SET` is supplied, even for ISO-8859-1. This renderer intentionally uses its default ISO-8859-1 encoding with no hint and no compact mode. The own Android `LinkPhoneShow.kt` currently supplies that hint despite its comment claiming no ECI; verify that separate phone-show path during cross-client acceptance. This does not establish a released-phone failure or require a phone update for the Windows-generated QR.

`WindowsLoginRegistration` requires a public Windows OAuth client ID from release configuration. It deliberately has no default Mac/web client ID or dynamic-registration fallback. Its redirect is `chat.bipe.app.windows:/callback`. No server/client registration has been created or deployed by this checkpoint. Confirm the deployed MAS registration and Windows callback/installer association before real phone testing.

## Next integration work

- Native Compose source and canonical resource generation now exist in [`../desktop-app`](../desktop-app/README.md), with Bipe naming/version/build, upper-left account switcher, palette selection display, favourite/list selection and SDK text chat. They remain uncompiled/unvalidated; Mac feature work stays paused.
- The pending-slot create/open flow and `PhoneLinkAttempt.phase` are wired to QR/check/approval screens in source. Pending discard/relink still requires a safe recovery flow: explicitly revoke/discard after complete teardown, then provision a new slot. A crash with a pending token must not silently restore as linked. The current UI preserves pending slots and points to phone device management.
- Add local removal/revocation recovery UI, including `AccountLifetime.pendingCleanup()`/`retryCleanup()`. Server-side logout of a partially created or duplicate device must finish before calling it removed. Never touch the already linked device when a new duplicate link fails.
- Owned sync/room-list/timeline/text composer source is present. Portable scam warnings ([#1455](https://github.com/bipe-chat/bipe/issues/1455)), linked-device controls, authenticated media and the remaining parity surfaces are still open. Dedicated Windows registration/callback delivery is [#1456](https://github.com/bipe-chat/bipe/issues/1456). No real phone acceptance is claimed.
- When MZ lifts the build hold: push/review #1345 first and pass native Windows storage + all applicable checks, including Android, on the exact head. Then validate this dependent source in CI and test the final native QR with released iOS and Android on Windows 11. Record key/history transfer, stable device after restart, phone offline, reconnect and remote revocation. No current tester ETA is promised.
- Keep signing, installer/update, Windows on Arm and the remaining client matrix explicit in #1019. #1345 and #1346 remain open; no parity acceptance box is checked by source changes.

Provenance: own `MacAccount.swift`, `MacQRCode.swift`, Android `LinkPhoneShow.kt`, `LinkComputerModel.kt` and `OldPhoneModel.kt`; bipe-shared link-computer scan/approve contracts; pinned Apache Matrix SDK generated QR APIs and Apache ZXing encoder/decoder. No Element source is used.
