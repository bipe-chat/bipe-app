// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Settings by the user's question (ADR 0112). The order, keys and gates come from the generated
// `BipeSettingsGroup` (bipe-shared/registries/settings.json). This file holds the iOS rules around it.

public extension BipeSettingsRow {
    /// Evaluates `gate` for iOS. `flag` answers `flag:<name>` gates. An unknown gate hides the row.
    func isShownOnIOS(flag: (String) -> Bool) -> Bool {
        guard let gate else { return true }
        switch gate {
        case "ios": return true
        case "android": return false
        default:
            // "planned:<issue>": hidden until the issue ships.
            return gate.hasPrefix("flag:") && flag(String(gate.dropFirst("flag:".count)))
        }
    }
}

public extension BipeSettingsSection {
    /// The rows of this section that iOS shows.
    func rowsShownOnIOS(flag: (String) -> Bool) -> [BipeSettingsRow] {
        rows.filter { $0.isShownOnIOS(flag: flag) }
    }
}

public extension BipeSettingsGroup {
    /// The language of the url rows: "pt" or "en". Same rule as the emails: pt-BR unless the phone is in English.
    static var urlLanguage: String {
        BipeAPIClient.emailLanguage
    }
}

// MARK: - Sua conta: ways back

/// The ways back to the account that this device can see (ADR 0112, bipe/docs/security.md).
/// There is no API for it: the app counts what it already reads.
public struct BipeWaysBack: Sendable, Equatable {
    /// A confirmed recovery email (`GET /v1/me/account`).
    public var email: Bool
    /// A linked Apple or Google account (ADR 0096). No iOS screen yet.
    public var linkedAccount: Bool
    /// A recovery key is set up (Matrix recovery state).
    public var recoveryKey: Bool
    /// The recovery token synced to this Apple account (iCloud Keychain).
    public var syncedToken: Bool
    
    public init(email: Bool, linkedAccount: Bool, recoveryKey: Bool, syncedToken: Bool) {
        self.email = email
        self.linkedAccount = linkedAccount
        self.recoveryKey = recoveryKey
        self.syncedToken = syncedToken
    }
    
    public var count: Int {
        [email, linkedAccount, recoveryKey, syncedToken].filter(\.self).count
    }
    
    /// One or none: the only warning in Settings.
    public var needsWarning: Bool {
        count <= 1
    }
    
    /// The subtitle of the "Sua conta" row.
    public var summary: String {
        switch count {
        case 0: BipeStrings.settingsAccountPathsNone
        case 1: BipeStrings.accountPathsWarning
        default: BipeStrings.settingsAccountPaths(String(count))
        }
    }
    
    /// The status card: the count line, then the warning when there is one.
    public var cardTitle: String {
        count == 0 ? BipeStrings.settingsAccountPathsNone : BipeStrings.settingsAccountPaths(String(count))
    }
    
    public var cardWarning: String? {
        switch count {
        case 0: BipeStrings.fallbackReminder
        case 1: BipeStrings.accountPathsWarning
        default: nil
        }
    }
}

// MARK: - Privacidade

public extension BipeWhoCanMessage {
    /// The subtitle of the "Privacidade" row.
    var settingsSummary: String {
        switch self {
        case .everyone: BipeStrings.settingsPrivacySummaryEveryone
        case .verified: BipeStrings.settingsPrivacySummaryVerified
        case .contacts: BipeStrings.settingsPrivacySummaryContacts
        }
    }
}

/// The answer of `GET /v1/me/export`.
public struct BipeDataExport: Sendable {
    public let data: Data
    /// A plain file name, safe for a temporary file.
    public let filename: String
    
    public init(data: Data, filename: String) {
        self.data = data
        self.filename = filename
    }
    
    /// The name from `content-disposition: attachment; filename="bipe-dados-YYYY-MM-DD.json"`.
    /// Anything else, or a name with other characters, gives `bipe-dados-<today>.json`.
    static func filename(contentDisposition: String?, now: Date = .now) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
        if let contentDisposition,
           let range = contentDisposition.range(of: "filename=") {
            let name = contentDisposition[range.upperBound...]
                .split(separator: ";", maxSplits: 1).first
                .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")) } ?? ""
            if !name.isEmpty, !name.hasPrefix("."), name.hasSuffix(".json"),
               name.unicodeScalars.allSatisfy(allowed.contains) {
                return name
            }
        }
        let day = now.formatted(Date.ISO8601FormatStyle(timeZone: .current).year().month().day())
        return "bipe-dados-\(day).json"
    }
}

// MARK: - Perfil de confiança

public extension BipeTrustProfile {
    /// Every step that is live today is done. The card collapses to one row.
    var liveStepsDone: Bool {
        steps.allSatisfy { $0.status != .todo }
    }
}
