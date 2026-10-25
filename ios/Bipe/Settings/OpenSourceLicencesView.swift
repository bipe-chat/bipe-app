// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec open-source-licences: every open source component of the app with its licence, Bipe first (ADR 0144). The
/// list is Resources/OpenSourceLicences.json, made by tools/oss-licences.mjs from tools/licences.json.
struct OpenSourceLicencesView: View {
    struct Component: Decodable, Hashable {
        let name: String
        let licence: String
        let text: String?
    }

    private struct LicenceFile: Decodable {
        let components: [Component]
        let texts: [String: String]
    }

    private let list: LicenceFile? = {
        guard let url = Bundle.main.url(forResource: "OpenSourceLicences", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(LicenceFile.self, from: data)
    }()

    var body: some View {
        List {
            ForEach(list?.components ?? [], id: \.self) { component in
                // Action select.
                NavigationLink {
                    LicenceTextView(name: component.name, text: component.text ?? list?.texts[component.licence] ?? "")
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: component.name)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(verbatim: component.licence)
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
        }
        .navigationTitle(BipeStrings.helpLicenses)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.openSourceLicences.rawValue)
    }
}

/// The full licence text. Component names and licence texts are not translated.
private struct LicenceTextView: View {
    let name: String
    let text: String

    var body: some View {
        ScrollView {
            Text(verbatim: text)
                .font(.footnote.monospaced())
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(Text(verbatim: name))
        .navigationBarTitleDisplayMode(.inline)
    }
}
