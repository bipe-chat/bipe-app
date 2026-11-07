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

The Mac holds multiple linked personal and company accounts. Each account keeps its own SDK client, sync and encrypted store. The first update copies the installed single-account Keychain record into an ordered account list without changing its device ID, tokens, store folder or encryption key. A failed migration preserves the old record. Refresh and revocation change only the affected account; linking an existing account twice cannot overwrite its working device.

Native SwiftUI tabs hold Chats, Groups and You for personal accounts, or Chats, Work and You for company accounts. The account photo opens the switcher at the upper left, as in the web toolbar; there is no account footer or extra navigation rail. Each mode keeps its own navigation state. Company Chats includes groups. RoomInfo.isDm is the DM rule, including groups with two members. Room-name search works in both lists. Unread status is shown with badges, without an Unread chip. Account rows show names and handles; Matrix IDs stay internal. Company account avatars are rounded squares. The Work overview reads live today counts, tool states, catalog items and people. It does not infer an owner role or expose phone-authorized operations. Work editors, videos/files/voice, global search, notifications and calls remain in #1018. Windows follows the Mac evidence.

The app is named Bipe in Finder, the menu bar and About. It uses Bipe's generated icon, shared rounded typography, adaptive colors for light/dark/Increase Contrast, Phosphor glyphs and large native buttons. Desktop cards, message bubbles, account rows and the composer use the shared tokens. Rounded text follows the existing iOS/web type scale (body 17, subheadline 15, footnote 13), with accessibility scaling.

Favourites use the synced m.favourite tag and BipeFavouritesOrder account data, a three-column avatar grid, and the existing per-scope reorder rules. A separate favourite stream finds rooms outside the first loaded page; joined, favourite and archived streams share one SDK subscription union. Favourites do not appear again in the list. The account menu, list, favourites and timeline header load avatar thumbnails through the SDK. A DM uses its peer's photo, never the room avatar. A group uses its own photo or up to three other members as a cluster, with the same geometry as iOS and web. Unknown/company/official avatar rules follow the existing clients. Rows show names, available previews/dates, unread/mention indicators and mute state.

## Remaining parity gaps

The Mac is still a preview. The 159-capability ledger includes partial implementations as planned until their full behavior and evidence exist.

| Area | Still missing or incomplete | Tracking |
| --- | --- | --- |
| Desktop and account lifecycle | Complete settings/navigation destinations, account removal/sign-out, app lock, full key/recovery/device surfaces, theme controls, updater and notarized distribution; cross-device/offline/revocation acceptance | #1161, #1165 |
| Conversations | Start chat and requests, full search, newest-DM selection/clearing, archive synchronization/actions, reply quotes, editing/deleting/forwarding, reactions, pins, receipts/ticks, profile/invite/Matrix routing, Mural and assistant drafts | #1162 |
| Groups | Creation/details/membership, roles/moderation, group links/privacy, threads, polls and communities | #1163 |
| Media | Videos/files/voice, gallery, captions after sending, full media settings/invite photos, stickers, location and share integrations | #1164 |
| Identity and safety | Complete profiles, verification/attestation/marks, invites/contacts, account/device/privacy settings, support/report/export/delete flows and all safety surfaces | #1165 |
| Work | The current screen is a read-only overview; operational tool screens/actions, roles/people/devices, catalog/order/quote/agenda/queue/payment editors remain. The inbox/key-model merge retains #1066 as its own prerequisite | #1166 |
| Desktop attention | Calls, notifications, badges/notification sender photos and work focus/time behavior | #1167 |

Text replies use the SDK's reply relation. Composer text and reply targets live in the room's encrypted SDK store. Each account has an ordered draft-write queue, so reopening a room waits for edits from its dismissed view. Visible conversations send read receipts only while Bipe is active. The privacy switch keeps DM receipts private when off; group receipts keep the shared public-receipt rule.

Images use SDK-authenticated encrypted media retrieval, with account-scoped memory previews, a native viewer and Save original. The account's media preview policy is observed through m.media_preview_config; a failed initial fetch waits for a tap. A DM the person accepted from chat requests on any device waits for a tap, through the synced chat.bipe.request_rooms list; a room also waits until that list has been read. Photo sending includes captions, encrypted image descriptions, reply relations, metadata removal and standard/high quality. Animated GIFs keep frames and timing; Reduce Motion stops playback. Upload files remain available until the SDK attachment send finishes. Shared BipeMessageText renders formatted messages and clickable links without executing HTML or loading remote embeds. The scam detector checks hidden link destinations and image descriptions; money warnings pause links and copying, in the timeline and in the image viewer. Save original accepts only bytes that decode as a known image type, names the file by that type, and marks it with the system quarantine. Native regression tests use synthetic images and links. End-to-end image sending and real-account preview preferences still need device evidence.

QR codes use one binary byte segment, as the web does, so released phone scanners can read them. Apple's Core Image encoder mixed an alphanumeric MATRIX header with byte segments; the released iPhone scanner ignored that result. The native MIT-licensed Nayuki encoder is vendored unchanged, with source hashes and its notice in macos/QRCode. The regression test decodes the final bitmap with the released phone's byte-only rules. No phone update is needed for this compatibility fix.

## CI evidence

`.github/workflows/macos.yml` builds Debug and Release for both architectures. Hosted XCTest checks native SDK/SQLite loading without a server, Keychain error handling with synthetic records, same-device restoration, the approval gate and delayed refresh after revocation. Unit tests never read real credentials.

The Release job uploads `bipe-mac-spike`, containing `Bipe-Mac.zip` and its SHA-256 checksum. After both Mac configurations and native tests pass on main, the publish job updates the public `mac-preview` prerelease. The downloads page reads that URL from bipe-shared/product/distribution.json. The ZIP contains Bipe.app for macOS 15+, on Apple silicon and Intel. It uses an ad hoc signature; Developer ID signing, notarization, store distribution and a supported updater remain later work. A build or synthetic test does not prove real-account linking or cross-device behavior.

## Real-account acceptance record

Record the build commit, Mac architecture/OS, phone platform/build and result in #1018. The following checks are pending until that evidence exists:

MZ confirmed that phone linking works on 6 November 2026 with build 521 (ff3bede3). No phone update was needed. The remaining cross-device checks below still need evidence.

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
