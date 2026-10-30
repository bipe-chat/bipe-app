// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Pack links, stickerPackField and pack rooms (spec sticker-picker, sticker-pack, bipe-chat/bipe#842).
/// StickerPackShareTest.kt checks the same cases.
struct StickerPackShareTests {
    private let ref = StickerPackRef(roomId: "!abc:bipe.net.br", stateKey: "")

    @Test func links() {
        #expect(ref.link == "https://bipe.chat/s/%21abc%3Abipe.net.br")
        #expect(StickerPackRef.fromLink(ref.link) == ref)
        #expect(StickerPackRef.fromLink("https://www.bipe.chat/s/!abc:bipe.net.br") == ref)
        #expect(StickerPackRef.fromLink("https://bipe.chat/@ana") == nil)
        #expect(StickerPackRef.fromLink("https://example.com/s/%21abc%3Abipe.net.br") == nil)
        #expect(StickerPackRef.fromLink("https://bipe.chat/s/abc") == nil)
    }

    @Test func packField() {
        let event = #"{"type":"m.sticker","content":{"body":"a","chat.bipe.pack":{"room_id":"!abc:bipe.net.br","state_key":"k"}}}"#
        #expect(StickerPackRef.read(eventJson: event) == StickerPackRef(roomId: "!abc:bipe.net.br", stateKey: "k"))
        #expect(StickerPackRef.read(eventJson: #"{"content":{"chat.bipe.pack":{"room_id":"abc","state_key":""}}}"#) == nil)
        #expect(StickerPackRef.read(eventJson: #"{"content":{"body":"a"}}"#) == nil)

        let fromRoom = Sticker(packId: "!abc:bipe.net.br/k", shortcode: "s", url: "mxc://x/1", body: "a", width: nil, height: nil, mimeType: nil, size: nil)
        #expect(StickerPackRef.read(eventJson: fromRoom.sendContentJSON()) == StickerPackRef(roomId: "!abc:bipe.net.br", stateKey: "k"))
        let own = Sticker(packId: "user", shortcode: "s", url: "mxc://x/1", body: "a", width: nil, height: nil, mimeType: nil, size: nil)
        #expect(own.packRef == nil && own.sendContentJSON() == own.eventContentJSON())
    }

    @Test func enablingKeepsOtherRooms() {
        let content: [String: Any] = ["rooms": ["!old:x": ["": [String: Any]()]], "other": 1]
        let enabled = StickerPackRoom.enabling(ref, in: content)
        let data = try! JSONSerialization.data(withJSONObject: enabled)
        let rooms = StickerPack.parseEnabledRooms(data).map { "\($0.roomId)/\($0.stateKey)" }
        #expect(rooms == ["!abc:bipe.net.br/", "!old:x/"])
        #expect(enabled["other"] as? Int == 1)
    }

    @Test func packRoomAndPowerLevels() {
        let body = StickerPackRoom.createBody(userPack: ["images": ["s": ["url": "mxc://x/1"]]], displayName: "Ana")
        #expect(body["preset"] as? String == "public_chat" && body["name"] as? String == "Ana")
        let state = (body["initial_state"] as? [[String: Any]]) ?? []
        #expect(state.contains { ($0["content"] as? [String: Any])?["history_visibility"] as? String == "world_readable" })
        #expect(!state.contains { $0["type"] as? String == "m.room.encryption" })

        let json = Data(#"[{"type":"m.room.create","state_key":"","sender":"@ana:x","content":{}},{"type":"m.room.power_levels","state_key":"","content":{"users":{"@bia:x":100},"state_default":50}},{"type":"im.ponies.room_emotes","state_key":"","content":{"pack":{"display_name":"P"},"images":{"s":{"url":"mxc://x/1"}}}}]"#.utf8)
        #expect(StickerPackPreview.canWrite(state: json, roomId: "!r:x", userId: "@ana:x"))
        #expect(StickerPackPreview.canWrite(state: json, roomId: "!r:x", userId: "@bia:x"))
        #expect(!StickerPackPreview.canWrite(state: json, roomId: "!r:x", userId: "@caio:x"))
        let preview = StickerPackPreview.parse(state: json, ref: StickerPackRef(roomId: "!r:x", stateKey: ""))
        #expect(preview?.creatorId == "@ana:x" && preview?.pack.displayName == "P" && preview?.pack.stickers.count == 1)
    }
}
