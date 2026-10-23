// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type ComposerMention of spec composer: one pill. userId nil is the whole group.
struct ComposerMention: Equatable {
    var userId: String?
    /// The text the pill shows: the name at the time of the mention.
    var label: String
}

/// Type MentionSuggestion of spec composer.
struct MentionSuggestion: Identifiable, Equatable {
    var userId: String?
    var name: String
    var handle: String?
    var avatarUrl: String?

    var id: String { userId ?? "@room" }
}

/// Markdown text with pills (spec composer, action send).
enum ComposerMarkdown {
    /// The pills become matrix.to links to the user, then messageEventContentFromMarkdown, then withMentions
    /// (user IDs of the pills, room true for the whole group).
    static func content(text: String, mentions: [ComposerMention]) -> RoomMessageEventContentWithoutRelation {
        var markdown = text
        for mention in mentions {
            guard let userId = mention.userId, let range = markdown.range(of: "@\(mention.label)") else { continue }
            markdown.replaceSubrange(range, with: "[\(escape(mention.label))](https://matrix.to/#/\(userId))")
        }
        let content = messageEventContentFromMarkdown(md: markdown)
        let userIds = mentions.compactMap(\.userId)
        let room = mentions.contains { $0.userId == nil }
        guard !userIds.isEmpty || room else { return content }
        return content.withMentions(mentions: Mentions(userIds: Array(Set(userIds)).sorted(), room: room))
    }

    private static func escape(_ label: String) -> String {
        label.replacingOccurrences(of: "[", with: "\\[").replacingOccurrences(of: "]", with: "\\]")
    }

    /// The pills still present in the text, in order.
    static func mentions(_ mentions: [ComposerMention], in text: String) -> [ComposerMention] {
        mentions.filter { text.contains("@\($0.label)") }
    }

    /// The word at the end of the text when it starts with @, without the @.
    static func mentionQuery(_ text: String) -> String? {
        guard let word = text.split(separator: " ", omittingEmptySubsequences: false).last, word.hasPrefix("@") else { return nil }
        return String(word.dropFirst())
    }

    /// Replaces the @word at the end of the text with the pill label and a space.
    static func insert(_ suggestion: MentionSuggestion, into text: String, everyoneLabel: String) -> (text: String, mention: ComposerMention) {
        let label = suggestion.userId == nil ? everyoneLabel : suggestion.name
        var words = text.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        if !words.isEmpty { words.removeLast() }
        words.append("@\(label) ")
        return (words.joined(separator: " "), ComposerMention(userId: suggestion.userId, label: label))
    }
}

/// The members of a group for @ suggestions (ADR 0141: groups only, no suggestions in a DM).
@MainActor
final class MentionDirectory {
    private var members: [RoomMember] = []
    private var loaded = false
    private(set) var canMentionEveryone = false

    func load(room: Room, ownUserId: String) async {
        guard !loaded else { return }
        loaded = true
        if let iterator = try? await room.members() {
            var all: [RoomMember] = []
            while let chunk = iterator.nextChunk(chunkSize: 200) {
                all += chunk
            }
            members = all.filter { $0.membership == .join && $0.userId != ownUserId && !$0.isIgnored }
        }
        canMentionEveryone = (try? await room.getPowerLevels().canOwnUserTriggerRoomNotification()) ?? false
    }

    /// Members whose name or user ID contains the query, the whole group first when allowed.
    func suggestions(_ query: String, everyoneLabel: String) -> [MentionSuggestion] {
        let needle = query.lowercased()
        var result: [MentionSuggestion] = []
        if canMentionEveryone, needle.isEmpty || everyoneLabel.lowercased().contains(needle) {
            result.append(MentionSuggestion(userId: nil, name: everyoneLabel))
        }
        let matches = members.filter { member in
            needle.isEmpty || (member.displayName?.lowercased().contains(needle) ?? false) || member.userId.lowercased().contains(needle)
        }
        result += matches.prefix(8).map { MentionSuggestion(userId: $0.userId, name: $0.displayName ?? $0.userId, avatarUrl: $0.avatarUrl) }
        return result
    }
}

/// Drafts (spec composer): one per chat, kept by the SDK on this device.
extension Room {
    /// Action appear: the saved newMessage draft, if any.
    func newMessageDraft() async -> String? {
        guard let draft = try? await loadComposerDraft(threadRoot: nil), case .newMessage = draft.draftType else { return nil }
        return draft.plainText.nilIfBlank
    }

    /// Action leave: saves the text when not blank, else clears the draft.
    func keepDraft(_ text: String) async {
        if text.nilIfBlank == nil {
            try? await clearComposerDraft(threadRoot: nil)
        } else {
            try? await saveComposerDraft(draft: ComposerDraft(plainText: text, htmlText: nil, draftType: .newMessage, attachments: []),
                                         threadRoot: nil)
        }
    }
}
