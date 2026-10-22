// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// The setting bipe_chats_show_media (state showMedia of spec timeline-media). Stored under the
/// settings row id chats.showMedia, so the settings screen writes the same value.
enum ShowMedia: String, CaseIterable {
    case always
    case privateOnly
    case never

    static var current: ShowMedia {
        UserDefaults.standard.string(forKey: BipeSettingsRow.chatsShowMedia.rawValue).flatMap(ShowMedia.init) ?? .always
    }

    /// privateOnly: media shows in DMs and in groups that need an invite; in a public group it waits for a tap.
    func shows(inPrivateRoom isPrivate: Bool) -> Bool {
        switch self {
        case .always: true
        case .privateOnly: isPrivate
        case .never: false
        }
    }
}
