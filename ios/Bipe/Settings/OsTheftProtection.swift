// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import LocalAuthentication
import SwiftUI
import UIKit

/// The phone's own theft protection (bipe-chat/bipe#125, ADR 0045 tier 0, spec theft-protection). iOS gives apps no
/// public API that reads Stolen Device Protection, and no public URL to its page. So this code never shows a state
/// and opens the Settings app (security.md rule 14).
enum OsTheftProtection {
    /// Spec theft-protection osPath: the path to Proteção de Dispositivo Roubado on this iPhone.
    static var pathText: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType == .touchID ? BipeStrings.osTheftPathIosTouchId : BipeStrings.osTheftPathIosFaceId
    }

    /// Action openOsSettings, target osTheftSettings: the only public way into the Settings app.
    @MainActor
    static func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// What prompt osTheftProtection remembers. One flag for the phone, not per account: the protection belongs to the phone.
enum OsTheftPromptMemory {
    private static let doneKey = "bipe.osTheftPrompt.done"

    /// True after Conferir proteção or Agora não. The prompt never comes back on this phone.
    static var isDone: Bool { UserDefaults.standard.bool(forKey: doneKey) }

    static func markDone() { UserDefaults.standard.set(true, forKey: doneKey) }

    /// Trigger voceAfterDays: this phone first saw the account `BipeOnboarding.osTheftProtectionAfterDays` ago.
    static func isDue(userID: String, now: Date = Date()) -> Bool {
        let firstSeen = BipePinReminderMemory(userID: userID).firstSeen
        return now.timeIntervalSince(firstSeen) >= TimeInterval(BipeOnboarding.osTheftProtectionAfterDays) * 86_400
    }
}

/// Spec theft-protection: the row Proteção do celular, above the guardians. It shows on every visit.
struct OsTheftProtectionSection: View {
    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(BipeStrings.osTheftRowTitle)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.osTheftRowBody)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                Text(OsTheftProtection.pathText)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            .padding(.vertical, 4)
            Button(BipeStrings.osTheftAction) { OsTheftProtection.openSettings() }
                .foregroundStyle(BipeColor.accentInk)
        }
    }
}
