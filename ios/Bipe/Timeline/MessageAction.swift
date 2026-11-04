// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

/// Type MessageAction of spec message-actions, in the order of the sheet.
enum MessageAction: CaseIterable, Identifiable {
    case reply
    case replyInThread
    case edit
    case removeCaption
    case copy
    case copyLink
    case pin
    case unpin
    case forward
    /// Spec message-forward startSelection (bipe#860).
    case select
    case saveSticker
    case viewStickerPack
    case keepAudio
    case unkeepAudio
    case retry
    case delete
    case report

    var id: Self { self }
}
