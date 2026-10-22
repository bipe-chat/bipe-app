// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

public extension AttributedString {
    /// Replaces `placeholder` with a bold link. When `url` is `nil` (Bipe has no help page for it yet),
    /// removes the placeholder and the space before it, so the sentence ends cleanly.
    mutating func bipeReplaceLink(_ placeholder: String, title: String, url: URL?) {
        guard let range = range(of: placeholder) else { return }
        
        if let url {
            var link = AttributedString(title)
            link.link = url
            link.inlinePresentationIntent = .stronglyEmphasized
            replaceSubrange(range, with: link)
            return
        }
        
        var lowerBound = range.lowerBound
        while lowerBound > startIndex, characters[characters.index(before: lowerBound)].isWhitespace {
            lowerBound = characters.index(before: lowerBound)
        }
        var upperBound = range.upperBound
        // "… identity. {link}." would end in ". ." without this.
        if upperBound < endIndex, characters[upperBound] == ".",
           lowerBound > startIndex, characters[characters.index(before: lowerBound)] == "." {
            upperBound = characters.index(after: upperBound)
        }
        removeSubrange(lowerBound..<upperBound)
    }
}
