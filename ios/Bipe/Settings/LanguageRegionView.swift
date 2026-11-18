// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// The app language choice of spec language-region. iOS keeps each app's language in the system Settings
/// (AppleLanguages in the app's own defaults); the app reads it and opens Settings to change it.
enum AppLanguageChoice: String, CaseIterable {
    case system
    case pt
    case en

    /// The language set for this app in the system Settings, or system when the app follows the phone.
    static var current: AppLanguageChoice {
        guard let id = Bundle.main.bundleIdentifier,
              let languages = UserDefaults.standard.persistentDomain(forName: id)?["AppleLanguages"] as? [String],
              let first = languages.first
        else { return .system }
        return first.hasPrefix("en") ? .en : .pt
    }

    var title: String {
        switch self {
        case .system: BipeStrings.chatsAppearanceSystem
        case .pt: BipeStrings.languagePt
        case .en: BipeStrings.languageEn
        }
    }

    /// The name of the language the screens show now, also when the app follows the phone.
    static var shownTitle: String {
        AppVersion.language == "en" ? BipeStrings.languageEn : BipeStrings.languagePt
    }

    /// Action selectLanguage: iOS restarts the app after a change in Settings.
    static func select(_ choice: AppLanguageChoice) {
        guard choice != current, let url = URL(string: UIApplication.openSettingsURLString) else { return }
        BipeHaptics.play(.tick)
        UIApplication.shared.open(url)
    }
}

/// The account region of spec language-region (ADR 0197). With one region in registries/regions.json every account
/// is in it.
enum AppRegion {
    static var current: String { BipeRegistries.regionIds.first ?? "br" }

    static func title(_ id: String) -> String {
        BipeLocalized.string("bipe_region_\(id)")
    }
}

/// Spec language-region: the app language and the account region.
struct LanguageRegionView: View {
    @State private var language = AppLanguageChoice.current
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        List {
            Section {
                ForEach(AppLanguageChoice.allCases, id: \.self) { choice in
                    Button {
                        AppLanguageChoice.select(choice)
                    } label: {
                        CheckRow(title: choice.title, checked: language == choice)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(language == choice ? .isSelected : [])
                }
            } header: {
                Text(BipeStrings.settingsLanguage)
            } footer: {
                Text([BipeStrings.settingsLanguageFooter, BipeStrings.settingsLanguageIosFooter].joined(separator: " "))
            }
            Section {
                ForEach(BipeRegistries.regionIds, id: \.self) { id in
                    // Action selectRegion does nothing while there is one region: a row, not a button.
                    CheckRow(title: AppRegion.title(id), checked: id == AppRegion.current)
                        .accessibilityAddTraits(id == AppRegion.current ? .isSelected : [])
                }
            } header: {
                Text(BipeStrings.settingsRegion)
            } footer: {
                Text(BipeStrings.settingsRegionFooter)
            }
        }
        .navigationTitle(BipeStrings.settingsLanguageRegion)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { language = AppLanguageChoice.current }
        }
    }
}

private struct CheckRow: View {
    let title: String
    let checked: Bool

    var body: some View {
        HStack {
            Text(title)
                .foregroundStyle(BipeColor.inkPrimary)
            Spacer()
            if checked {
                PhosphorIcon.check.image
                    .resizable()
                    .frame(width: 18, height: 18)
                    .foregroundStyle(BipeColor.accentInk)
                    .accessibilityHidden(true)
            }
        }
        .contentShape(Rectangle())
    }
}
