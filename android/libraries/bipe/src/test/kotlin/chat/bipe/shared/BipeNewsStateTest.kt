// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.settings.BipeNewsState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The GET, PUT and DELETE /v1/me/news answers of the contract (bipe-shared api/openapi.yaml). */
class BipeNewsStateTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun subscribedWithRoom() {
        assertEquals(BipeNewsState(true, "!news:example.test"), json.decodeFromString(BipeNewsState.serializer(), """{"subscribed":true,"room_id":"!news:example.test"}"""))
    }

    @Test fun offHasNoRoom() {
        assertEquals(BipeNewsState(false, null), json.decodeFromString(BipeNewsState.serializer(), """{"subscribed":false}"""))
    }
}
