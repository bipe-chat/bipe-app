// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// The app UI face. ADR 0159: SF Rounded. Trial for bipe-chat/bipe#1521 (ADR 0295): Allo, a rounded redraw of Inter
/// (SIL OFL 1.1), bundled as `Fonts/Allo-Text.ttf` (roundness fixed at 30, weight and optical size axes kept,
/// `calt` removed so typed text shows as typed). Change the face only here.
enum BipeType {
    enum Face { case sfRounded, allo }
    static let face: Face = .allo

    /// PostScript name of the bundled Allo file.
    static let alloName = "AlloVariable-Regular"

    /// The iOS text style sizes at the default Dynamic Type size (ADR 0159). `relativeTo:` scales them.
    static func size(_ style: Font.TextStyle) -> CGFloat {
        switch style {
        case .largeTitle: 34
        case .title: 28
        case .title2: 22
        case .title3: 20
        case .headline, .body: 17
        case .callout: 16
        case .subheadline: 15
        case .footnote: 13
        case .caption: 12
        case .caption2: 11
        default: 17
        }
    }

    static func font(_ style: Font.TextStyle) -> Font {
        switch face {
        case .sfRounded:
            return .system(style, design: .rounded)
        case .allo:
            let font = Font.custom(alloName, size: size(style), relativeTo: style)
            return style == .headline ? font.weight(.semibold) : font
        }
    }
}

/// The text styles of the app, in the current face. Use these, not `.body` or `.headline`.
extension Font {
    static var bipeLargeTitle: Font { BipeType.font(.largeTitle) }
    static var bipeTitle: Font { BipeType.font(.title) }
    static var bipeTitle2: Font { BipeType.font(.title2) }
    static var bipeTitle3: Font { BipeType.font(.title3) }
    static var bipeHeadline: Font { BipeType.font(.headline) }
    static var bipeBody: Font { BipeType.font(.body) }
    static var bipeCallout: Font { BipeType.font(.callout) }
    static var bipeSubheadline: Font { BipeType.font(.subheadline) }
    static var bipeFootnote: Font { BipeType.font(.footnote) }
    static var bipeCaption: Font { BipeType.font(.caption) }
    static var bipeCaption2: Font { BipeType.font(.caption2) }
}

/// Rounded type for all app UI text (ADR 0159), with the system text styles, weights and Dynamic Type.
/// Monospaced codes keep `.monospaced`, which wins over the inherited design.
extension View {
    /// Put on the root view of the app and of each extension with UI.
    @ViewBuilder
    func bipeRoundedType() -> some View {
        switch BipeType.face {
        case .sfRounded: fontDesign(.rounded)
        case .allo: font(.bipeBody)
        }
    }
}

enum RoundedType {
    /// Navigation bar titles are UIKit text that `fontDesign` does not reach. Call once before the first view.
    /// Tab bar labels stay as the system draws them (ADR 0149).
    static func applyToBars() {
        let bar = UINavigationBar.appearance()
        bar.titleTextAttributes = [.font: font(.headline, weight: .semibold)]
        bar.largeTitleTextAttributes = [.font: font(.largeTitle, weight: .bold)]
    }

    private static func font(_ style: UIFont.TextStyle, weight: UIFont.Weight) -> UIFont {
        // The default size; UIFontMetrics then scales it for the current Dynamic Type size.
        let base = UIFont.preferredFont(forTextStyle: style, compatibleWith: UITraitCollection(preferredContentSizeCategory: .large))
        if BipeType.face == .allo, let allo = UIFont(name: BipeType.alloName, size: base.pointSize) {
            // The weight axis ('wght') of the variable font. UIFont.Weight .semibold is 0.3, .bold 0.4.
            let wght: CGFloat = weight == .bold ? 700 : weight == .semibold ? 600 : 400
            let tag = 0x7767_6874 // 'wght'
            let descriptor = allo.fontDescriptor.addingAttributes([UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [tag: wght]])
            return UIFontMetrics(forTextStyle: style).scaledFont(for: UIFont(descriptor: descriptor, size: base.pointSize))
        }
        let weighted = base.fontDescriptor.addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: weight]])
        let rounded = weighted.withDesign(.rounded) ?? weighted
        return UIFontMetrics(forTextStyle: style).scaledFont(for: UIFont(descriptor: rounded, size: 0))
    }
}
