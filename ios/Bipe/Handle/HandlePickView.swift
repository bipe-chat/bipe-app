// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec handle-pick (bipe-shared product/screens/handle-pick.json).
@MainActor
final class HandlePickModel: ObservableObject {
    enum Status: Equatable {
        case empty
        case tooShort
        case tooLong
        case invalidCharacters
        case checking
        case available
        case reserved
        case taken
        case unchanged
        /// The availability check failed (offline): the server decides on save.
        case unknown
    }

    enum Phase: Equatable {
        case editing
        case saving
        case saved
        case failed
    }

    @Published private(set) var currentHandle: String?
    @Published private(set) var nextChangeAt: Date?
    @Published private(set) var input = ""
    @Published private(set) var status: Status = .empty
    @Published private(set) var phase: Phase = .editing
    @Published private(set) var error: String?
    /// Read once: the prompt card shows only when the account has no @ yet.
    @Published private(set) var loaded = false
    /// The text shown after save or remove.
    @Published private(set) var doneMessage: String?
    @Published private(set) var promptDismissed: Bool
    /// Escolha seu @ (bipe-chat/bipe#200): free handles from the display name, while the account has no @.
    @Published private(set) var suggestions: [String] = []

    private let api: BipeAPIClient
    private let session: AccountSession
    private lazy var checker = BipeHandleAvailabilityChecker(apiClient: api)
    private var promptKey: String { "bipe.handlePrompt.dismissed.\(session.userId)" }

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
        promptDismissed = UserDefaults.standard.bool(forKey: "bipe.handlePrompt.dismissed.\(session.userId)")
    }

    var inCooldown: Bool {
        guard let nextChangeAt else { return false }
        return nextChangeAt > Date()
    }

    /// Prompt card above chat-list: trigger chatListWithoutHandle (registries/onboarding.json prompts.handle), in the
    /// prompt slot (bipe-chat/bipe#200).
    var showsPrompt: Bool {
        loaded && currentHandle == nil && !promptDismissed && BipePromptSlot(userID: session.userId).allows(.handle)
    }

    /// The prompt card showed: it takes the prompt slot.
    func promptShown() {
        BipePromptSlot(userID: session.userId).didShow(.handle)
    }

    /// State suggestions of handle-pick: candidates in order, checked one by one, the first free ones kept.
    func loadSuggestions() async {
        guard currentHandle == nil, suggestions.isEmpty,
              let name = try? await session.client.displayName(), !name.isEmpty else { return }
        var free: [String] = []
        for candidate in BipeHandle.suggestions(for: name) {
            guard free.count < BipeRegistries.handleSuggestionsShown, !Task.isCancelled else { break }
            if (try? await api.handleAvailability(candidate))?.available == true {
                free.append(candidate)
            }
        }
        suggestions = free
    }

    func load() async {
        let mine = try? await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeMyHandle in
            try await self.api.myHandle(openID: token)
        }
        if let mine {
            currentHandle = mine.handle
            nextChangeAt = mine.changeableAt
            input = mine.handle ?? ""
            status = mine.handle == nil ? .empty : .unchanged
            loaded = true
        }
    }

    /// Action editHandle.
    func editHandle(_ text: String) {
        input = BipeHandle.sanitize(text).lowercased()
        error = nil
        phase = .editing
        if !input.isEmpty, input == currentHandle {
            checker.cancel()
            status = .unchanged
            return
        }
        checker.check(input) { [weak self] result in
            self?.status = Self.status(result)
        }
    }

    /// Action save: only when the @ is free.
    func save() async {
        guard status == .available else { return }
        phase = .saving
        let handle = input
        do {
            let mine = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeMyHandle in
                try await self.api.setMyHandle(handle, accessToken: token)
            }
            currentHandle = mine.handle
            nextChangeAt = mine.changeableAt
            status = .unchanged
            phase = .saved
            doneMessage = BipeStrings.handleSaved
        } catch {
            fail(error)
        }
    }

    /// Action remove, after the confirm dialog.
    func remove() async {
        guard currentHandle != nil else { return }
        phase = .saving
        do {
            let mine = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeMyHandle in
                try await self.api.removeMyHandle(accessToken: token)
            }
            currentHandle = mine.handle
            nextChangeAt = mine.changeableAt
            input = ""
            status = .empty
            phase = .saved
            doneMessage = BipeStrings.handleRemoveDone
        } catch {
            fail(error)
        }
    }

    /// Action dismissPrompt: hides the card on this phone.
    func dismissPrompt() {
        promptDismissed = true
        UserDefaults.standard.set(true, forKey: promptKey)
    }

    private func fail(_ error: BipeAPIError) {
        phase = .failed
        self.error = switch error {
        case .server(.handleCooldown, _, _): BipeStrings.errorHandleCooldown
        case .server(.handleTaken, _, _): BipeStrings.handleTaken
        case .network: BipeStrings.errorNetwork
        default: BipeStrings.errorGeneric
        }
    }

    private static func status(_ status: BipeHandleStatus) -> Status {
        switch status {
        case .empty: .empty
        case .checking: .checking
        case .available: .available
        case .unknown: .unknown
        case .problem(let problem):
            switch problem {
            case .tooShort: .tooShort
            case .tooLong: .tooLong
            case .invalidCharacters: .invalidCharacters
            case .reserved: .reserved
            case .taken: .taken
            }
        }
    }

    /// The footer under the field.
    var footer: String {
        if inCooldown, let nextChangeAt {
            return BipeStrings.handleCooldownUntil(nextChangeAt.formatted(date: .abbreviated, time: .shortened))
        }
        switch status {
        case .empty, .unchanged, .unknown: return BipeStrings.handleScreenFooter
        case .checking: return BipeStrings.handleChecking
        case .available: return BipeStrings.handleAvailable
        case .tooShort: return BipeHandleProblem.tooShort.localizedMessage
        case .tooLong: return BipeHandleProblem.tooLong.localizedMessage
        case .invalidCharacters: return BipeHandleProblem.invalidCharacters.localizedMessage
        case .reserved: return BipeHandleProblem.reserved.localizedMessage
        case .taken: return BipeHandleProblem.taken.localizedMessage
        }
    }

    var footerIsProblem: Bool {
        [.tooShort, .tooLong, .invalidCharacters, .reserved, .taken].contains(status)
    }
}

/// The card above chat-list for an account without an @ (registries/onboarding.json prompts.handle).
struct HandlePromptCard: View {
    @ObservedObject var model: HandlePickModel
    let open: () -> Void

    var body: some View {
        let prompt = BipeOnboardingPrompt.handle
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeLocalized.string(prompt.titleKey))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            if let bodyKey = prompt.bodyKey {
                Text(BipeLocalized.string(bodyKey))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            HStack {
                Button(action: open) {
                    Text(prompt.primary.title)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
                ForEach(prompt.text, id: \.titleKey) { button in
                    Button { model.dismissPrompt() } label: {
                        Text(button.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.accentWash))
        .onAppear { model.promptShown() }
    }
}

struct HandlePickView: View {
    @ObservedObject var model: HandlePickModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmsRemove = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 4) {
                    Text(verbatim: "@")
                        .foregroundStyle(BipeColor.inkSecondary)
                    TextField(BipeStrings.handlePlaceholder, text: Binding(get: { model.input }, set: model.editHandle))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(model.inCooldown)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                Text(model.footer)
                    .font(.footnote)
                    .foregroundStyle(model.footerIsProblem ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
                if model.input.isEmpty, !model.suggestions.isEmpty {
                    // State suggestions (bipe-chat/bipe#200): a tap fills the field.
                    Text(BipeStrings.handleSuggestions).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    HStack(spacing: 8) {
                        ForEach(model.suggestions, id: \.self) { suggestion in
                            Button { model.editHandle(suggestion) } label: {
                                Text(verbatim: "@\(suggestion)")
                                    .font(.subheadline)
                                    .lineLimit(1)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .foregroundStyle(BipeColor.accentInk)
                                    .background(Capsule().fill(BipeColor.accentWash))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if let error = model.error {
                    Text(error).font(.footnote).foregroundStyle(BipeColor.trustDangerInk)
                }
                if let done = model.doneMessage, model.phase == .saved {
                    Text(done).font(.footnote).foregroundStyle(BipeColor.statusSuccess)
                }
                Button {
                    Task {
                        await model.save()
                        if model.phase == .saved { dismiss() }
                    }
                } label: {
                    Text(BipeStrings.actionContinue)
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .foregroundStyle(model.status == .available ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                        .background(RoundedRectangle(cornerRadius: 14)
                            .fill(model.status == .available ? BipeColor.accentFill : BipeColor.surfaceSunken))
                }
                .disabled(model.status != .available || model.phase == .saving)
                if let current = model.currentHandle {
                    Button { confirmsRemove = true } label: {
                        Text(BipeStrings.handleRemove)
                            .foregroundStyle(BipeColor.trustDangerInk)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                    }
                    .confirmationDialog(BipeStrings.handleRemoveBody(current), isPresented: $confirmsRemove, titleVisibility: .visible) {
                        Button(role: .destructive) { Task { await model.remove() } } label: { Text(BipeStrings.handleRemove) }
                        Button(role: .cancel) { } label: { Text(BipeStrings.appCancel) }
                    }
                }
                Spacer()
            }
            .padding(24)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.signupHandle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(BipeStrings.appClose) }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.handlePick.rawValue)
        .task { await model.loadSuggestions() }
    }
}
