// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import PhotosUI
import SwiftUI

/// Spec group-details. Groups only (RoomInfo.isDm false, ADR 0141).
struct GroupDetailsView: View {
    @StateObject private var model: GroupDetailsModel
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var leaving = false
    @State private var openMembers = false
    @State private var toast: String?
    private let media: MediaLoader

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: GroupDetailsModel(session: session, roomId: roomId))
        self.media = media
    }

    var body: some View {
        List {
            Section {
                header
            }
            .listRowBackground(Color.clear)

            if model.phase == .viewing {
                viewingSections
            } else {
                editingSections
            }
        }
        .navigationTitle(BipeStrings.appGroupDetailsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.phase != .viewing)
        .toolbar { toolbar }
        .disabled(model.phase == .saving)
        .alert(BipeStrings.appSaveError, isPresented: $model.showsSaveError) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .sheet(isPresented: $leaving) {
            GroupLeaveView(session: model.session, roomId: model.roomId) {
                leaving = false
                openMembers = true
            }
        }
        .navigationDestination(isPresented: $openMembers) {
            GroupMembersView(session: model.session, media: media, roomId: model.roomId)
        }
        .toast($toast)
        .accessibilityIdentifier(BipeScreen.groupDetails.rawValue)
        .task { await model.appear() }
        .onChange(of: pickedPhoto) { _, item in
            Task {
                guard let item, let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }
                model.editPhoto = .new(image)
            }
        }
    }

    private var header: some View {
        VStack(spacing: 12) {
            Group {
                if case .new(let image) = model.editPhoto, model.phase != .viewing {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 96, height: 96)
                        .clipShape(Circle())
                } else {
                    let removed = model.editPhoto == .removed && model.phase != .viewing
                    AvatarView(name: model.name, mxcUrl: removed ? nil : model.avatarUrl, loader: media, size: 96)
                }
            }
            if model.phase == .viewing {
                Text(model.name)
                    .font(.title2.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Text(BipeStrings.appGroupDetailsPeople(model.memberCount.formatted()))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                PhotosPicker(selection: $pickedPhoto, matching: .images) {
                    Text(BipeStrings.appGroupPhotoChoose)
                }
                if model.editPhoto != .removed && (model.avatarUrl != nil || model.editPhoto != .unchanged) {
                    Button(BipeStrings.appGroupPhotoRemove, role: .destructive) {
                        model.editPhoto = .removed
                    }
                }
            }
        }
        .buttonStyle(.borderless)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var viewingSections: some View {
        if let description = model.description {
            Section {
                Text(description)
                    .foregroundStyle(BipeColor.inkPrimary)
            } header: {
                Text(BipeStrings.appGroupDescription)
            }
        } else if model.canEdit {
            Section {
                Button(BipeStrings.appGroupDescriptionPlaceholder) { model.edit() }
            } header: {
                Text(BipeStrings.appGroupDescription)
            }
        }

        Section {
            Button {
                openMembers = true
            } label: {
                row(icon: .usersThree, title: BipeStrings.appGroupDetailsPeople(model.memberCount.formatted()))
            }
            if model.canInvite {
                NavigationLink {
                    GroupInviteMembersView(session: model.session, media: media, mode: .invite(roomId: model.roomId)) { _ in
                        toast = BipeStrings.dmInviteToGroupSent
                    }
                } label: {
                    row(icon: .userPlus, title: BipeStrings.appGroupAddPeople, chevron: false)
                }
            }
            if model.canChangePrivacy {
                // Spec group-privacy (bipe#772).
                NavigationLink {
                    GroupPrivacyView(session: model.session, media: media, roomId: model.roomId)
                } label: {
                    row(icon: .lockSimple, title: BipeStrings.groupPrivacyTitle, chevron: false)
                }
            }
            NavigationLink {
                ChatNotificationsView(session: model.session, roomId: model.roomId)
            } label: {
                HStack {
                    row(icon: .bell, title: BipeStrings.settingsNotifications, chevron: false)
                    Spacer()
                    if let mode = model.notificationMode {
                        Text(mode.title)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
        }

        Section {
            Button(role: .destructive) {
                leaving = true
            } label: {
                Label {
                    Text(BipeStrings.appGroupLeaveAction)
                } icon: {
                    PhosphorIcon.signOut.image.resizable().frame(width: 20, height: 20)
                }
                .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
    }

    @ViewBuilder
    private var editingSections: some View {
        Section {
            TextField(BipeStrings.appGroupName, text: $model.editName)
        } header: {
            Text(BipeStrings.appGroupName)
        }
        Section {
            TextField(BipeStrings.appGroupDescriptionPlaceholder, text: $model.editDescription, axis: .vertical)
                .lineLimit(2...6)
        } header: {
            Text(BipeStrings.appGroupDescription)
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        switch model.phase {
        case .viewing:
            if model.canEdit {
                ToolbarItem(placement: .primaryAction) {
                    Button(BipeStrings.appEdit) { model.edit() }
                }
            }
        case .editing, .saving:
            ToolbarItem(placement: .cancellationAction) {
                Button(BipeStrings.appCancel) { model.cancelEdit() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if model.phase == .saving {
                    ProgressView()
                } else {
                    Button(BipeStrings.appSave) {
                        Task { await model.save() }
                    }
                    .disabled(!model.canSave)
                }
            }
        }
    }

    private func row(icon: PhosphorIcon, title: String, chevron: Bool = true) -> some View {
        HStack(spacing: 12) {
            icon.image
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(BipeColor.glyphPrimary)
                .accessibilityHidden(true)
            Text(title)
                .foregroundStyle(BipeColor.inkPrimary)
            if chevron {
                Spacer()
                PhosphorIcon.caretRight.image
                    .resizable()
                    .frame(width: 14, height: 14)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .accessibilityHidden(true)
            }
        }
    }
}
