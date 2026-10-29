// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Feeds the requests to join a room to an AsyncStream, like Common/Listeners.swift.
final class KnockRequestsStream: KnockRequestsListener {
    let updates: AsyncStream<[KnockRequest]>
    private let continuation: AsyncStream<[KnockRequest]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func call(joinRequests: [KnockRequest]) {
        continuation.yield(joinRequests)
    }
}

/// Spec group-privacy (bipe#772): who can join, older messages for new members, requests to join.
@MainActor
final class GroupPrivacyModel: ObservableObject {
    enum Join: String, CaseIterable {
        case invite
        case knock

        var title: String {
            switch self {
            case .invite: BipeStrings.groupPrivacyJoinInvite
            case .knock: BipeStrings.groupPrivacyJoinKnock
            }
        }

        var rule: JoinRule { self == .invite ? .invite : .knock }
    }

    enum History: String, CaseIterable {
        case joined
        case shared

        var title: String {
            switch self {
            case .joined: BipeStrings.groupPrivacyHistoryJoined
            case .shared: BipeStrings.groupPrivacyHistoryShared
            }
        }

        var visibility: RoomHistoryVisibility { self == .joined ? .joined : .shared }
    }

    @Published private(set) var join: Join?
    @Published private(set) var history: History?
    @Published private(set) var requests: [KnockRequest] = []
    @Published private(set) var canChangeJoin = false
    @Published private(set) var canChangeHistory = false
    @Published private(set) var canInvite = false
    @Published var failed = false

    private let session: AccountSession
    private let roomId: String
    private var handle: TaskHandle?

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    private var room: Room? { try? session.client.getRoom(roomId: roomId) }

    /// Reads the settings and the rights, then listens to the requests to join.
    func appear() async {
        guard let room else { return }
        await refresh(room)
        guard handle == nil else { return }
        let stream = KnockRequestsStream()
        handle = try? await room.subscribeToKnockRequests(listener: stream)
        for await requests in stream.updates {
            self.requests = requests
        }
    }

    private func refresh(_ room: Room) async {
        if let info = try? await room.roomInfo() {
            switch info.joinRule {
            case .invite?: join = .invite
            case .knock?: join = .knock
            default: join = nil
            }
            switch info.historyVisibility {
            case .joined: history = .joined
            case .shared: history = .shared
            default: history = nil
            }
        }
        if let levels = try? await room.getPowerLevels() {
            canChangeJoin = levels.canOwnUserSendState(stateEvent: .roomJoinRules)
            canChangeHistory = levels.canOwnUserSendState(stateEvent: .roomHistoryVisibility)
            canInvite = levels.canOwnUserInvite()
        }
    }

    /// Action setJoin.
    func setJoin(_ value: Join) async {
        guard let room, value != join else { return }
        do {
            try await room.updateJoinRules(newRule: value.rule)
            join = value
        } catch {
            failed = true
        }
    }

    /// Action setHistory.
    func setHistory(_ value: History) async {
        guard let room, value != history else { return }
        do {
            try await room.updateHistoryVisibility(visibility: value.visibility)
            history = value
        } catch {
            failed = true
        }
    }

    /// Actions accept and decline.
    func answer(_ request: KnockRequest, accept: Bool) async {
        do {
            if accept {
                try await request.actions.accept()
            } else {
                try await request.actions.decline(reason: nil)
            }
        } catch {
            failed = true
        }
    }
}

/// Spec group-privacy.
struct GroupPrivacyView: View {
    @StateObject private var model: GroupPrivacyModel
    let media: MediaLoader

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: GroupPrivacyModel(session: session, roomId: roomId))
        self.media = media
    }

    var body: some View {
        List {
            Section {
                ForEach(GroupPrivacyModel.Join.allCases, id: \.self) { value in
                    choiceRow(value.title, selected: model.join == value) { Task { await model.setJoin(value) } }
                        .disabled(!model.canChangeJoin)
                }
            } header: {
                Text(BipeStrings.groupPrivacyJoin)
            }
            Section {
                ForEach(GroupPrivacyModel.History.allCases, id: \.self) { value in
                    choiceRow(value.title, selected: model.history == value) { Task { await model.setHistory(value) } }
                        .disabled(!model.canChangeHistory)
                }
            } header: {
                Text(BipeStrings.groupPrivacyHistory)
            } footer: {
                Text(BipeStrings.groupPrivacyHistoryFooter)
            }
            if !model.requests.isEmpty {
                Section {
                    ForEach(model.requests, id: \.eventId) { request in
                        requestRow(request)
                    }
                } header: {
                    Text(BipeStrings.groupRequests)
                }
            }
        }
        .navigationTitle(BipeStrings.groupPrivacyTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.groupPrivacy.rawValue)
        .alert(BipeStrings.errorGeneric, isPresented: $model.failed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .task { await model.appear() }
    }

    private func choiceRow(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                // The chosen value: a check mark, not only a colour.
                if selected {
                    PhosphorIcon.check.image
                        .resizable()
                        .frame(width: 18, height: 18)
                        .foregroundStyle(BipeColor.accentInk)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func requestRow(_ request: KnockRequest) -> some View {
        let name = request.displayName?.nilIfBlank ?? request.userId
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                AvatarView(name: name, mxcUrl: request.avatarUrl, loader: media, size: 40, userId: request.userId)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).foregroundStyle(BipeColor.inkPrimary)
                    if let reason = request.reason?.nilIfBlank {
                        Text(reason)
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            if model.canInvite {
                HStack(spacing: 12) {
                    Button(BipeStrings.groupRequestAccept) { Task { await model.answer(request, accept: true) } }
                        .buttonStyle(.bipeProminent)
                    Button(BipeStrings.groupRequestDecline) { Task { await model.answer(request, accept: false) } }
                        .buttonStyle(.bipeBordered)
                }
            }
        }
    }
}
