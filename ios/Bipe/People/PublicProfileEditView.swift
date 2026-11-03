// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec public-profile-edit: turns the public profile on or off and edits its name, bio and social handles
/// (ADR 0228). The photo is the profile photo (syncPhoto). Anyone with the link bipe.chat/@handle sees it;
/// it is in no search (ADR 0041).
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
    /// Spec socials: the text of each field by registries/profiles.json socials id, without the fixed @.
    @State private var socials: [String: String] = [:]
    /// MyProfile.socials ids this build does not know. Saved back unchanged, so a newer network is not removed.
    @State private var otherSocials: [String: String] = [:]
    /// Client.avatarUrl: the public page shows a copy of it.
    @State private var avatarUrl: String?
    @State private var phase: Phase = .loading
    @State private var error: String?
    @State private var savedOnce = false

    /// problem of the spec, from profileProblem.
    private var problem: BipeProfileProblem? {
        BipePublicProfileRules.profileProblem(name: name, bio: bio)
    }

    /// The problem of one social field: socialHandleProblem with the user's own @.
    private func socialProblem(_ platform: SocialPlatform) -> BipeProfileProblem? {
        BipeSocialHandles.problem(platform, input: socials[platform.id] ?? "", ownHandle: handle)
    }

    private var hasSocialProblem: Bool {
        BipeRegistries.profileSocials.contains { socialProblem($0) != nil }
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
            socialsSection
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
                .disabled(problem != nil || hasSocialProblem || phase == .saving)
                if enabled, savedOnce {
                    Button(BipeStrings.publicProfileShare, action: openLink)
                }
            }
        }
    }

    /// Spec socials (ADR 0228): one field per network in registry order, a fixed @ before the text.
    /// The field takes a handle, never a URL: default keyboard, no autocorrect, no autocapitalisation.
    private var socialsSection: some View {
        Section {
            ForEach(BipeRegistries.profileSocials, id: \.id) { platform in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(platform.name)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Spacer(minLength: 8)
                        HStack(spacing: 0) {
                            Text("@")
                                .foregroundStyle(BipeColor.inkSecondary)
                            // The row shows the network name: no placeholder, the label is for VoiceOver.
                            TextField("", text: socialBinding(platform.id))
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .accessibilityLabel(platform.name)
                        }
                    }
                    if let problem = socialProblem(platform) {
                        Text(problem.localizedMessage(network: platform.name))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
        } header: {
            Text(BipeStrings.publicProfileSocials)
        } footer: {
            Text(BipeStrings.publicProfileSocialsFooter)
        }
    }

    /// Action setSocial.
    private func socialBinding(_ id: String) -> Binding<String> {
        Binding(get: { socials[id] ?? "" }, set: { socials[id] = $0 })
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
        let stored = profile.socials ?? [:]
        socials = stored.filter { BipeSocialHandles.platform($0.key) != nil }
        otherSocials = stored.filter { BipeSocialHandles.platform($0.key) == nil }
    }

    /// Every non-empty field after socialHandleClean. The whole object goes, so a cleared field is removed.
    private var socialsUpdate: [String: String] {
        var result = otherSocials
        for platform in BipeRegistries.profileSocials {
            let handle = BipeSocialHandles.clean(socials[platform.id] ?? "")
            if !handle.isEmpty {
                result[platform.id] = handle
            }
        }
        return result
    }

    /// Action save: PUT /v1/me/profile, then syncPhoto.
    private func save() async {
        guard problem == nil, !hasSocialProblem else { return }
        phase = .saving
        error = nil
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
        let isOn = enabled && handle != nil
        let socialHandles = socialsUpdate
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.setMyProfile(BipeProfileUpdate(enabled: isOn, name: trimmedName, bio: trimmedBio, socials: socialHandles), accessToken: token)
            }
            apply(await people.syncPublicPhoto(profile))
            savedOnce = true
            phase = .saved
        } catch {
            if case .server(.profileInvalid, let reason, _) = error, let problem = reason.flatMap(BipeProfileProblem.init(rawValue:)) {
                // The server does not name the network: the first field with the same problem here is the one.
                let network = BipeRegistries.profileSocials.first { socialProblem($0) == problem }?.name
                self.error = problem.localizedMessage(network: network)
            } else {
                self.error = error.localizedMessage
            }
            phase = .failed
        }
    }
}
