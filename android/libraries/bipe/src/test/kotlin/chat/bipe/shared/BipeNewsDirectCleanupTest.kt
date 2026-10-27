// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.settings.BipeNewsDirectCleanup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one-time m.direct cleanup of the news room (bipe-chat/bipe#809). Same cases as NewsDirectCleanupTests.swift. */
class BipeNewsDirectCleanupTest {
    private val news = "!news:example.test"

    private fun parsed(json: String?) = json?.let { Json.parseToJsonElement(it) }

    @Test fun removesTheNewsRoomAndTheEmptyUser() {
        val json = """{"@bipe:example.test":["!news:example.test"],"@ana:example.test":["!dm:example.test"]}"""
        assertEquals(parsed("""{"@ana:example.test":["!dm:example.test"]}"""), parsed(BipeNewsDirectCleanup.removing(news, json)))
    }

    @Test fun keepsOtherRoomsOfTheSameUser() {
        val json = """{"@bipe:example.test":["!old:example.test","!news:example.test"]}"""
        assertEquals(parsed("""{"@bipe:example.test":["!old:example.test"]}"""), parsed(BipeNewsDirectCleanup.removing(news, json)))
    }

    @Test fun nothingToWriteWhenTheRoomIsNotThere() {
        assertNull(BipeNewsDirectCleanup.removing(news, """{"@ana:example.test":["!dm:example.test"]}"""))
        assertNull(BipeNewsDirectCleanup.removing(news, "{}"))
        assertNull(BipeNewsDirectCleanup.removing(news, null))
        assertNull(BipeNewsDirectCleanup.removing(news, "not json"))
    }
}
