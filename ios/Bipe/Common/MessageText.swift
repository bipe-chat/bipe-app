// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

extension TimelineItemContent {
    /// The plain text of a message, for the chat list preview. nil for anything the slice does not show.
    var previewText: String? {
        guard case .msgLike(let content) = self else { return nil }
        switch content.kind {
        case .message(let message):
            return message.body
        case .unableToDecrypt:
            return BipeStrings.appTimelineUndecryptable
        default:
            return nil
        }
    }
}

extension ProfileDetails {
    func displayName(or userId: String) -> String {
        if case .ready(let displayName, _, _, _, _) = self, let displayName, !displayName.isEmpty {
            return displayName
        }
        return userId
    }
}

extension Date {
    /// SDK timestamps are milliseconds since 1970.
    init(milliseconds: UInt64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}
