// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.BipePushMessage.CountChanged
import chat.bipe.shared.BipePushMessage.Event
import chat.bipe.shared.BipePushMessage.Ignored
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Android FCM data messages: the account comes only from a route registered on this phone. */
class PushMessageTest {
    private val routes = mapOf("route-a" to "@a:bipe.net.br")

    private fun parse(vararg pairs: Pair<String, String>) = BipePushMessage.parse(mapOf(*pairs)) { routes[it] }

    @Test
    fun eachPayloadShape() {
        val expected = listOf(
            parse("account_route" to "route-a", "room_id" to "!r", "event_id" to "\$e") to Event("@a:bipe.net.br", "!r", "\$e"),
            parse("account_route" to "route-x", "room_id" to "!r", "event_id" to "\$e") to Event(null, "!r", "\$e"),
            parse("room_id" to "!r", "event_id" to "\$e") to Event(null, "!r", "\$e"),
            parse("account_route" to "route-a", "unread" to "0") to CountChanged("@a:bipe.net.br"),
            parse("account_route" to "route-x", "unread" to "0") to Ignored,
            parse("unread" to "3") to Ignored,
            parse("account_route" to "route-a", "event_id" to "\$e") to Ignored,
            parse("account_route" to "", "room_id" to " ", "event_id" to "\$e") to Ignored,
        )
        for ((actual, wanted) in expected) assertThat(actual).isEqualTo(wanted)
    }
}
