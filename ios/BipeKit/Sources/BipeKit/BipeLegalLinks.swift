// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeLegalLinks.swift).

import Foundation

/// The Termos de uso and the Política de privacidade on the public site (ADR 0113).
/// English devices get the English pages, by the same rule as ``BipeAPIClient/emailLanguage``.
public enum BipeLegalLinks {
    public static func termsURL(site: URL) -> URL {
        site.appending(path: isEnglish ? BipeRegistries.enTermsPath : BipeRegistries.termsPath)
    }
    
    public static func privacyURL(site: URL) -> URL {
        site.appending(path: isEnglish ? BipeRegistries.enPrivacyPath : BipeRegistries.privacyPath)
    }
    
    /// The site home page (onboarding-welcome learnMore).
    public static func homeURL(site: URL) -> URL {
        site.appending(path: isEnglish ? BipeRegistries.enHomePath : BipeRegistries.homePath)
    }

    private static var isEnglish: Bool {
        BipeAPIClient.emailLanguage == "en"
    }
}
