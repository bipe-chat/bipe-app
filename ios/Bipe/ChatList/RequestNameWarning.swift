// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Trust facts stay observable while the detail is open. Only a verified seal supplies a proof name.
struct RequestTrustContent: View {
    @ObservedObject var model: ChatRequestsModel
    @ObservedObject private var people: PeopleService
    @ObservedObject private var seals: BipeCompanySeals
    let request: ChatRequestSummary
    @State private var answers: [String: Bool] = [:]
    @State private var foregroundRevision = 0

    init(model: ChatRequestsModel, request: ChatRequestSummary) {
        self.model = model
        people = model.people
        seals = model.people.seals
        self.request = request
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { _ in
            content
        }
        .id(foregroundRevision)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            foregroundRevision += 1
        }
    }

    @ViewBuilder
    private var content: some View {
        let trust = model.trust(for: request)
        let verified: Bool = if case .verified = trust { true } else { false }
        VStack(spacing: 12) {
            VStack(spacing: 8) {
                switch trust {
                case .verified(let name):
                    Text(BipeStrings.badgeVerifiedName(name)).foregroundStyle(BipeColor.trustVerifiedInk)
                case .unverified:
                    Text(BipeStrings.bannerUnverifiedTitle).font(.headline).foregroundStyle(BipeColor.trustCautionInk)
                    Text(BipeStrings.requestUnverifiedBody).foregroundStyle(BipeColor.inkSecondary)
                case .external:
                    Text(BipeStrings.bannerExternal).foregroundStyle(BipeColor.trustNeutralInk)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 12).fill(verified ? BipeColor.trustVerifiedWash : BipeColor.trustCautionWash))
            if case .verified(let name) = trust, let saved = people.contactName(for: request.inviterId) {
                let key = BipeContactNameKey.make(accountId: model.session.userId, peerId: request.inviterId, savedName: saved, verifiedName: name)
                let answer = answers[key] ?? (UserDefaults.standard.object(forKey: key) as? Bool)
                if let warning = BipeRelationshipNames.warning(savedName: saved, verifiedName: name, nicknameConfirmed: answer == true) {
                    VStack(spacing: 8) {
                        Text(BipeStrings.warningNameMismatch(name))
                        if warning == "confirm", answer == nil {
                            Text(BipeStrings.warningNicknameConfirmTitle).font(.headline)
                            Text(BipeStrings.warningNicknameConfirmBody(saved, name))
                            Button(BipeStrings.actionNicknameConfirmYes) { save(true, key: key) }
                            Button(BipeStrings.actionNicknameConfirmUnsure) { save(false, key: key) }
                        }
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(warning == "mismatch" ? BipeColor.trustDangerInk : BipeColor.trustCautionInk)
                    .background(RoundedRectangle(cornerRadius: 12).fill(warning == "mismatch" ? BipeColor.trustDangerWash : BipeColor.trustCautionWash))
                }
            }
        }
        .multilineTextAlignment(.center)
    }

    private func save(_ answer: Bool, key: String) {
        // The proof key includes this account, the peer and both normalised names. A new identity asks again.
        UserDefaults.standard.set(answer, forKey: key)
        answers[key] = answer
    }
}
