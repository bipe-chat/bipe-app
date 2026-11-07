// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import MatrixRustSDK

protocol MacDraftRoom {
    func id() -> String
    func loadComposerDraft(threadRoot: String?) async throws -> ComposerDraft?
    func saveComposerDraft(draft: ComposerDraft, threadRoot: String?) async throws
    func clearComposerDraft(threadRoot: String?) async throws
}

extension Room: MacDraftRoom {}

/// One queue per account. Reopening a room waits for its last write, including writes from a dismissed view.
@MainActor
final class MacDrafts {
    private var writes: [String: Task<Void, Error>] = [:]

    func load(room: any MacDraftRoom) async throws -> ComposerDraft? {
        // Wait for the order only. A failed write was reported once and must not keep the room closed.
        _ = await writes[room.id()]?.result
        return try await room.loadComposerDraft(threadRoot: nil)
    }

    func save(room: any MacDraftRoom, text: String, reply: String?, onFailure: @escaping @MainActor () -> Void) {
        let previous = writes[room.id()]
        writes[room.id()] = Task {
            // A failed earlier write must not prevent a later edit from being saved.
            _ = await previous?.result
            do {
                if text.isEmpty && reply == nil {
                    try await room.clearComposerDraft(threadRoot: nil)
                } else {
                    try await room.saveComposerDraft(draft: ComposerDraft(plainText: text, htmlText: nil,
                        draftType: reply.map { .reply(eventId: $0) } ?? .newMessage, attachments: []), threadRoot: nil)
                }
            } catch { onFailure(); throw error }
        }
    }
}
