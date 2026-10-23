// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation

/// Local answer key for ADR0018. No raw name, phone number or account ID is written into the preferences key.
public nonisolated enum BipeContactNameKey {
    public static func make(accountId: String, peerId: String, savedName: String, verifiedName: String, identityKey: String) -> String {
        let fields = ["v1", accountId, peerId, NameNormalizer.normalize(savedName), NameNormalizer.normalize(verifiedName), identityKey]
        let input = fields.map { "\($0.utf8.count):\($0)" }.joined()
        return "bipe_contact_name_" + SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
