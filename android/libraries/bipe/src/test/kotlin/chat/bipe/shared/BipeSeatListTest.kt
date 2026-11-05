// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeSeatList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec company-team (ADR 0251, ADR 0252): the seat list as the server sends it. */
class BipeSeatListTest {
    @Test fun seatListDecodesSnakeCase() {
        val list = Json.decodeFromString(
            BipeSeatList.serializer(),
            """
            {"me":{"seat_id":"s1","name":"Maria","role":"owner"},
             "seats":[{"id":"s1","name":"Maria","role":"owner","status":"active",
                       "devices":[{"device_id":"D1","kind":"phone","added_at":"2026-11-05T12:00:00.000Z"}]},
                      {"id":"s2","name":"Ana","role":"attendant","status":"waiting","devices":[],
                       "window_expires_at":"2026-11-05T12:15:00.000Z"}],
             "unassigned_devices":[{"device_id":"D9","kind":"phone","added_at":"2026-11-05T12:00:00.000Z"}],
             "max_people":5,"backup_rotation_pending":false}
            """,
        )
        assertEquals("s1", list.me.seatId)
        assertEquals(5, list.maxPeople)
        assertEquals(listOf("s1"), list.activeSeats.map { it.id })
        assertEquals("D1", list.seats[0].devices[0].deviceId)
        assertEquals("2026-11-05T12:15:00.000Z", list.seats[1].windowExpiresAt)
        assertEquals("D9", list.unassignedDevices[0].deviceId)
    }
}
