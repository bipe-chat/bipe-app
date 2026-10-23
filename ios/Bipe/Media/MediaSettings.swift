// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The setting bipe_chats_show_media (state showMedia of spec timeline-media), as the settings
/// screen stores it (SettingsKeys.showMedia).
extension ShowMedia {
    static var current: ShowMedia {
        UserDefaults.standard.string(forKey: SettingsKeys.showMedia).flatMap(ShowMedia.init) ?? .always
    }

    /// private: media shows in DMs and in groups that need an invite; in a public group it waits for a tap.
    func shows(inPrivateRoom isPrivate: Bool) -> Bool {
        switch self {
        case .always: true
        case .private: isPrivate
        case .never: false
        }
    }
}
