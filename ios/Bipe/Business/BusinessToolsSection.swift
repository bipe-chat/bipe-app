// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec business-tools (bipe-shared product/screens/business-tools.json): on the Trabalho tab of a company account,
/// the business tools, each opening its owner screen.
struct BusinessToolsSection: View {
    let api: BipeBusinessAPI
    /// Action openAgenda: opens agenda-owner.
    let openAgenda: () -> Void
    /// Action openCatalog: opens catalog-owner.
    let openCatalog: () -> Void
    /// Action openOrders: opens order-owner.
    let openOrders: () -> Void
    /// Action openQuotes: opens quote-builder.
    let openQuotes: () -> Void
    /// Action openQueue: opens queue-owner.
    let openQueue: () -> Void

    /// nil before activation (agenda_off) or while unknown.
    @State private var published: Bool?
    @State private var activated = false
    @State private var loaded = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Section {
            Button(action: openAgenda) {
                BusinessToolLabel(icon: .clock, title: BipeStrings.tabAgenda, subtitle: agendaSubtitle)
            }
            Button(action: openCatalog) {
                BusinessToolLabel(icon: .image, title: BipeStrings.catalogTitle, subtitle: nil)
            }
            Button(action: openOrders) {
                BusinessToolLabel(icon: .notepad, title: BipeStrings.ordersTitle, subtitle: nil)
            }
            Button(action: openQuotes) {
                BusinessToolLabel(icon: .file, title: BipeStrings.quotesTitle, subtitle: nil)
            }
            Button(action: openQueue) {
                BusinessToolLabel(icon: .usersThree, title: BipeStrings.queueTitle, subtitle: nil)
            }
        } header: {
            Text(BipeStrings.businessToolsTitle)
        } footer: {
            Text(BipeStrings.businessToolsFooter)
        }
        .accessibilityIdentifier(BipeScreen.businessTools.rawValue)
        .task { await load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await load() } }
        }
    }

    private var agendaSubtitle: String? {
        guard loaded else { return nil }
        guard let published else { return activated ? nil : BipeStrings.agendaActivate }
        return published ? BipeStrings.agendaPublished : BipeStrings.agendaNotPublished
    }

    /// The Agenda's state for the subtitle: bipe_agenda_activate before activation.
    private func load() async {
        do {
            let state = try await api.agendaState()
            activated = true
            published = state.published
            loaded = true
        } catch {
            if (error as? BipeAPIError)?.isAgendaOff == true {
                activated = false
                published = nil
                loaded = true
            }
        }
    }
}

/// One business tool row: its icon, name and state.
private struct BusinessToolLabel: View {
    let icon: PhosphorIcon
    let title: String
    let subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            icon.image
                .resizable()
                .frame(width: 24, height: 24)
                .foregroundStyle(BipeColor.accentInk)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(BipeColor.inkPrimary)
                if let subtitle {
                    Text(subtitle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
        }
    }
}
