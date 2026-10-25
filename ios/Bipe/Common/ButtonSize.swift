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
        if prominent {
            Button(configuration).buttonStyle(.bipeProminent).controlSize(.large)
        } else {
            Button(configuration).buttonStyle(.bipeBordered).controlSize(.large)
        }
    }
}

extension PrimitiveButtonStyle where Self == BipeBorderedButtonStyle {
    static var bipeBordered: BipeBorderedButtonStyle { BipeBorderedButtonStyle() }
    static var bipeProminent: BipeBorderedButtonStyle { BipeBorderedButtonStyle(prominent: true) }
}
