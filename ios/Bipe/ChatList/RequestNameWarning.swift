// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Trust facts stay observable while the detail is open. Only a verified seal supplies a proof name.
struct RequestTrustContent: View {
    @ObservedObject var model: ChatRequestsModel
    @ObservedObject private var people: PeopleService
    @Bindable private var seals: BipeCompanySeals
    let request: ChatRequestSummary
    @State private var answers: [String: Bool] = [:]
    @State private var foregroundRevision = 0
    @State private var photoMatchName: String?

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
        .task(id: request.id) { photoMatchName = await model.photoMatch(for: request) }
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
            // Security rule 8: a stranger whose name copies a contact's. Caution, with an icon, not colour alone.
            if let contact = model.contactCollision(for: request) {
                CautionLine(text: BipeStrings.warningNotYourContact(contact))
            }
            // ADR 0049 photo match: the avatar is the same as a contact's.
            if let contact = photoMatchName {
                CautionLine(text: BipeStrings.warningPhotoMatch(contact))
            }
            if case .verified(let name) = trust, let badge = seals.badge(for: request.inviterId),
               badge.name == name, let saved = people.contactName(for: request.inviterId) {
                let key = BipeContactNameKey.make(accountId: model.session.userId, peerId: request.inviterId, savedName: saved, verifiedName: name, identityKey: identityKey(badge))
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

    private func identityKey(_ badge: BadgePayload) -> String {
        switch badge.binding {
        case .masterKey(let key): "master:" + key
        case .device(let key): "device:" + key
        }
    }

    private func save(_ answer: Bool, key: String) {
        // The proof key includes this account, the peer and both normalised names. A new identity asks again.
        UserDefaults.standard.set(answer, forKey: key)
        answers[key] = answer
    }
}

/// One caution line of the request trust block (trust-ui.md): an icon and words, never colour alone.
private struct CautionLine: View {
    let text: String

    var body: some View {
        Label {
            Text(text)
        } icon: {
            PhosphorIcon.warningCircle.image.resizable().frame(width: 18, height: 18)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .foregroundStyle(BipeColor.trustCautionInk)
        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustCautionLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}
