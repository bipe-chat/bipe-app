# iPhone Duo: research and first adjustments

Researched 23 October 2026. Scope: Bipe Next in `bipe-chat/bipe-app`, the native client. This is a first adaptation pass, not a claim of device certification.

## Verified platform guidance

- Build with Xcode 27.1 and the iOS 27.1 SDK to use the full display and native vertical bars. Older SDK builds run with compatibility layouts. The minimum supported iOS version can stay lower. [1]
- The outer display uses compact horizontal size classes. The inner display uses regular horizontal and vertical size classes. Choose layouts from available space and traits. Do not infer them from an iPhone/iPad check, device model, or orientation. [1]
- Keep foreground controls inside the safe area. Backgrounds can extend beyond it. Insets on opposite edges can differ; Split View can put the controls on either side. Avoid `UIScreen.main` and cached screen dimensions. [1]
- Keep state and functionality when moving between displays. Keep the native `TabView`, `NavigationStack`, toolbar and presentation containers. The system moves their controls to the side where appropriate. [2]
- Give toolbar actions both an image and a title. The title is needed in overflow and expanded presentations. Keyboard accessory controls stay with the keyboard. [3]
- A partial fold creates an active division region. The inner camera creates an occlusion region. Reserved-region APIs and `ArrangementView` are options for layouts that must respond to these regions. A width cap alone does not avoid the fold. [4]
- Apple accepts Duo submissions now. Duo screenshots become required for submissions in April 2027. [5]

No hardware pixel dimensions are used as layout breakpoints.

## Findings in Bipe and changes in this pass

| Finding on 23 October 2026 | Adjustment |
| --- | --- |
| The app declares portrait only and `UIRequiresFullScreen`. | Allow all four interface orientations; remove the full-screen requirement. Xcode 27.1 warns if any orientation is omitted without this requirement. |
| Recent iOS CI builds select Xcode 26.6. | Select Xcode 27.1 explicitly in the iOS gate, BipeKit tests and tester archive. Keep the iOS 18 deployment target. |
| The timeline and composer fill all available width. | Center a conversation column capped at 760 points. It shrinks with the local safe-area proposal. The width is a Bipe reading-width default, not a Duo device dimension. |
| Image, video and sticker boxes have fixed widths. | Treat 260 points and 160 points as maximum widths. Scale the box to the local proposal and preserve its aspect ratio and existing portrait-height limit. |
| The composer allows five lines even in a short landscape window. | Allow up to three lines in compact vertical size classes. Let a drag on the timeline dismiss the keyboard interactively. |
| The PIN pad has an unscrollable stack and fixed 72-point keys. | Make the content scroll when needed. Size keys from the available width, between 44 and 72 points. Cap the form width at 400 points. |
| Chat info buttons supply an image and an accessibility label. | Supply a `Label` with a title and image, displayed as an icon, so the system also has a title for toolbar presentations. |

## Second pass, 24 October 2026

Lessons carried over from the EST game's Duo work: size decisions read the window and the size class, never `UIScreen.main`; a wide window puts the list and the content side by side; toolbar items carry a title so the system can place them in the Duo side bar.

| Finding on 24 October 2026 | Adjustment |
| --- | --- |
| The Conversas and Grupos lists push the chat onto a `NavigationStack`. On the inner display the list or one chat takes the whole window. | One `NavigationSplitView` per list, in every size class. A regular window shows the list and the chat side by side; a compact window collapses to the same push navigation as before. One `ChatDetail` selection (room, requests or a people screen) drives both layouts, so opening or closing the device keeps the chat, its timeline and the detail path. |
| Rows open screens by appending to one path. | A row, a notification, a profile link, group create and invites replace the detail root. Screens inside a chat (partner profile, who can message) still push onto the detail path. `closeChat` clears the selection. The selected row is marked with `accentWash` while the detail is beside it. |
| The account avatar and the plus menu had only an image. | Both use a `Label` with a title, shown icon-only, so the side bar and overflow have a title. |
| The sign-up steps fill the whole inner display: the filled button ran edge to edge. | Center the sign-up steps in a column capped at 560 points. The background still fills the window. |
| The welcome screen was a thin column of text on white; on the open Duo it floated in empty space. Four actions in three styles competed below it. | A full-bleed açaí cover (accent.fill to accent.fillPressed) with the mark-and-wordmark lockup, the welcome line, the title, a faint texture of Bipe marks and the Learn more link. Below it, two choices of equal width: the filled sign-up button and a tonal "I already have an account" button. Paste moved to the invite-code step. A tall window gives the cover all the height above the buttons. A wide window makes the cover the left half of the whole window, counted with the safe-area insets, so the split meets the fold. The points list moved to the store listing and the site. `BipeMark` (iOS) and `BipeMarkShape` (Android) draw the app-icon shape for any brand use. |
| The search tab keeps its own `NavigationStack`. | No change in this pass. It stays one column on the open Duo. |

Default chosen: the empty detail column shows only a chat icon, because a sentence would need a new bipe-shared string and the list beside it is the call to action. MZ can overrule.

These changes keep the existing view and model identity during resizing. They add no display-mode branch that replaces the active chat. Runtime continuity still needs validation.

The changes concern iOS platform layout and packaging. Android keeps its current native layout. They do not change the shared screen actions or account rules.

## Next adaptation work

1. Done on 24 October 2026 for Conversas and Grupos (see the second pass). Search and Você still use one column.
2. Test the partially folded poses. Use division and occlusion regions where content would cross a fold or camera. Keep the composer attached to the keyboard and its conversation region.
3. Audit custom toolbar content: the account avatar, chat title and menus. Check their vertical representation, overflow titles and the Você tab's double-tap and long-press gestures.
4. Check sign-up, recovery, QR codes, upload previews, media viewing and recording on short windows and with accessibility text sizes. The PIN change does not establish coverage of the other flows.
5. Capture the Duo App Store screenshots after device validation. Do not mark the app as optimized based only on a passing build.

## Device validation matrix

Run the changed build in Xcode 27.1 Device Hub or on a Duo. All rows below are **pending runtime validation**. Widths here describe test cases, not hardware specifications.

| Configuration | Check |
| --- | --- |
| Outer display, portrait and landscape | Tabs, navigation, PIN keys and composer stay reachable. Toolbar actions have clear overflow labels. |
| Inner display, both orientations | Conversation stays readable; its background fills the display; no controls collide with system UI. |
| Open and close with a draft, reply or edit active | The room, draft, reply/edit target and keyboard session survive. |
| Open and close while reading older messages | The visible message stays in context. Pagination does not jump to the newest message. |
| Split View, left and right positions | Each safe-area edge is handled independently. No fixed-width media escapes the bubble. |
| Partially folded, book and tabletop poses | Check content at the fold. Record where a reserved-region layout is still needed. |
| Narrow windows around 280–360 points | PIN actions, image/video/sticker bubbles, reactions and long filenames remain usable. |
| Keyboard shown, long draft and mention suggestions | The input and send/record actions remain reachable; enough timeline remains visible. |
| Largest accessibility sizes, light/dark, VoiceOver | PIN screen scrolls; content and labels remain legible and reachable. |
| Ordinary iPhone, iOS 18 and current iOS | Portrait behavior remains usable and landscape controls work. |

## Validation boundary

Repository instructions prohibit local compilation. The gate is GitHub CI. A build with the new SDK proves compilation; it does not prove pose transitions or visual correctness. Simulator/device screenshots and the matrix above remain separate evidence.

Local non-build checks passed: 37 tool tests; dependency, licence, header, string and SDK-version gates; workflow YAML parsing; XcodeGen project generation; and inspection of the generated orientation and full-screen settings. The licence gate retains its existing warning for the Android SDK's unverified licence entry.

On this Mac, the installed Xcode reports version 27.1 and includes `iPhoneOS27.1.sdk`. GitHub's `xcode-27` runner manifest lists `/Applications/Xcode_27.1.app` and the iOS 27.1 SDK. [6] The workflow selects this version explicitly instead of relying on the runner's default Xcode 27.0.

## Sources

All checked 23 October 2026. Platform claims above come from Apple. The runner manifest is infrastructure evidence only.

1. Apple, [Prepare your app for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111461/?time=339).
2. Apple, [Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo).
3. Apple, [Raise the bar with iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111462/?time=120).
4. Apple, [Strike a pose with adaptive layouts on iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111463/?time=0).
5. Apple, [Prepare and submit your apps for iPhone Duo](https://developer.apple.com/news/?id=kkphp5qo), 5 October 2026.
6. GitHub Actions, [Xcode 27 runner software manifest](https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md). Read with `gh api`.
