// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Storage adapter for the unchanged SwiftUI ChatBackgroundView shared with the phone target.
/// Mac preferences stay in this app's local defaults domain.
enum AppTheme {
    static let chatBackgroundKey = "bipe.settings.chatBackground"
    static var defaults: UserDefaults { .standard }
}
