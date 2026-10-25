// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// The GET, PUT and DELETE /v1/me/news answers of the contract (bipe-shared api/openapi.yaml).
struct NewsStateTests {
    private func decode(_ json: String) throws -> BipeNewsState {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(BipeNewsState.self, from: Data(json.utf8))
    }

    @Test
    func subscribedWithRoom() throws {
        #expect(try decode(#"{"subscribed":true,"room_id":"!news:example.test"}"#) == BipeNewsState(subscribed: true, roomId: "!news:example.test"))
    }

    @Test
    func offHasNoRoom() throws {
        #expect(try decode(#"{"subscribed":false}"#) == BipeNewsState(subscribed: false, roomId: nil))
    }
}
