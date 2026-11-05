// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec company-team-join and company-team-welcome: this phone scans the Responsável's code, claims the seat with an
/// attestation, and says what customers see and what the people on the account share. It stores no hidden password,
/// recovery token or PIN. A failed claim signs the account out of this phone.
struct CompanyTeamJoinView: View {
    let api: BipeAPIClient
    let close: () -> Void
    let onDone: (AccountSession) -> Void

    private enum Phase {
        case scan
        case joining
        case welcome(AccountSession, name: String)
        case failed(String)
    }

    @State private var phase = Phase.scan

    var body: some View {
        Group {
            switch phase {
            case .scan:
                OldPhoneView(joining: true) { session in Task { await claim(session) } }
            case .joining:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .welcome(let session, let name):
                welcome(session, name: name)
            case .failed(let message):
                failed(message)
            }
        }
        .accessibilityIdentifier(BipeScreen.companyTeamJoin.rawValue)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                if case .scan = phase { Button(BipeStrings.appCancel, action: close) }
            }
        }
    }

    /// Action claim: a new challenge, then the claim.
    private func claim(_ session: AccountSession) async {
        phase = .joining
        do {
            guard BipeAppAttest.isSupported, let attestation = try await BipeAppAttest.attestationUnlessServiceDown(api: api) else {
                await refuse(session, BipeStrings.teamJoinAttestFailed)
                return
            }
            let list = try await session.business(api).claimSeat(attestation: attestation)
            CompanyRole.remember(list.me.role, userId: session.userId)
            let name = (try? await session.client.displayName()) ?? ""
            phase = .welcome(session, name: name)
        } catch BipeAPIError.server(.attestationInvalid, _, _) {
            await refuse(session, BipeStrings.teamJoinAttestFailed)
        } catch BipeAPIError.attestationFailed {
            await refuse(session, BipeStrings.teamJoinAttestFailed)
        } catch BipeAPIError.server(.seatWindowClosed, _, _), BipeAPIError.server(.notFound, _, _) {
            await refuse(session, BipeStrings.teamAddExpired)
        } catch {
            await refuse(session, BipeStrings.teamError)
        }
    }

    private func refuse(_ session: AccountSession, _ message: String) async {
        await session.signOut()
        phase = .failed(message)
    }

    private func welcome(_ session: AccountSession, name: String) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.teamWelcomeTitle(name)).font(.title2.bold()).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.teamWelcomeBody(name)).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.teamSharedTitle).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.teamSharedChats).foregroundStyle(BipeColor.inkSecondary)
                Text(BipeStrings.teamSharedRead).foregroundStyle(BipeColor.inkSecondary)
                Text(BipeStrings.teamSharedChanges).foregroundStyle(BipeColor.inkSecondary)
                Button { onDone(session) } label: {
                    Text(BipeStrings.teamWelcomeOk).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeProminent)
            }
            .padding(24)
        }
        .accessibilityIdentifier(BipeScreen.companyTeamWelcome.rawValue)
    }

    private func failed(_ message: String) -> some View {
        VStack(spacing: 16) {
            Text(message).foregroundStyle(BipeColor.inkPrimary).multilineTextAlignment(.center)
            Button(BipeStrings.appClose, action: close).buttonStyle(.bipeBordered)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
