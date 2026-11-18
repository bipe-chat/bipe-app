// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

// Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.

/// Type Person of the people specs: the name to show, the photo and the @. Never the Matrix ID (ADR 0013).
struct Person: Identifiable, Equatable, Hashable {
    var userId: String
    /// People names: the saved contact name, else the real profile name, else the @, else bipe_person_no_name.
    var displayName: String
    var avatarUrl: String?
    var handle: String?

    init(userId: String, displayName: String?, avatarUrl: String? = nil, handle: String? = nil) {
        self.userId = userId
        self.displayName = BipeChatRowName.person(userID: userId, displayName: displayName, handle: handle)
        self.avatarUrl = avatarUrl
        self.handle = handle
    }

    var id: String { userId }
}
