// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Spec accessibility-settings (bipe-chat/bipe#917): the state of the system accessibility settings that Bipe
/// follows. The rows show state only; iOS has no public URL to the Accessibility pane, so the footer names the path.
struct AccessibilitySettingsView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var voiceOver = UIAccessibility.isVoiceOverRunning
    @State private var page: WebPage?

    private var textSizeValue: String {
        if typeSize > .large { return BipeStrings.accessibilityTextLarger }
        if typeSize < .large { return BipeStrings.accessibilityTextSmaller }
        return BipeStrings.accessibilityTextDefault
    }

    var body: some View {
        List {
            Section {
                Text(BipeStrings.accessibilityIntro)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .listRowBackground(BipeColor.surfaceRaised)
            }
            Section {
                stateRow(title: BipeStrings.accessibilityScreenReader, value: onOff(voiceOver))
                stateRow(title: BipeStrings.accessibilityTextSize, value: textSizeValue)
                stateRow(title: BipeStrings.accessibilityReduceMotion, value: onOff(reduceMotion))
                stateRow(title: BipeStrings.accessibilityContrast, value: onOff(contrast == .increased))
            } footer: {
                Text(BipeStrings.accessibilityIosPath)
            }
            Section {
                Button {
                    if let url = BipeSettingsUrl.accessibility.url(language: AppVersion.language) {
                        page = WebPage(url: url)
                    }
                } label: {
                    Text(BipeStrings.accessibilityPage)
                        .foregroundStyle(BipeColor.accentInk)
                }
                .listRowBackground(BipeColor.surfaceRaised)
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.chatsAccessibility)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.accessibilitySettings.rawValue)
        .sheet(item: $page) { page in
            SafariPage(url: page.url).ignoresSafeArea()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in
            voiceOver = UIAccessibility.isVoiceOverRunning
        }
    }

    private func onOff(_ isOn: Bool) -> String {
        isOn ? BipeStrings.accessibilityOn : BipeStrings.accessibilityOff
    }

    /// One info row; VoiceOver reads the name, then the state.
    private func stateRow(title: String, value: String) -> some View {
        SettingsRowLabel(title: title, value: value)
            .accessibilityElement(children: .combine)
            .listRowBackground(BipeColor.surfaceRaised)
    }
}
