// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import PhotosUI
import SwiftUI

/// Spec group-create. Shown as a sheet; onCreated opens the new group.
struct GroupCreateView: View {
    @StateObject private var model: GroupCreateModel
    @Environment(\.dismiss) private var dismiss
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var choosingPeople = false
    private let media: MediaLoader
    private let onCreated: (_ roomId: String, _ allInvited: Bool) -> Void

    init(session: AccountSession, media: MediaLoader, people: [Person] = [], onCreated: @escaping (String, Bool) -> Void) {
        _model = StateObject(wrappedValue: GroupCreateModel(session: session, people: people))
        self.media = media
        self.onCreated = onCreated
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 12) {
                        photoView
                        PhotosPicker(selection: $pickedPhoto, matching: .images) {
                            Text(BipeStrings.appGroupPhotoChoose)
                        }
                        if model.photo != nil {
                            Button(BipeStrings.appGroupPhotoRemove, role: .destructive) {
                                model.removePhoto()
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .buttonStyle(.borderless)
                }
                .listRowBackground(Color.clear)

                Section {
                    TextField(BipeStrings.appGroupName, text: Binding(get: { model.name }, set: { model.setName($0) }))
                        .foregroundStyle(BipeColor.inkPrimary)
                } footer: {
                    if model.isQuietDefault {
                        Text(BipeStrings.appGroupCreateQuietFooter)
                    }
                }

                Section {
                    ForEach(model.people) { person in
                        HStack(spacing: 12) {
                            AvatarView(name: person.displayName, mxcUrl: person.avatarUrl, loader: media, size: 36)
                            Text(person.displayName)
                                .foregroundStyle(BipeColor.inkPrimary)
                                .lineLimit(1)
                            Spacer()
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { model.people[$0].userId }.forEach(model.removePerson)
                    }
                    Button {
                        choosingPeople = true
                    } label: {
                        Label {
                            Text(BipeStrings.appGroupAddPeople)
                        } icon: {
                            PhosphorIcon.userPlus.image.resizable().frame(width: 20, height: 20)
                        }
                    }
                } header: {
                    if !model.people.isEmpty {
                        Text(BipeStrings.appGroupCreatePeople(model.people.count.formatted()))
                    }
                }

                if model.phase == .failed {
                    Section {
                        Text(BipeStrings.appGroupCreateError)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .disabled(model.phase == .creating)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appGroupCreateTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.groupsEmptyAction) {
                        Task {
                            if let result = await model.create() {
                                dismiss()
                                onCreated(result.roomId, result.allInvited)
                            }
                        }
                    }
                    .disabled(!model.canCreate)
                }
            }
            .overlay {
                if model.phase == .creating {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(BipeStrings.appGroupCreating)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    .padding(24)
                    .background(RoundedRectangle(cornerRadius: 16).fill(BipeColor.surfaceRaised))
                }
            }
            .navigationDestination(isPresented: $choosingPeople) {
                GroupInviteMembersView(session: model.session, media: media, mode: .pick(model.people)) { picked in
                    model.people = picked
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.groupCreate.rawValue)
        .tint(BipeColor.accentFill)
        .interactiveDismissDisabled(model.phase == .creating)
        .task { await model.appear() }
        .onChange(of: pickedPhoto) { _, item in
            Task {
                guard let item, let data = try? await item.loadTransferable(type: Data.self) else { return }
                model.setPhoto(UIImage(data: data))
            }
        }
    }

    private var photoView: some View {
        ZStack {
            Circle().fill(BipeColor.accentWash)
            if let photo = model.photo {
                Image(uiImage: photo)
                    .resizable()
                    .scaledToFill()
            } else {
                PhosphorIcon.usersThree.image
                    .resizable()
                    .frame(width: 40, height: 40)
                    .foregroundStyle(BipeColor.accentInk)
            }
        }
        .frame(width: 96, height: 96)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}
