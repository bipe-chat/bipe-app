// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeCreatedInvite
import chat.bipe.shared.api.BipeMyInvites
import chat.bipe.shared.invites.BipeInviteMemoryData
import chat.bipe.shared.invites.importOwnedInviteMemory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class InvitesTest {
    @Test
    fun serverFieldsAndUnknownStatusRemainSafe() {
        val response = Json.decodeFromString(BipeMyInvites.serializer(), """
            {"quota":2,"available":0,"next_available_at":"2026-11-01T12:00:00Z","invites":[
              {"id":"synthetic","status":"future-status","created_at":"2026-10-01T12:00:00Z",
               "expires_at":"2026-11-01T12:00:00Z","joined_user_id":null,"name":"Fixture"}
            ]}
        """.trimIndent())
        assertThat(response.available).isEqualTo(0)
        assertThat(response.nextAvailableAt).isEqualTo("2026-11-01T12:00:00Z")
        assertThat(response.invites.single().name).isEqualTo("Fixture")
        assertThat(response.invites.single().isPending).isFalse()
        assertThat(response.invites.single().isUsed).isFalse()
    }

    @Test
    fun descriptionsExcludeInviteCodesLinksAndNotes() {
        val created = BipeCreatedInvite("fixture", "synthetic-code", "https://example.invalid/synthetic", "2026-11-01T12:00:00Z")
        assertThat(created.toString()).isEqualTo("BipeCreatedInvite(fixture)")
        val memory = BipeInviteMemoryData(mapOf(created.id to created.url), mapOf(created.id to "Synthetic first message"))
        assertThat(memory.toString()).isEqualTo("BipeInviteMemoryData")
    }

    @Test
    fun earlierNativeImportIsAccountBoundAndCannotResurrectConsumedNotes() {
        val previous = BipeInviteMemoryData(
            links = mapOf("owned" to "https://example.invalid/old", "other-account" to "https://example.invalid/other"),
            notes = mapOf("owned" to "Earlier synthetic note", "other-account" to "Other synthetic note"),
        )
        val current = BipeInviteMemoryData(links = mapOf("owned" to "https://example.invalid/current"))
        val imported = importOwnedInviteMemory(current, previous, setOf("owned"))
        assertThat(imported.links).containsExactly("owned", "https://example.invalid/current")
        assertThat(imported.notes).containsExactly("owned", "Earlier synthetic note")
        assertThat(imported.importedEarlierNativeStore).isTrue()
        val consumed = imported.copy(notes = emptyMap())
        assertThat(importOwnedInviteMemory(consumed, previous, setOf("owned"))).isEqualTo(consumed)
    }

}
