// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// Bipe's existing iOS/web type scale. Native macOS body text defaults to 13pt, rather than Bipe's 17pt.
enum MacTextStyle: Equatable {
    case body, headline, subheadline, callout, footnote, caption, title2, largeTitle
    var size: CGFloat {
        switch self {
        case .body, .headline: 17
        case .subheadline: 15
        case .callout: 16
        case .footnote: 13
        case .caption: 12
        case .title2: 22
        case .largeTitle: 34
        }
    }
    var relative: Font.TextStyle {
        switch self {
        case .body: .body
        case .headline: .headline
        case .subheadline: .subheadline
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption
        case .title2: .title2
        case .largeTitle: .largeTitle
        }
    }
}

private struct MacType: ViewModifier {
    let style: MacTextStyle
    @ScaledMetric private var size: CGFloat
    init(style: MacTextStyle) {
        self.style = style
        _size = ScaledMetric(wrappedValue: style.size, relativeTo: style.relative)
    }
    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: style == .headline ? .semibold : .regular, design: .rounded))
    }
}

extension View {
    func macFont(_ style: MacTextStyle) -> some View { modifier(MacType(style: style)) }
}
