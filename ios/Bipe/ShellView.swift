// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// The shell screen (bipe-chat/bipe#549): the app name from bipe-shared strings and
/// the matrix-rust-sdk version that the SDK reports at run time.
/// The SDK writes no logs here and nothing is sent off the phone.
struct ShellView: View {
    private let sdkVersion = sdkGitSha()

    var body: some View {
        VStack(spacing: 12) {
            Text(BipeStrings.appName)
                .font(.largeTitle.bold())
            Text(BipeStrings.appSdkVersion(sdkVersion))
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
