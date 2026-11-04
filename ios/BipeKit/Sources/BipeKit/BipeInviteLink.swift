// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeInviteLink.swift).

import Foundation

/// Invite links: `https://bipe.chat/convite/XXXX-XXXX-XXXX`. The web page there explains how to install;
/// the app opens it as a universal link and pre-fills the sign-up code.
public nonisolated enum BipeInviteLink {
    /// Rule: bipe-shared/registries/invite-codes.json (generated). Cases: test-vectors/invite-codes.json.
    private static let pattern = try? NSRegularExpression(pattern: BipeRegistries.inviteCodePattern)
    
    /// The invite code in a link, normalised, or `nil` when the URL is not an invite link.
    public static func code(from url: URL) -> String? {
        code(from: url, path: "convite")
    }

    /// Group links (ADR 0184): `https://bipe.chat/g/XXXX-XXXX-XXXX`. The same code format; sign-up takes either code.
    public static func groupCode(from url: URL) -> String? {
        code(from: url, path: "g")
    }

    private static func code(from url: URL, path: String) -> String? {
        guard url.scheme == "https", BipeProfileLink.isBipeLinkHost(url) else {
            return nil
        }
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count == 2, components[0] == path else { return nil }
        return normalizedCode(components[1])
    }
    
    /// `XXXX-XXXX-XXXX` from anything a user typed or pasted: a code, an invite link or a whole message. `nil` when it holds no code.
    public static func normalizedCode(_ raw: String) -> String? {
        let text = String(raw.prefix(BipeRegistries.inviteCodeMaxInputLength)).uppercased()
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern?.firstMatch(in: text, range: range) else { return nil }
        let groups = (1...3).compactMap { Range(match.range(at: $0), in: text).map { String(text[$0]) } }
        return groups.count == 3 ? groups.joined(separator: "-") : nil
    }

    /// The code field text while the user types: uppercase, a dash after every 4 characters, at most 12 characters.
    /// A pasted link or message that holds a code becomes that code.
    public static func typedCode(_ raw: String) -> String {
        if let code = normalizedCode(raw) { return code }
        let characters = Array(raw.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(12))
        return stride(from: 0, to: characters.count, by: 4)
            .map { String(characters[$0..<min($0 + 4, characters.count)]) }
            .joined(separator: "-")
    }
}

/// A normalised invite code. Prints redacted, because routes and actions get logged.
public nonisolated struct BipeInviteCode: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let value: String
    
    /// `nil` when `raw` cannot be a code.
    public init?(_ raw: String) {
        guard let value = BipeInviteLink.normalizedCode(raw) else { return nil }
        self.value = value
    }
    
    public var description: String {
        "BipeInviteCode(redacted)"
    }
    
    public var debugDescription: String {
        description
    }
}
