// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import PhotosUI
import SwiftUI
import UIKit

/// Spec profile-edit: Seu perfil on one screen (MZ, 3 Nov 2026). The Matrix name and photo, the @, the public
/// profile (ADR 0041) with its socials (ADR 0228), then Membro desde and the internal ID. One Salvar saves all.
struct ProfileEditView: View {
    enum Phase: Equatable {
        case loading
        case editing
        case saving
        case failed
    }

    let people: PeopleService
    /// Action openHandle without a handle: handle-pick. nil where the screen has no way to it (the photo ask sheet).
    var pickHandle: (() -> Void)?
    /// Action openHandle with a handle: profile-link.
    var openLink: (() -> Void)?

    // Matrix profile.
    @State private var name = ""
    @State private var originalName = ""
    @State private var avatarUrl: String?
    @State private var newPhoto: Data?
    @State private var removesPhoto = false
    @State private var isCompany = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var showsPicker = false
    // Public profile (GET /v1/me/profile).
    @State private var stored: BipeMyProfile?
    @State private var publicEnabled = false
    @State private var publicName = ""
    @State private var bio = ""
    /// Spec socials: the text of each field by registries/profiles.json socials id, without the fixed @.
    @State private var socials: [String: String] = [:]
    /// MyProfile.socials ids this build does not know. Saved back unchanged, so a newer network is not removed.
    @State private var otherSocials: [String: String] = [:]

    @State private var phase: Phase = .loading
    @State private var error: String?
    @State private var copied = false
    @State private var notice: String?
    /// The network name column of the socials section, so every @ field starts at the same x.
    @ScaledMetric(relativeTo: .body) private var socialLabelWidth: CGFloat = 96
    @Environment(\.dismiss) private var dismiss

    private var handle: String? { stored?.handle }

    /// Spec socials: the public page (https://bipe.chat/@handle), only while the saved profile is on, so the link works.
    private var publicLink: URL? {
        guard stored?.enabled == true else { return nil }
        return handle.flatMap(BipeProfileLink.url(handle:))
    }

    /// nameProblem of the spec: empty, tooLong, reserved or lookalikeEmoji, else none.
    private var nameProblem: BipeProfileProblem? {
        BipePublicProfileRules.profileProblem(name: name, bio: "")
            ?? (BipeNameLookalikes.sealLookalike(in: name) != nil ? .nameLookalikeEmoji : nil)
    }

    /// publicProblem of the spec. Checked only while the public profile is on.
    private var publicProblem: BipeProfileProblem? {
        publicEnabled ? BipePublicProfileRules.profileProblem(name: publicName, bio: bio) : nil
    }

    private func socialProblem(_ platform: SocialPlatform) -> BipeProfileProblem? {
        BipeSocialHandles.problem(platform, input: socials[platform.id] ?? "", ownHandle: handle)
    }

    private var hasSocialProblem: Bool {
        publicEnabled && BipeRegistries.profileSocials.contains { socialProblem($0) != nil }
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var matrixChanged: Bool { trimmedName != originalName || newPhoto != nil || removesPhoto }

    private var publicChanged: Bool {
        guard let stored else { return false }
        return publicEnabled != stored.enabled
            || publicName.trimmingCharacters(in: .whitespacesAndNewlines) != stored.name
            || bio.trimmingCharacters(in: .whitespacesAndNewlines) != stored.bio
            || socialsUpdate != (stored.socials ?? [:])
    }

    private var canSave: Bool {
        (matrixChanged || publicChanged) && nameProblem == nil && publicProblem == nil && !hasSocialProblem
    }

    var body: some View {
        Form {
            photoSection
            nameSection
            handleSection
            publicSection
            if publicEnabled, handle != nil {
                publicFields
                socialsSection
            }
            accountSection
            if phase == .failed {
                Section {
                    Text(error ?? BipeStrings.appSaveError)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
        .tint(BipeColor.accentFill)
        .navigationTitle(BipeStrings.settingsProfile)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(phase == .saving)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if phase == .saving {
                    ProgressView()
                } else {
                    Button(BipeStrings.appSave) { Task { await save() } }
                        .disabled(!canSave)
                }
            }
        }
        .photosPicker(isPresented: $showsPicker, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            Task { await load(item) }
        }
        .toast($notice)
        .accessibilityIdentifier(BipeScreen.profileEdit.rawValue)
        .task { await appear() }
        // Back from profile-link or handle-pick: the @ may have changed.
        .onAppear { if phase != .loading { Task { await loadPublic() } } }
        // handle-pick is a sheet, so this screen does not appear again when it closes (bipe-chat/bipe#1328).
        .onReceive(NotificationCenter.default.publisher(for: .bipeHandleChanged)) { _ in Task { await loadPublic() } }
    }

    // MARK: Sections

    private var photoSection: some View {
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
    }

    private var nameSection: some View {
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
    }

    /// Action openHandle: the @ row.
    @ViewBuilder
    private var handleSection: some View {
        Section {
            if let handle {
                Button { openLink?() } label: {
                    LabeledContent(BipeStrings.settingsHandle) {
                        Text(verbatim: "@\(handle)").foregroundStyle(BipeColor.inkSecondary)
                    }
                    .foregroundStyle(BipeColor.inkPrimary)
                }
                .disabled(openLink == nil)
            } else if phase != .loading {
                if let pickHandle {
                    Button(BipeStrings.handlePromptAction, action: pickHandle)
                } else {
                    LabeledContent(BipeStrings.settingsHandle) { EmptyView() }
                }
            }
        }
    }

    private var publicSection: some View {
        Section {
            Toggle(BipeStrings.publicProfileToggle, isOn: $publicEnabled)
                .disabled(handle == nil || stored == nil)
        } header: {
            Text(BipeStrings.settingsPublicProfile)
        } footer: {
            Text(handle == nil && phase != .loading ? BipeStrings.publicProfileNeedsHandle : BipeStrings.publicProfileFooter)
        }
    }

    private var publicFields: some View {
        Group {
            Section {
                TextField(BipeStrings.publicProfileName, text: $publicName)
            } header: {
                Text(BipeStrings.publicProfileName)
            }
            Section {
                TextField(BipeStrings.publicProfileBioPlaceholder, text: $bio, axis: .vertical)
                    .lineLimit(2...6)
            } header: {
                Text(BipeStrings.publicProfileBio)
            } footer: {
                if let publicProblem {
                    Text(publicProblem.localizedMessage)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
    }

    /// Spec socials (ADR 0228): the public page link with copy and share, then one field per network in registry
    /// order. The network names sit in one column, so the fields line up. Each field has a fixed @ before the text
    /// and takes a handle, never a URL: default keyboard, no autocorrect, no autocapitalisation.
    private var socialsSection: some View {
        Section {
            if let publicLink {
                VStack(alignment: .leading, spacing: 4) {
                    Text(BipeStrings.appProfileLink)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Text(publicLink.absoluteString)
                        .font(.body.monospaced())
                        .foregroundStyle(BipeColor.inkPrimary)
                        .textSelection(.enabled)
                }
                // Actions copyLink and shareLink.
                HStack(spacing: 24) {
                    Button {
                        UIPasteboard.general.url = publicLink
                        notice = BipeStrings.appCopied
                    } label: {
                        IconLabel(icon: .copy, text: BipeStrings.appCopy)
                    }
                    ShareLink(item: publicLink) {
                        IconLabel(icon: .shareNetwork, text: BipeStrings.appShare)
                    }
                }
                .buttonStyle(.borderless)
            }
            ForEach(BipeRegistries.profileSocials, id: \.id) { platform in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(platform.name)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .lineLimit(1)
                            .frame(width: socialLabelWidth, alignment: .leading)
                        Text(verbatim: "@")
                            .foregroundStyle(BipeColor.inkSecondary)
                        // The row shows the network name: no placeholder, the label is for VoiceOver.
                        TextField("", text: socialBinding(platform.id))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityLabel(platform.name)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if let problem = socialProblem(platform) {
                        Text(problem.localizedMessage(network: platform.name))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.trustDangerInk)
                            .padding(.leading, socialLabelWidth + 8)
                    }
                }
            }
        } header: {
            Text(BipeStrings.publicProfileSocials)
        } footer: {
            Text(BipeStrings.publicProfileSocialsFooter)
        }
    }

    /// Membro desde and the internal ID (MZ, 3 Nov 2026).
    private var accountSection: some View {
        Section {
            if let since = stored?.memberSince {
                LabeledContent(BipeStrings.profileMemberSince) {
                    Text(since.formatted(date: .abbreviated, time: .omitted))
                }
            }
            Button(action: copyUserId) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(BipeStrings.profileInternalId)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(verbatim: people.session.userId)
                        .font(.subheadline.monospaced())
                        .foregroundStyle(BipeColor.inkSecondary)
                        .textSelection(.enabled)
                }
            }
            .accessibilityHint(BipeStrings.appCopy)
        } footer: {
            Text(copied ? BipeStrings.appCopied : BipeStrings.profileInternalIdFooter)
        }
    }

    @ViewBuilder
    private var photo: some View {
        if let newPhoto, let image = UIImage(data: newPhoto) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 96, height: 96)
                .clipShape(isCompany ? AnyShape(RoundedRectangle(cornerRadius: 24, style: .continuous)) : AnyShape(Circle()))
        } else {
            AvatarView(name: name, mxcUrl: removesPhoto ? nil : avatarUrl, loader: people.media, size: 96, isCompany: isCompany)
        }
    }

    /// Action setSocial.
    private func socialBinding(_ id: String) -> Binding<String> {
        Binding(get: { socials[id] ?? "" }, set: { socials[id] = $0 })
    }

    // MARK: Actions

    /// Action appear: the Matrix profile first (cached avatar at once), then GET /v1/me/profile.
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
        await loadPublic()
        if let stored {
            await apply(people.syncPublicPhoto(stored))
        }
    }

    private func loadPublic() async {
        do throws(BipeAPIError) {
            let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                try await people.api.myProfile(accessToken: token)
            }
            if stored == nil || !publicChanged {
                apply(profile)
                if profile.name.isEmpty {
                    publicName = originalName
                }
            } else {
                stored = profile
            }
            if phase == .loading { phase = .editing }
        } catch {
            if phase == .loading { phase = .editing }
        }
    }

    private func apply(_ profile: BipeMyProfile) {
        stored = profile
        publicEnabled = profile.enabled
        publicName = profile.name
        bio = profile.bio
        let all = profile.socials ?? [:]
        socials = all.filter { BipeSocialHandles.platform($0.key) != nil }
        otherSocials = all.filter { BipeSocialHandles.platform($0.key) == nil }
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

    /// Action choosePhoto: the picked photo, cropped square and re-encoded as JPEG.
    private func load(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
        guard let jpeg = BipeProfilePhoto.jpegData(from: image.centreSquare()) else { return }
        newPhoto = jpeg
        removesPhoto = false
    }

    /// Action copyUserId.
    private func copyUserId() {
        UIPasteboard.general.string = people.session.userId
        copied = true
    }

    /// Action save: the Matrix profile, then the public profile, then syncPhoto. Closes after both.
    private func save() async {
        guard canSave else { return }
        phase = .saving
        error = nil
        let client = people.session.client
        let photoChanged = newPhoto != nil || removesPhoto
        do {
            if trimmedName != originalName {
                try await client.setDisplayName(name: trimmedName)
                originalName = trimmedName
            }
            if let newPhoto {
                try await client.uploadAvatar(mimeType: "image/jpeg", data: newPhoto)
            } else if removesPhoto {
                try await client.removeAvatar()
            }
            newPhoto = nil
            removesPhoto = false
        } catch {
            phase = .failed
            return
        }
        if publicChanged {
            let update = BipeProfileUpdate(enabled: publicEnabled && handle != nil,
                                           name: publicName.trimmingCharacters(in: .whitespacesAndNewlines),
                                           bio: bio.trimmingCharacters(in: .whitespacesAndNewlines),
                                           socials: socialsUpdate)
            do throws(BipeAPIError) {
                let profile = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
                    try await people.api.setMyProfile(update, accessToken: token)
                }
                apply(await people.syncPublicPhoto(profile))
            } catch {
                if case .server(.profileInvalid, let reason, _) = error, let problem = reason.flatMap(BipeProfileProblem.init(rawValue:)) {
                    // The server does not name the network: the first field with the same problem here is the one.
                    let network = BipeRegistries.profileSocials.first { socialProblem($0) == problem }?.name
                    self.error = problem.localizedMessage(network: network)
                } else {
                    self.error = error.localizedMessage
                }
                phase = .failed
                return
            }
        } else if photoChanged {
            // The public profile photo is a copy of this one (ADR 0041, amended 2 Nov 2026).
            await people.syncPublicPhoto()
        }
        dismiss()
    }
}
