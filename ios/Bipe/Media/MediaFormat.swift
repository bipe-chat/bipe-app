// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

enum MediaFormat {
    /// File size as the system writes it ("2,4 MB").
    static func size(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(clamping: bytes), countStyle: .file)
    }

    /// "0:42", "12:05".
    static func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// Upload percent of an own media message while it sends.
    static func uploadPercent(_ state: EventSendState?) -> Int? {
        guard case .some(.notSentYet(let progress)) = state else { return nil }
        guard let progress, progress.progress.total > 0 else { return 0 }
        return Int(min(100, progress.progress.current * 100 / progress.progress.total))
    }
}
