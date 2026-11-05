// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Whether this phone answers for a work account as an attendant. Kept on the phone, so that a refused token still
/// tells a removed person from an owner who must sign in again (spec company-team).
enum CompanyRole {
    private static func key(_ userId: String) -> String { "bipe.team.attendant.\(userId)" }

    static func isAttendant(_ userId: String) -> Bool {
        UserDefaults.standard.bool(forKey: key(userId))
    }

    static func remember(_ role: String?, userId: String) {
        if role == "attendant" {
            UserDefaults.standard.set(true, forKey: key(userId))
        } else {
            UserDefaults.standard.removeObject(forKey: key(userId))
        }
    }
}
