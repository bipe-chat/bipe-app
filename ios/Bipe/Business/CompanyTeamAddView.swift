// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec company-team-add: name, what the person will read, the code on this phone, then the wait for the person's phone
/// to claim the seat. Both phones are in one place. The Responsável never scans a code from someone else (ADR 0251).
struct CompanyTeamAddView: View {
    @ObservedObject var home: WorkHomeModel
    let settings: SettingsModel
    let accountName: String
    @Environment(\.dismiss) private var dismiss

    private enum Phase { case name, warning, code, waiting, done, expired }

    @State private var phase = Phase.name
    @State private var name = ""
    @State private var seat: BipeSeat?
    @State private var grantDone = false
    @State private var showsCode = false
    @State private var failure: String?

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form { content }
                .navigationTitle(BipeStrings.teamAdd)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        if phase == .name || phase == .warning { Button(BipeStrings.appCancel) { dismiss() } }
                    }
                }
        }
        .tint(BipeColor.accentFill)
        .interactiveDismissDisabled(phase == .code || phase == .waiting)
        .accessibilityIdentifier(BipeScreen.companyTeamAdd.rawValue)
        .fullScreenCover(isPresented: $showsCode, onDismiss: codeClosed) {
            if let seat {
                LinkComputerView(settings: settings, mode: .phone,
                                 person: LinkComputerModel.PersonToAdd(seatId: seat.id, name: seat.name, accountName: accountName),
                                 onGrantDone: { grantDone = true })
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .name:
            Section {
                TextField(BipeStrings.teamAddName, text: $name).textContentType(.name)
            }
            Button(BipeStrings.teamAddContinue) { phase = .warning }
                .disabled(trimmed.isEmpty || trimmed.count > 60)
        case .warning:
            Section {
                Text(BipeStrings.teamAddWarning(trimmed, accountName)).foregroundStyle(BipeColor.inkPrimary)
            } footer: {
                if let failure { Text(failure).foregroundStyle(BipeColor.trustDangerInk) }
            }
            Button(BipeStrings.teamAddContinue) { Task { await create() } }
        case .code, .waiting:
            ProgressView().frame(maxWidth: .infinity)
        case .done:
            Text(BipeStrings.teamAddDone(trimmed)).foregroundStyle(BipeColor.inkPrimary)
            Button(BipeStrings.appClose) { dismiss() }
        case .expired:
            Text(BipeStrings.teamAddExpired).foregroundStyle(BipeColor.inkPrimary)
            Button(BipeStrings.appClose) { dismiss() }
        }
    }

    /// Action open: the waiting seat, then the code.
    private func create() async {
        failure = nil
        do {
            seat = try await home.api.createSeat(name: trimmed)
            phase = .code
            showsCode = true
        } catch BipeAPIError.server(.seatLimit, _, _) {
            failure = home.seats.map { BipeStrings.teamAddLimit(String($0.maxPeople)) } ?? BipeStrings.teamError
        } catch {
            failure = WorkHomeModel.seatMessage(error)
        }
    }

    /// Action cancel before the grant is done drops the seat. After it, action progress waits for the claim.
    private func codeClosed() {
        guard let seat else { return }
        guard grantDone else {
            Task {
                _ = try? await home.api.removeSeat(id: seat.id)
                await home.loadSeats()
                dismiss()
            }
            return
        }
        phase = .waiting
        Task { await waitForClaim(seat) }
    }

    private func waitForClaim(_ seat: BipeSeat) async {
        // The server always sends the end of the window; 15 minutes is its value in registries/accounts.json.
        let end = seat.windowExpiresAt.flatMap { try? Date($0, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) }
            ?? Date.now.addingTimeInterval(900)
        while Date.now < end {
            if let list = try? await home.api.seats() {
                let current = list.seats.first { $0.id == seat.id }
                if current?.status == "active" {
                    await home.loadSeats()
                    phase = .done
                    return
                }
            }
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
        }
        _ = try? await home.api.removeSeat(id: seat.id)
        await home.loadSeats()
        phase = .expired
    }
}
