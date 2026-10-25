// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// The bordered button styles of the app. They always use the large control size,
/// so that a button never shrinks to the height of its text (MZ, 25 October 2026).
/// Use `.bipeBordered` and `.bipeProminent`, never `.bordered` or `.borderedProminent`.
struct BipeBorderedButtonStyle: PrimitiveButtonStyle {
    var prominent = false

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        // The system styles by type name: `.bipeProminent` here is this style again and recursed until the stack
        // overflowed at launch (build 180).
        if prominent {
            Button(configuration).buttonStyle(BorderedProminentButtonStyle()).controlSize(.large)
        } else {
            Button(configuration).buttonStyle(BorderedButtonStyle()).controlSize(.large)
        }
    }
}

extension PrimitiveButtonStyle where Self == BipeBorderedButtonStyle {
    static var bipeBordered: BipeBorderedButtonStyle { BipeBorderedButtonStyle() }
    static var bipeProminent: BipeBorderedButtonStyle { BipeBorderedButtonStyle(prominent: true) }
}
