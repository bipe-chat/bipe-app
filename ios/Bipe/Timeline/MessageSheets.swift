// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Spec message-actions: the message on top, the quick reactions, then the actions that apply.
struct MessageActionsSheet: View {
    @ObservedObject var model: ChatTimelineModel
    let row: TimelineRow

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let message = row.message {
                Text(message.body ?? message.senderName)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(3)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(message.isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther))
            }
            if model.showsReactions(row) {
                HStack {
                    ForEach(ChatTimelineModel.quickReactions, id: \.self) { key in
                        Button {
                            model.react(rowId: row.id, key: key)
                        } label: {
                            Text(key)
                                .font(.title2)
                                .frame(maxWidth: .infinity)
                        }
                        .accessibilityLabel(key)
                    }
                    Button {
                        model.moreReactions(rowId: row.id)
                    } label: {
                        PhosphorIcon.smiley.image
                            .resizable()
                            .frame(width: 26, height: 26)
                            .foregroundStyle(BipeColor.glyphSecondary)
                            .frame(maxWidth: .infinity)
                    }
                    .accessibilityLabel(BipeStrings.appMessageActionMoreReactions)
                }
            }
            VStack(spacing: 0) {
                // The money pause counts down in the sheet (spec scam-warning); a periodic redraw keeps the number current.
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    ForEach(model.actions(for: row)) { action in
                        let paused = action == .copy && model.pauseSecondsLeft(rowId: row.id, now: context.date) > 0
                        Button {
                            model.choose(action, rowId: row.id)
                        } label: {
                            HStack(spacing: 14) {
                                action.menuIcon.image
                                    .resizable()
                                    .frame(width: 22, height: 22)
                                Text(model.menuTitle(action, rowId: row.id))
                                Spacer()
                            }
                            .foregroundStyle(action == .delete ? BipeColor.trustDangerInk
                                             : paused ? BipeColor.inkDisabled : BipeColor.inkPrimary)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(paused)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier(BipeScreen.messageActions.rawValue)
    }


}

/// Spec message-reactions openPicker: the system emoji keyboard. The first emoji typed is the reaction.
struct EmojiPickerSheet: View {
    let onPick: (String) -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(BipeStrings.appMessageActionMoreReactions)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            EmojiField(onPick: onPick)
                .frame(height: 44)
        }
        .padding(20)
        .presentationDetents([.height(140)])
        .accessibilityIdentifier(BipeScreen.messageReactions.rawValue)
    }
}

/// A text field that opens the emoji keyboard and reports the first character typed.
private struct EmojiField: UIViewRepresentable {
    let onPick: (String) -> Void

    func makeUIView(context: Context) -> EmojiTextField {
        let field = EmojiTextField()
        field.textAlignment = .center
        field.font = .systemFont(ofSize: 32)
        field.tintColor = .clear
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        DispatchQueue.main.async { field.becomeFirstResponder() }
        return field
    }

    func updateUIView(_ uiView: EmojiTextField, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    @MainActor
    final class Coordinator: NSObject {
        let onPick: (String) -> Void

        init(onPick: @escaping (String) -> Void) {
            self.onPick = onPick
        }

        @objc func changed(_ field: UITextField) {
            guard let first = field.text?.first else { return }
            field.text = ""
            BipeHaptics.play(.tick)
            onPick(String(first))
        }
    }
}

final class EmojiTextField: UITextField {
    override var textInputMode: UITextInputMode? {
        UITextInputMode.activeInputModes.first { $0.primaryLanguage == "emoji" } ?? super.textInputMode
    }
}

/// Spec message-reactions openSenders: who reacted with one key, newest first.
struct ReactionSendersSheet: View {
    let reaction: MessageReaction

    var body: some View {
        NavigationStack {
            List(reaction.senders) { sender in
                HStack {
                    Text(reactionKeyText(reaction.key))
                    Text(sender.name)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    Text(sender.reactedAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            .navigationTitle(BipeStrings.appReactionsTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier(BipeScreen.messageReactions.rawValue)
    }
}

/// Spec read-receipts openReaders: the read-by sheet of a group message.
struct ReadersSheet: View {
    let readers: [ReceiptReader]
    let media: MediaLoader

    var body: some View {
        NavigationStack {
            List(readers) { reader in
                HStack(spacing: 12) {
                    AvatarView(name: reader.name, mxcUrl: reader.avatarUrl, loader: media, size: 32, userId: reader.userId)
                    Text(reader.name)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    if let readAt = reader.readAt {
                        Text(readAt.formatted(date: .abbreviated, time: .shortened))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            .navigationTitle(BipeStrings.appReceiptReadByTitle)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier(BipeScreen.readReceipts.rawValue)
    }
}

/// Spec message-report: an optional reason and the block option.
struct MessageReportSheet: View {
    @ObservedObject var model: ChatTimelineModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(BipeStrings.reportMessageReason,
                              text: Binding(get: { model.report?.reason ?? "" }, set: { model.report?.reason = $0 }),
                              axis: .vertical)
                        .lineLimit(3...6)
                } footer: {
                    Text(BipeStrings.reportMessageBody)
                }
                Section {
                    Toggle(BipeStrings.actionBlockPerson,
                           isOn: Binding(get: { model.report?.alsoBlock ?? false }, set: { model.report?.alsoBlock = $0 }))
                }
                if model.report?.error == true {
                    Section {
                        Text(BipeStrings.appReportError)
                            .foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .navigationTitle(BipeStrings.appMessageActionReport)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { model.report = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if model.report?.isSending == true {
                        ProgressView()
                    } else {
                        Button(BipeStrings.appReportSend) { Task { await model.sendReport() } }
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.messageReport.rawValue)
    }
}
