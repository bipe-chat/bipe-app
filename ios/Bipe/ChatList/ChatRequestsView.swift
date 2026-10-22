// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// The requests row at the top of Conversas (spec chat-requests, rowCount). Hidden at 0.
struct ChatRequestsRow: View {
    let count: Int

    var body: some View {
        HStack(spacing: 12) {
            PhosphorIcon.userCircle.image
                .resizable()
                .frame(width: 40, height: 40)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(BipeStrings.requestsTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.requestsRowBody)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            Spacer()
            Text(count.formatted())
                .font(.caption.bold())
                .foregroundStyle(BipeColor.inkOnAccent)
                .padding(.horizontal, 7)
                .padding(.vertical, 2)
                .background(Capsule().fill(BipeColor.accentFill))
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

/// Spec chat-requests: the box of chat requests.
struct ChatRequestsView: View {
    @ObservedObject var model: ChatRequestsModel
    let media: MediaLoader
    let onOpenChat: (String) -> Void
    let onOpenWhoCanMessage: () -> Void

    var body: some View {
        List {
            if model.visibleRequests.isEmpty {
                // Empty box: bipe_app_requests_empty and a link to the who-can-message setting.
                Text(BipeStrings.appRequestsEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .listRowBackground(BipeColor.surfaceBase)
            } else {
                ForEach(model.visibleRequests) { request in
                    // Action openRequest.
                    NavigationLink {
                        ChatRequestView(model: model, request: request, media: media, onOpenChat: onOpenChat)
                    } label: {
                        RequestRow(request: request, media: media, hidePhoto: model.hidePhotos)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                }
            }
            // Action openWhoCanMessage.
            Button(action: onOpenWhoCanMessage) {
                Text(BipeStrings.messagingTitle)
                    .foregroundStyle(BipeColor.accentInk)
            }
            .listRowBackground(BipeColor.surfaceBase)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.requestsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.chatRequests.rawValue)
    }
}

private struct RequestRow: View {
    let request: ChatRequestSummary
    let media: MediaLoader
    let hidePhoto: Bool

    var body: some View {
        HStack(spacing: 12) {
            // hidePhotos: the initials avatar, never the photo, until accepted.
            AvatarView(name: request.name, mxcUrl: hidePhoto ? nil : request.avatarUrl, loader: media)
            VStack(alignment: .leading, spacing: 2) {
                Text(request.name)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                TrustLine(trust: request.trust)
            }
        }
        .padding(.vertical, 4)
    }
}

/// The trust line of a request: verified seal, unverified or external.
struct TrustLine: View {
    let trust: ChatRequestSummary.Trust

    var body: some View {
        switch trust {
        case .verified:
            Text(BipeStrings.badgeVerified)
                .font(.subheadline)
                .foregroundStyle(BipeColor.trustVerifiedInk)
        case .unverified:
            Text(BipeStrings.bannerUnverifiedTitle)
                .font(.subheadline)
                .foregroundStyle(BipeColor.trustCautionInk)
        case .external:
            Text(BipeStrings.bannerExternal)
                .font(.subheadline)
                .foregroundStyle(BipeColor.trustNeutralInk)
        }
    }
}

/// Spec chat-request: who wants to talk, with their trust facts, before the person answers.
struct ChatRequestView: View {
    @ObservedObject var model: ChatRequestsModel
    let request: ChatRequestSummary
    let media: MediaLoader
    let onOpenChat: (String) -> Void
    @State private var isWorking = false
    @State private var confirmBlock = false
    @State private var reported = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                AvatarView(name: request.name, mxcUrl: model.hidePhotos ? nil : request.avatarUrl, loader: media, size: 72)
                Text(request.name)
                    .font(.title2.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                trustBlock
                if model.error {
                    Text(BipeStrings.errorGeneric)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
                if reported {
                    Text(BipeStrings.dmReportedAndLeft)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                }
                VStack(spacing: 10) {
                    // Action accept.
                    Button {
                        Task {
                            isWorking = true
                            if let roomId = await model.accept(request.roomId) {
                                dismiss()
                                onOpenChat(roomId)
                            }
                            isWorking = false
                        }
                    } label: {
                        Text(BipeStrings.appRequestAccept).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    // Action decline.
                    Button {
                        Task {
                            isWorking = true
                            await model.decline(request.roomId)
                            isWorking = false
                            if !model.error { dismiss() }
                        }
                    } label: {
                        Text(BipeStrings.appRequestDecline).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    // Action askDeclineAndBlock.
                    Button(role: .destructive) {
                        confirmBlock = true
                    } label: {
                        Text(BipeStrings.appRequestDeclineBlock).frame(maxWidth: .infinity)
                    }
                    Text(BipeStrings.dmDeclineBlockDescription)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                    // Action report.
                    Button(role: .destructive) {
                        Task {
                            isWorking = true
                            reported = await model.report(request.roomId)
                            isWorking = false
                        }
                    } label: {
                        Text(BipeStrings.dmReport).frame(maxWidth: .infinity)
                    }
                    Text(BipeStrings.dmDeclineReportDescription)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                }
                .disabled(isWorking || reported)
            }
            .padding(24)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.chatRequest.rawValue)
        .alert(BipeStrings.appRequestDeclineBlock, isPresented: $confirmBlock) {
            Button(role: .destructive) {
                Task {
                    isWorking = true
                    await model.declineAndBlock(request)
                    isWorking = false
                    if !model.error { dismiss() }
                }
            } label: {
                Text(BipeStrings.appRequestDeclineBlock)
            }
            Button(role: .cancel) {} label: {
                Text(BipeStrings.appCancel)
            }
        } message: {
            Text(BipeStrings.dmDeclineBlockAlert(request.name))
        }
    }

    @ViewBuilder
    private var trustBlock: some View {
        VStack(spacing: 8) {
            switch request.trust {
            case .verified:
                Text(BipeStrings.badgeVerifiedName(request.name))
                    .foregroundStyle(BipeColor.trustVerifiedInk)
            case .unverified:
                Text(BipeStrings.bannerUnverifiedTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.trustCautionInk)
                Text(BipeStrings.requestUnverifiedBody)
                    .foregroundStyle(BipeColor.inkSecondary)
            case .external:
                Text(BipeStrings.bannerExternal)
                    .foregroundStyle(BipeColor.trustNeutralInk)
            }
        }
        .multilineTextAlignment(.center)
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 12).fill(request.trust == .verified ? BipeColor.trustVerifiedWash : BipeColor.trustCautionWash))
    }
}

/// A group invite row at the top of the groups list (spec chat-list-filters).
struct GroupInviteRow: View {
    @ObservedObject var model: ChatRequestsModel
    let invite: ChatRequestSummary
    let media: MediaLoader
    let onOpenChat: (String) -> Void

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: invite.name, mxcUrl: invite.avatarUrl, loader: media)
            VStack(alignment: .leading, spacing: 6) {
                Text(invite.name)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                if let inviter = invite.inviterName {
                    Text(BipeStrings.groupNotificationRoomInviteBodyWithSender(inviter))
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                HStack {
                    // Action acceptInvite.
                    Button {
                        Task {
                            if let roomId = await model.accept(invite.roomId) { onOpenChat(roomId) }
                        }
                    } label: {
                        Text(BipeStrings.appRequestAccept)
                    }
                    .buttonStyle(.borderedProminent)
                    // Action declineInvite.
                    Button {
                        Task { await model.decline(invite.roomId) }
                    } label: {
                        Text(BipeStrings.appRequestDecline)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
