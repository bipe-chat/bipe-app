// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI

/// Native palette menus extract each command's image. Text-only emoji labels produce empty cells on Mac.
@MainActor enum MacReactionArtwork {
    private static var cache: [String: NSImage] = [:]

    static func image(for key: String) -> NSImage {
        if let image = cache[key] { return image }
        let image = NSImage(size: NSSize(width: 28, height: 28), flipped: false) { rect in
            let text = NSAttributedString(string: key, attributes: [.font: NSFont.systemFont(ofSize: 22)])
            let size = text.size()
            text.draw(at: NSPoint(x: (rect.width - size.width) / 2, y: (rect.height - size.height) / 2))
            return true
        }
        image.isTemplate = false
        cache[key] = image
        return image
    }
}

struct MacReaction: Identifiable {
    let key: String
    let includesOwn: Bool
    let senders: [ReactionSenderData]
    var id: String { key }
    var count: Int { senders.count }
    static func summaries(_ reactions: [Reaction], ownUserID: String) -> [Self] {
        reactions.filter { !$0.senders.isEmpty }.map {
            Self(key: $0.key, includesOwn: $0.senders.contains { $0.senderId == ownUserID },
                 senders: $0.senders.sorted { a, b in a.timestamp == b.timestamp ? a.senderId < b.senderId : a.timestamp > b.timestamp })
        }
    }
}

struct MacReactionPills: View {
    let reactions: [MacReaction]
    let pending: (String) -> Bool
    let toggle: (String) -> Void
    let showSenders: (MacReaction) -> Void
    var body: some View {
        FlowRow(spacing: 4) {
            ForEach(reactions) { reaction in
                Button { toggle(reaction.key) } label: {
                    HStack(spacing: 4) {
                        Text(BipeReactions.keyText(reaction.key))
                        Text(reaction.count.formatted()).macFont(.caption)
                            .foregroundStyle(reaction.includesOwn ? BipeColor.accentInk : BipeColor.inkSecondary)
                    }.padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Capsule().fill(reaction.includesOwn ? BipeColor.accentWash : BipeColor.surfaceSunken))
                        .overlay(Capsule().stroke(reaction.includesOwn ? BipeColor.accentFill : BipeColor.lineSubtle, lineWidth: 1))
                }.buttonStyle(.plain).disabled(pending(reaction.key))
                    .accessibilityLabel(BipeStrings.appReactionLabel(reaction.key, reaction.count.formatted()))
                    .accessibilityAddTraits(reaction.includesOwn ? [.isSelected] : [])
                    .contextMenu { Button(BipeStrings.appReactionsTitle) { showSenders(reaction) } }
                    .accessibilityAction(named: BipeStrings.appReactionsTitle) { showSenders(reaction) }
            }
        }
    }
}

struct MacReactionPicker: View {
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        VStack(spacing: 16) {
            Text(BipeStrings.appMessageActionMoreReactions).macFont(.headline)
            MacEmojiField { key in dismiss(); pick(key) }.frame(height: 44)
            Button(BipeStrings.appCancel) { dismiss() }.keyboardShortcut(.cancelAction)
        }.padding(24).frame(width: 360)
    }
}

private struct MacEmojiField: NSViewRepresentable {
    let pick: (String) -> Void
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 32
    func makeNSView(context: Context) -> NSTextField {
        let field = NSTextField()
        field.alignment = .center
        field.font = .systemFont(ofSize: size)
        field.delegate = context.coordinator
        field.setAccessibilityLabel(BipeStrings.appMessageActionMoreReactions)
        DispatchQueue.main.async {
            guard field.window?.makeFirstResponder(field) == true else { return }
            NSApplication.shared.orderFrontCharacterPalette(nil)
        }
        return field
    }
    func updateNSView(_ view: NSTextField, context: Context) { view.font = .systemFont(ofSize: size) }
    func makeCoordinator() -> Coordinator { Coordinator(pick: pick) }
    @MainActor final class Coordinator: NSObject, NSTextFieldDelegate {
        let pick: (String) -> Void
        init(pick: @escaping (String) -> Void) { self.pick = pick }
        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSTextField, let character = field.stringValue.first else { return }
            field.stringValue = ""
            pick(String(character))
        }
    }
}

private struct MacReactionPerson: Identifiable {
    let sender: ReactionSenderData
    let name: String
    let avatarURL: String?
    var id: String { sender.senderId + "|" + String(sender.timestamp) }
}

struct MacReactionSenders: View {
    let reaction: MacReaction
    let room: Room
    @ObservedObject var media: MacMedia
    @Environment(\.dismiss) private var dismiss
    @State private var people: [MacReactionPerson] = []
    @State private var loading = true
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(BipeStrings.appReactionsTitle + " " + BipeReactions.keyText(reaction.key)).macFont(.headline)
                Spacer()
                Button(BipeStrings.appClose) { dismiss() }.keyboardShortcut(.cancelAction)
            }
            if loading { ProgressView().frame(maxWidth: .infinity) }
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(people) { person in
                        HStack(spacing: 12) {
                            MacAvatar(name: person.name, media: media, url: person.avatarURL, userID: person.sender.senderId, size: 32)
                            Text(person.name).macFont(.body)
                            Spacer()
                            Text(Date(timeIntervalSince1970: Double(person.sender.timestamp) / 1000), format: .dateTime)
                                .macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                }
            }
        }.padding(24).frame(width: 560, height: 360).background(BipeColor.surfaceBase)
            .task {
                var result: [MacReactionPerson] = []
                for sender in reaction.senders {
                    async let displayName = try? room.memberDisplayName(userId: sender.senderId)
                    async let avatar = try? room.memberAvatarUrl(userId: sender.senderId)
                    let name = sender.senderId == room.ownUserId() ? BipeStrings.appYou
                        : BipeChatRowName.person(userID: sender.senderId, displayName: await displayName,
                                                handle: BipeHandleCache.handle(for: sender.senderId))
                    result.append(MacReactionPerson(sender: sender, name: name, avatarURL: await avatar))
                    guard !Task.isCancelled else { return }
                }
                people = result; loading = false
            }
    }
}
