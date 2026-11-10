// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Flags payment-scam signals in a message. The rules run in bipe-core (`scamMatchIds`, `scamWarningKind`, ADR 0267).
/// Must match bipe-shared/test-vectors/scam-detection.json and scam-warnings.json.
public nonisolated enum ScamDetector {
    /// Match IDs in reference order: `<patternGroup>:<id>` for regexes, then `urgency:<phrase>`, then `receipt:<keyword>`.
    public static func matchIDs(in message: String) -> [String] {
        scamMatchIds(message: message)
    }
}
