// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Flags payment-scam signals in a message.
/// Must match bipe-shared/test-vectors/scam-detection.json.
public nonisolated enum ScamDetector {
    /// Match IDs in reference order: `<patternGroup>:<id>` for regexes, then `urgency:<phrase>`, then `receipt:<keyword>`.
    public static func matchIDs(in message: String) -> [String] {
        var ids: [String] = []
        let range = NSRange(message.startIndex..., in: message)
        for pattern in compiledPatterns where pattern.expression.firstMatch(in: message, range: range) != nil {
            ids.append(pattern.matchID)
        }
        
        // The reference matches phrases on the normalised message without homoglyph folding.
        let normalized = NameNormalizer.normalize(message, homoglyphs: [:])
        for phrase in BipeRegistries.urgencyPhrases where containsCodeUnits(normalized, phrase) {
            ids.append("urgency:\(phrase)")
        }
        for keyword in BipeRegistries.receiptKeywords where containsCodeUnits(normalized, keyword) {
            ids.append("receipt:\(keyword)")
        }
        return ids
    }
    
    // MARK: - Private
    
    private struct CompiledPattern: Sendable {
        let matchID: String
        let expression: NSRegularExpression
    }
    
    private static let compiledPatterns: [CompiledPattern] = {
        let groups: [(name: String, patterns: [ScamPattern])] = [
            ("pixKeyPatterns", BipeRegistries.pixKeyPatterns),
            ("pixCopyPastePatterns", BipeRegistries.pixCopyPastePatterns),
            ("boletoPatterns", BipeRegistries.boletoPatterns),
            ("linkPatterns", BipeRegistries.linkPatterns)
        ]
        // bipe-shared validates every regex at generation time, so a compile failure here is not expected.
        return groups.flatMap { group in
            group.patterns.compactMap { pattern in
                (try? NSRegularExpression(pattern: pattern.regex)).map {
                    CompiledPattern(matchID: "\(group.name):\(pattern.id)", expression: $0)
                }
            }
        }
    }()
    
    /// JS `String.prototype.includes`: UTF-16 code units, no grapheme or canonical-equivalence matching.
    static func containsCodeUnits(_ text: String, _ substring: String) -> Bool {
        (text as NSString).range(of: substring, options: .literal).location != NSNotFound
    }
}
