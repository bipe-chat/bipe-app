// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import PhotosUI
import SwiftUI

/// Spec public-profile-edit: turns the public profile on or off and edits its name, bio and photo.
/// Anyone with the link bipe.chat/@handle sees it; it is in no search (ADR 0041).
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
    @State private var handle: String?
    @State private var name = ""
    @State private var bio = ""
    @State private var photoUrl: URL?
    @State private var newPhoto: Data?
    @State private var phase: Phase = .loading
    @State private var error: String?
    @State private var savedOnce = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var showsPicker = false

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
        .photosPicker(isPresented: $showsPicker, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            Task { await load(item) }
        }
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
                VStack(spacing: 12) {
                    photo
                    HStack(spacing: 16) {
                        Button(BipeStrings.publicProfilePhotoChange) { showsPicker = true }
                        if newPhoto != nil || photoUrl != nil {
                            Button(BipeStrings.publicProfilePhotoRemove, role: .destructive) {
                                Task { await removePhoto() }
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
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
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private var photo: some View {
        if let newPhoto, let image = UIImage(data: newPhoto) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 96, height: 96)
                .clipShape(Circle())
        } else if let photoUrl {
            AsyncImage(url: photoUrl) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Circle().fill(BipeColor.accentWash)
            }
            .frame(width: 96, height: 96)
            .clipShape(Circle())
        } else {
            AvatarView(name: name, mxcUrl: nil, loader: people.media, size: 96)
        }
    }

    /// Action appear: GET /v1/me/profile. A profile never saved starts from the Matrix profile.
    private func appear() async {
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.myProfile(accessToken: token)
            }
            apply(profile)
            savedOnce = profile.enabled
            if profile.name.isEmpty {
                name = (try? await people.session.client.displayName()) ?? ""
                if !profile.hasPhoto, let mxc = try? await people.session.client.avatarUrl(),
                   let source = try? MediaSource.fromUrl(url: mxc),
                   let data = try? await people.session.client.getMediaContent(mediaSource: source),
                   let image = UIImage(data: data) {
                    newPhoto = BipeProfilePhoto.jpegData(from: image.centreSquare())
                }
            }
            phase = .editing
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
        photoUrl = profile.photoUrl
    }

    /// Action choosePhoto.
    private func load(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
        guard let jpeg = BipeProfilePhoto.jpegData(from: image.centreSquare()) else {
            error = BipeStrings.publicProfilePhotoInvalid
            return
        }
        error = nil
        newPhoto = jpeg
    }

    /// Action removePhoto: DELETE /v1/me/profile/photo.
    private func removePhoto() async {
        if newPhoto != nil {
            newPhoto = nil
            if photoUrl == nil { return }
        }
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.deleteMyProfilePhoto(accessToken: token)
            }
            photoUrl = profile.photoUrl
        } catch {
            self.error = error.localizedMessage
        }
    }

    /// Action save: PUT /v1/me/profile, then the new photo.
    private func save() async {
        guard problem == nil else { return }
        phase = .saving
        error = nil
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
        let isOn = enabled && handle != nil
        do throws(BipeAPIError) {
            var profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.setMyProfile(BipeProfileUpdate(enabled: isOn, name: trimmedName, bio: trimmedBio), accessToken: token)
            }
            if let jpeg = newPhoto {
                profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                    try await people.api.setMyProfilePhoto(jpeg: jpeg, accessToken: token)
                }
                newPhoto = nil
            }
            apply(profile)
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
