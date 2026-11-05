// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec agenda-settings (ADR 0101): per agenda, the secret feed for a calendar app and the busy-time imports.
struct AgendaCalendarsSections: View {
    let api: BipeBusinessAPI
    let agendas: [BipeAgenda]

    var body: some View {
        ForEach(agendas) { agenda in
            AgendaCalendarSection(api: api, agenda: agenda, showsName: agendas.count > 1)
        }
    }
}

private struct AgendaCalendarSection: View {
    let api: BipeBusinessAPI
    let agenda: BipeAgenda
    let showsName: Bool

    @State private var imports: [BipeBusyImport] = []
    @State private var includeFirstName = false
    @State private var address = ""
    @State private var adding = false
    @State private var error: String?
    @State private var shareLink: FeedShare?

    private struct FeedShare: Identifiable {
        let id = UUID()
        let url: String
    }

    var body: some View {
        Section {
            Toggle(BipeStrings.agendaFeedFirstName, isOn: $includeFirstName)
            Button(BipeStrings.agendaFeedGet) {
                Task {
                    if let feed = try? await api.issueAgendaFeed(agendaId: agenda.id, includeFirstName: includeFirstName) {
                        shareLink = FeedShare(url: feed.url)
                    } else {
                        error = BipeStrings.errorGeneric
                    }
                }
            }
            ForEach(imports) { item in
                VStack(alignment: .leading, spacing: 4) {
                    Button(BipeStrings.agendaBusyRemove, role: .destructive) {
                        Task {
                            try? await api.removeBusyImport(agendaId: agenda.id, importId: item.id)
                            await load()
                        }
                    }
                    if item.failing == true {
                        Text(BipeStrings.agendaBusyFailing).font(.footnote).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            TextField(BipeStrings.agendaBusyUrl, text: $address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Button(BipeStrings.agendaBusyAdd) { Task { await add() } }
                .disabled(address.isEmpty || adding)
            if let error {
                Text(error).font(.footnote).foregroundStyle(BipeColor.trustDangerInk)
            }
        } header: {
            Text(showsName ? "\(BipeStrings.agendaCalendarSection) · \(agenda.name)" : BipeStrings.agendaCalendarSection)
        } footer: {
            Text("\(BipeStrings.agendaFeedFooter) \(BipeStrings.agendaBusyFooter)")
        }
        .task { await load() }
        .sheet(item: $shareLink) { share in
            ShareSheet(items: [share.url])
        }
    }

    private func load() async {
        imports = (try? await api.busyImports(agendaId: agenda.id)) ?? imports
    }

    private func add() async {
        adding = true
        error = nil
        defer { adding = false }
        do {
            _ = try await api.addBusyImport(agendaId: agenda.id, url: address.trimmingCharacters(in: .whitespacesAndNewlines))
            address = ""
            await load()
        } catch {
            self.error = BipeStrings.agendaBusyInvalid
        }
    }
}
