// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import PhotosUI
import SwiftUI

/// Spec profile-edit: the name and photo people who chat with the user see (the Matrix profile).
/// The public profile has its own name and photo (public-profile-edit, ADR 0041).
struct ProfileEditView: View {
    enum Phase: Equatable {
        case editing
        case saving
        case saved
        case failed
    }

    let people: PeopleService

    @State private var name = ""
    @State private var originalName = ""
    @State private var avatarUrl: String?
    @State private var newPhoto: Data?
    @State private var removesPhoto = false
    @State private var isCompany = false
    @State private var phase: Phase = .editing
    @State private var pickerItem: PhotosPickerItem?
    @State private var showsPicker = false
    @Environment(\.dismiss) private var dismiss

    /// nameProblem of the spec: empty, tooLong or reserved, else none.
    private var nameProblem: BipeProfileProblem? {
        BipePublicProfileRules.profileProblem(name: name, bio: "")
    }

    private var hasChanges: Bool {
        name.trimmingCharacters(in: .whitespacesAndNewlines) != originalName || newPhoto != nil || removesPhoto
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    photo
                    HStack(spacing: 16) {
                        Button(BipeStrings.publicProfilePhotoChange) { showsPicker = true }
                        if newPhoto != nil || (avatarUrl != nil && !removesPhoto) {
                            Button(BipeStrings.publicProfilePhotoRemove, role: .destructive) {
                                newPhoto = nil
                                removesPhoto = true
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                }
                .frame(maxWidth: .infinity)
            }
            .listRowBackground(Color.clear)
            Section {
                TextField(isCompany ? BipeStrings.companyName : BipeStrings.profileDisplayName, text: $name)
                    .textContentType(.name)
            } header: {
                Text(isCompany ? BipeStrings.companyName : BipeStrings.profileDisplayName)
            } footer: {
                if let nameProblem {
                    Text(nameProblem.localizedMessage)
                        .foregroundStyle(BipeColor.trustDangerInk)
                } else {
                    Text(BipeStrings.profileDisplayNameFooter)
                }
            }
            if phase == .failed {
                Section {
                    Text(BipeStrings.appSaveError)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .tint(BipeColor.accentFill)
        .navigationTitle(BipeStrings.settingsProfileNamePhoto)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(phase == .saving)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if phase == .saving {
                    ProgressView()
                } else {
                    Button(BipeStrings.appSave) { Task { await save() } }
                        .disabled(nameProblem != nil || !hasChanges)
                }
            }
        }
        .photosPicker(isPresented: $showsPicker, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            Task { await load(item) }
        }
        .accessibilityIdentifier(BipeScreen.profileEdit.rawValue)
        .task { await appear() }
    }

    @ViewBuilder
    private var photo: some View {
        if let newPhoto, let image = UIImage(data: newPhoto) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 96, height: 96)
                .clipShape(isCompany ? AnyShape(RoundedRectangle(cornerRadius: 21, style: .continuous)) : AnyShape(Circle()))
        } else {
            AvatarView(name: name, mxcUrl: removesPhoto ? nil : avatarUrl, loader: people.media, size: 96, isSquare: isCompany)
        }
    }

    /// Action appear: Client.cachedAvatarUrl first, to show at once, then the server values.
    private func appear() async {
        let client = people.session.client
        if let cached = try? await client.cachedAvatarUrl() {
            avatarUrl = cached
        }
        if let current = try? await client.displayName() {
            originalName = current
            if name.isEmpty {
                name = current
            }
        }
        avatarUrl = (try? await client.avatarUrl()) ?? avatarUrl
        isCompany = (try? await people.api.accountType(userID: people.session.userId)) == .company
    }

    /// Action choosePhoto: the picked photo, cropped square and re-encoded as JPEG.
    private func load(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
        guard let jpeg = BipeProfilePhoto.jpegData(from: image.centreSquare()) else { return }
        newPhoto = jpeg
        removesPhoto = false
    }

    /// Action save: sends only what changed, then closes.
    private func save() async {
        guard nameProblem == nil else { return }
        phase = .saving
        let client = people.session.client
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            if trimmed != originalName {
                try await client.setDisplayName(name: trimmed)
                originalName = trimmed
            }
            if let newPhoto {
                try await client.uploadAvatar(mimeType: "image/jpeg", data: newPhoto)
            } else if removesPhoto {
                try await client.removeAvatar()
            }
            phase = .saved
            dismiss()
        } catch {
            phase = .failed
        }
    }
}
