# Bipe for Mac: feasibility spike

Started 6 November 2026. Tracking: [bipe#1018](https://github.com/bipe-chat/bipe/issues/1018). Scope: [ADR 0266](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0266-mac-first-full-personal-and-work-parity.md). Native desktop architecture: [ADR 0238](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0238-native-mac-and-windows-apps.md).

`project.yml` creates a macOS 15+ SwiftUI app. CI builds Apple Silicon and Intel with the same Matrix SDK release as the phones. It reuses BipeKit's strings, rules and tokens, and the iOS client's SDK listener/vector adapters. It adds no dependency.

## First slice

- The Mac displays an MSC4108 QR code. The phone scans it in **Conectar um computador**. Enter the phone's check code on the Mac, then approve on the phone.
- Codes refresh after 25 seconds if nobody scanned them. A new attempt has its own store folder. Unapproved tokens are not persisted.
- After approval and key transfer, the Mac starts sync and displays joined rooms. The text timeline reads available history, sends messages and checks incoming text with the shared scam detector.
- The session, refresh token and random database key live in a separate, non-synced macOS Keychain service. SQLite stores are encrypted and excluded from backups. A failed Keychain read never opens the store without its key.
- Restart restores the same device. A missing crypto store fails closed instead of making new keys under the saved device ID. SDK offline mode makes cached state available without waiting for a network response.
- An authentication refusal ends the local session. Delayed refresh callbacks cannot save it again after revocation. The encrypted cache remains local; this spike makes no remote-erasure claim.

The spike holds one linked account. The Mac uses Bipe's generated app icon, shared rounded typography, adaptive colors for light/dark/Increase Contrast, Phosphor glyphs and large native buttons. Desktop cards, message bubbles, account rows and the composer use the shared tokens. Multiple personal/work accounts, rich media, search, notifications, calls and the work UI remain in #1018. Windows follows the Mac spike evidence.

QR codes use one binary byte segment, as the web does, so released phone scanners can read them. Apple's Core Image encoder mixed an alphanumeric MATRIX header with byte segments; the released iPhone scanner ignored that result. The native MIT-licensed Nayuki encoder is vendored unchanged, with source hashes and its notice in macos/QRCode. The regression test decodes the final bitmap with the released phone's byte-only rules. No phone update is needed for this compatibility fix.

## CI evidence

`.github/workflows/macos.yml` builds Debug and Release for both architectures. Hosted XCTest checks native SDK/SQLite loading without a server, Keychain error handling with synthetic records, same-device restoration, the approval gate and delayed refresh after revocation. Unit tests never read real credentials.

The Release job uploads `bipe-mac-spike`, an ad hoc signed ZIP. This is a test artifact. Developer ID signing, notarization, store distribution and a supported updater remain later work. A build or synthetic test does not prove real-account linking or cross-device behavior.

## Real-account acceptance record

Record the build commit, Mac architecture/OS, phone platform/build and result in #1018. The following checks are pending until that evidence exists:

- Link from iOS and Android; reject a wrong check code or a declined request.
- Read available encrypted history and exchange text in both directions. Confirm local scam warnings.
- Put the phone offline after linking. Send and receive on the Mac.
- Quit and restart. Confirm the same device ID and history; repeat offline, then reconnect.
- Revoke the Mac on the phone. On reconnect it must leave the signed-in UI and must not restore the revoked session after restart.
- Repeat on Apple Silicon and Intel. Record failures and gaps without marking the full Mac product complete.

## Sources

- BipeKit and `ios/Bipe/Account/AccountSession.swift`: local stores and Matrix security policy, read 6 November 2026.
- `web/src/QrLogin.tsx`, `ios/Bipe/Account/AccountSession+QRLogin.swift`, shared link-computer specs: QR flow, read 6 November 2026.
- matrix-rust-components-swift 26.08.11 generated API: `GeneratedQrLoginProgress`, `LoginWithQrCodeHandler`, `ClientBuilder`, `RoomListService` and `Timeline`, read through `gh` on 6 November 2026.
