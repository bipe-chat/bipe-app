// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-notifications.
struct ChatNotificationsView: View {
    @StateObject private var model: ChatNotificationsModel

    init(session: AccountSession, roomId: String) {
        _model = StateObject(wrappedValue: ChatNotificationsModel(session: session, roomId: roomId))
    }

    var body: some View {
        List {
            ForEach(model.options) { option in
                Section {
                    Button {
                        Task { await model.choose(option.mode) }
                    } label: {
                        HStack {
                            Text(option.mode.title)
                                .foregroundStyle(BipeColor.inkPrimary)
                            Spacer()
                            if model.current == option.mode {
                                PhosphorIcon.check.image
                                    .resizable()
                                    .frame(width: 18, height: 18)
                                    .foregroundStyle(BipeColor.accentInk)
                            }
                        }
                    }
                    .disabled(model.saving || option.mode == .mentionsLegacy)
                    .accessibilityAddTraits(model.current == option.mode ? .isSelected : [])
                } footer: {
                    if let footer = option.footer {
                        Text(footer)
                    }
                }
            }
        }
        .navigationTitle(BipeStrings.settingsNotifications)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.chatNotifications.rawValue)
        .alert(BipeStrings.appNotifyError, isPresented: $model.showsError) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .task { await model.appear() }
    }
}
