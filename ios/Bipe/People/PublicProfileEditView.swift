// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec public-profile-edit: turns the public profile on or off and edits its name and bio. The photo is the
/// profile photo (syncPhoto). Anyone with the link bipe.chat/@handle sees it; it is in no search (ADR 0041).
struct PublicProfileEditView: View {
    enum Phase: Equatable {
        case loading
        case editing
        case saving
        case saved
        case failed
    }

    let people: PeopleService
    /// Opens handle-pick (no handle yet).
    let pickHandle: () -> Void
    /// Opens profile-link (action share).
    let openLink: () -> Void

    @State private var enabled = false
    @State private var isCompany = false
    @State private var handle: String?
    @State private var name = ""
    @State private var bio = ""
    /// Client.avatarUrl: the public page shows a copy of it.
    @State private var avatarUrl: String?
    @State private var phase: Phase = .loading
    @State private var error: String?
    @State private var savedOnce = false

    /// problem of the spec, from profileProblem.
    private var problem: BipeProfileProblem? {
        BipePublicProfileRules.profileProblem(name: name, bio: bio)
    }

    var body: some View {
        Group {
            if phase == .loading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                form
            }
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .tint(BipeColor.accentFill)
        .navigationTitle(BipeStrings.settingsPublicProfile)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.publicProfileEdit.rawValue)
        .task { await appear() }
    }

    private var form: some View {
        Form {
            Section {
                Toggle(BipeStrings.publicProfileToggle, isOn: $enabled)
                    .disabled(handle == nil)
            } footer: {
                if handle == nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(BipeStrings.publicProfileNeedsHandle)
                        Button(BipeStrings.handlePromptAction, action: pickHandle)
                    }
                } else {
                    Text(BipeStrings.publicProfileFooter)
                }
            }
            Section {
                AvatarView(name: name, mxcUrl: avatarUrl, loader: people.media, size: 96, userId: people.session.userId, isCompany: isCompany)
                    .frame(maxWidth: .infinity)
            }
            .listRowBackground(Color.clear)
            Section {
                TextField(BipeStrings.publicProfileName, text: $name)
            } header: {
                Text(BipeStrings.publicProfileName)
            }
            Section {
                TextField(BipeStrings.publicProfileBioPlaceholder, text: $bio, axis: .vertical)
                    .lineLimit(2...6)
            } header: {
                Text(BipeStrings.publicProfileBio)
            } footer: {
                if let problem {
                    Text(problem.localizedMessage)
                        .foregroundStyle(BipeColor.trustDangerInk)
                } else if let error {
                    Text(error)
                        .foregroundStyle(BipeColor.trustDangerInk)
                } else if phase == .saved {
                    Text(BipeStrings.publicProfileSaved)
                        .foregroundStyle(BipeColor.statusSuccess)
                }
            }
            Section {
                Button {
                    Task { await save() }
                } label: {
                    HStack {
                        Text(BipeStrings.publicProfileSave)
                        if phase == .saving {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(problem != nil || phase == .saving)
                if enabled, savedOnce {
                    Button(BipeStrings.publicProfileShare, action: openLink)
                }
            }
        }
    }

    /// Action appear: GET /v1/me/profile, then syncPhoto. A profile never saved starts from the Matrix name.
    private func appear() async {
        isCompany = await people.media.isCompany(userId: people.session.userId)
        avatarUrl = try? await people.session.client.avatarUrl()
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.myProfile(accessToken: token)
            }
            apply(profile)
            savedOnce = profile.enabled
            if profile.name.isEmpty {
                name = (try? await people.session.client.displayName()) ?? ""
            }
            phase = .editing
            await people.syncPublicPhoto(profile)
        } catch {
            self.error = error.localizedMessage
            phase = .failed
        }
    }

    private func apply(_ profile: BipeMyProfile) {
        enabled = profile.enabled
        handle = profile.handle
        name = profile.name
        bio = profile.bio
    }

    /// Action save: PUT /v1/me/profile, then syncPhoto.
    private func save() async {
        guard problem == nil else { return }
        phase = .saving
        error = nil
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
        let isOn = enabled && handle != nil
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.setMyProfile(BipeProfileUpdate(enabled: isOn, name: trimmedName, bio: trimmedBio), accessToken: token)
            }
            apply(await people.syncPublicPhoto(profile))
            savedOnce = true
            phase = .saved
        } catch {
            if case .server(.profileInvalid, let reason, _) = error, let problem = reason.flatMap(BipeProfileProblem.init(rawValue:)) {
                self.error = problem.localizedMessage
            } else {
                self.error = error.localizedMessage
            }
            phase = .failed
        }
    }
}
