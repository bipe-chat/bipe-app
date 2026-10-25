// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR 0141: a DM never shows its own m.room.name; the person's name, then the localpart, wins. */
class BipeChatRowNameDmTest {
    private val ana = "@ana:example.test"

    // sdkName, peerName, peerId, dmRoomName -> shown name
    private val cases = listOf(
        listOf("Renamed", "Ana", ana, "Renamed") to "Ana",
        listOf(" Renamed ", null, ana, "Renamed") to "ana",
        listOf("Renamed", null, null, "Renamed") to "Unnamed",
        listOf("Ana", null, ana, null) to "Ana",
    )

    @Test fun dmIgnoresItsRoomName() {
        for ((input, shown) in cases) {
            val (sdkName, peerName, peerId, roomName) = input
            assertEquals(shown, BipeChatRowName.resolve("!dm:example.test", sdkName, peerName, peerId, "Unnamed", dmRoomName = roomName))
        }
    }
}
