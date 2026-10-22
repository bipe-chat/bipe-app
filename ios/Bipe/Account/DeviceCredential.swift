// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import BipeKit

/// The hidden MAS password of the device account (ADR 0057), read from the keychain.
///
/// The item layout is the one of BipeKit `BipeCredentialStore`: generic password, service
/// `chat.bipe.account.password`, `kSecAttrAccount` = Matrix ID (ADR 0086). This type only reads.
/// It never logs or shows the password.
struct DeviceCredential: CustomStringConvertible {
    let userId: String
    let password: String

    var description: String { "DeviceCredential(\(userId))" }

    /// Own-group records win over legacy records for the same account (ADR 0147).
    static func newest() -> DeviceCredential? {
        BipeCredentialStore().password().map { DeviceCredential(userId: $0.userID, password: $0.secret) }
    }
}
