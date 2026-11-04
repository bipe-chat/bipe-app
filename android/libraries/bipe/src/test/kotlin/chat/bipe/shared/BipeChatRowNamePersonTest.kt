// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** People names (MZ, 2 November 2026): contact, real display name, @handle, placeholder. Never the ID or its localpart. */
class BipeChatRowNamePersonTest {
    private val id = "@s9d89sa89sas0:bipe.net.br"

    // contactName, displayName, handle -> shown name
    private val people = listOf(
        listOf(" Mãe ", "Ana", "ana") to "Mãe",
        listOf(null, "Ana", "ana") to "Ana",
        listOf(null, "s9d89sa89sas0", "ana") to "@ana",
        listOf(null, id, null) to "Sem nome",
        listOf(null, " ", "") to "Sem nome",
    )

    @Test fun personNeverShowsTheMatrixId() {
        for ((input, shown) in people) {
            val (contact, display, handle) = input
            assertEquals(shown, BipeChatRowName.person(id, contact, display, handle, "Sem nome"))
        }
    }

    @Test fun rowFallsBackToTheHandle() {
        assertEquals("@pessoa", BipeChatRowName.resolve("!room:example.test", "person", "person", "@person:example.test", "Unnamed", peerHandle = "pessoa"))
    }
}
