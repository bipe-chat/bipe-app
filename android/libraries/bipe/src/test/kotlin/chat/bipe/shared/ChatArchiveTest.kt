// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ChatArchiveTest {
    @Test fun incomingRestoresButOwnOrOldActivityDoesNot() {
        val policy = chat.bipe.shared.generated.BipeChatArchive
        assertTrue(policy.isArchived(100L, null))
        assertTrue(policy.isArchived(100L, 100L))
        assertTrue(policy.isArchived(100L, 99L))
        assertFalse(policy.isArchived(100L, 101L))
        assertTrue(policy.isArchived(0L, null))
        assertFalse(policy.isArchived(null, 100L))
    }

    @Test fun persistsOfflineAndIsolatesOwners() {
        val directory = Files.createTempDirectory("bipe-archive-test").toFile()
        try {
            val a = BipeChatArchiveStore(directory, "@a:example.invalid")
            val b = BipeChatArchiveStore(directory, "@b:example.invalid")
            a.save(mapOf("!room:example.invalid" to 100L))
            assertEquals(mapOf("!room:example.invalid" to 100L), BipeChatArchiveStore(directory, "@a:example.invalid").load())
            assertTrue(b.load().isEmpty())
            assertFalse(a.file.name.contains("@a"))
            assertThrows(Exception::class.java) { a.save(mapOf("!invalid" to -1L)) }
            assertEquals(mapOf("!room:example.invalid" to 100L), a.load())
            a.save(emptyMap())
            assertTrue(a.load().isEmpty())
            a.file.writeText("corrupt")
            assertThrows(Exception::class.java) { a.load() }
            assertEquals("corrupt", a.file.readText())
        } finally { directory.deleteRecursively() }
    }
}
