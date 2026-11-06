# bipe-app

Bipe's own messenger client for iOS (`ios/`, SwiftUI) and Android (`android/`, Jetpack Compose), built on [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk). It replaces the Element X forks. The decision is in [ADR 0143](https://github.com/bipe-chat/bipe/blob/main/docs/adr/0143-own-client-on-matrix-rust-sdk.md).

Licence: [AGPL-3.0-only](LICENSE) with an additional permission for app stores ([APP-STORE-PERMISSION.md](APP-STORE-PERMISSION.md)).

The native Mac feasibility target is in [`macos/`](macos/README.md). It starts with phone-approved QR linking, encrypted local storage and text messaging. Full personal/work parity is tracked in [bipe#1018](https://github.com/bipe-chat/bipe/issues/1018); Windows follows the Mac spike evidence.

iPhone Duo research, first layout changes and the device validation matrix: [docs/iphone-duo.md](docs/iphone-duo.md).
