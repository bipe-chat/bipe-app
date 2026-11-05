// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Spec chat-search notes (bipe-chat/bipe#1143): the text for SearchService.setQuery, where each typed word also
/// matches the start of a longer word, and a message must have all the words. The SDK gives the text to tantivy's
/// query parser. tantivy refuses `"adore"*` for one word, so each word is a range on the body field. The exact word
/// is an optional clause, so it ranks first. MessageSearchQuery.kt is the same on Android.
public enum MessageSearchQuery {
    /// The last character of CJK extension H. It is a letter, so the index tokenizer keeps it in the upper bound,
    /// and it sorts after every character people type.
    private static let upperBoundEnd = "\u{323AF}"
    /// tantivy's default tokenizer drops a token of 40 UTF-8 bytes or more. The upper bound adds 4 bytes to the word.
    private static let longestRangeWord = 35

    /// nil when the text has no letter or digit: the index holds only those, so nothing can match.
    public static func make(_ text: String) -> String? {
        // The same word boundaries as the tokenizer, which keeps Rust's char::is_alphanumeric. Lower case, because
        // the parser reads the upper-case word TO as a keyword.
        let words = text.precomposedStringWithCanonicalMapping.lowercased().unicodeScalars
            .split { !$0.properties.isAlphabetic && $0.properties.numericType == nil }
            .map { String($0) }
        guard !words.isEmpty else { return nil }
        return words.map { word in
            word.utf8.count > longestRangeWord
                ? "+body:\(word)"
                : "+body:[\(word) TO \(word)\(upperBoundEnd)] body:\(word)"
        }.joined(separator: " ")
    }
}
