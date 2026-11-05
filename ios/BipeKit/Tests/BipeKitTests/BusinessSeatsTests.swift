// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Spec company-team (ADR 0251, ADR 0252): the seat list as the server sends it.
struct BusinessSeatsTests {
    @Test
    func seatListDecodesSnakeCase() throws {
        let json = """
        {"me":{"seat_id":"s1","name":"Maria","role":"owner"},
         "seats":[{"id":"s1","name":"Maria","role":"owner","status":"active",
                   "devices":[{"device_id":"D1","kind":"phone","added_at":"2026-11-05T12:00:00.000Z"}]},
                  {"id":"s2","name":"Ana","role":"attendant","status":"waiting","devices":[],
                   "window_expires_at":"2026-11-05T12:15:00.000Z"}],
         "unassigned_devices":[{"device_id":"D9","kind":"phone","added_at":"2026-11-05T12:00:00.000Z"}],
         "max_people":5,"backup_rotation_pending":false}
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let list = try decoder.decode(BipeSeatList.self, from: Data(json.utf8))
        #expect(list.me.seatId == "s1")
        #expect(list.maxPeople == 5)
        #expect(list.activeSeats.map(\.id) == ["s1"])
        #expect(list.seats[0].devices[0].deviceId == "D1")
        #expect(list.seats[1].windowExpiresAt == "2026-11-05T12:15:00.000Z")
        #expect(list.unassignedDevices[0].deviceId == "D9")
    }
}
