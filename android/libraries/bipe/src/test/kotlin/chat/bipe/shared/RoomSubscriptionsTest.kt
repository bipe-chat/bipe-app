// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RoomSubscriptionsTest {
    @Test fun ownersShareUnionAndReleaseIndependently() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val subscriptions = BipeRoomSubscriptions { calls += it }
        subscriptions.update("chats", listOf("!a:example.test", "!a:example.test"))
        subscriptions.update("unread", listOf("!a:example.test", "!b:example.test"))
        subscriptions.remove("chats")
        subscriptions.remove("unread")
        assertThat(calls).containsExactly(listOf("!a:example.test"), listOf("!a:example.test", "!b:example.test"), emptyList<String>()).inOrder()
    }

    @Test fun failureRetriesAndClosedAccountCannotRevive() = runBlocking {
        val calls = mutableListOf<List<String>>()
        var failsOnce = true
        val subscriptions = BipeRoomSubscriptions {
            calls += it
            if (failsOnce) { failsOnce = false; error("synthetic error") }
        }
        subscriptions.update("chats", listOf("!a:example.test"))
        subscriptions.update("chats", listOf("!a:example.test"))
        subscriptions.close()
        subscriptions.update("late-view", listOf("!b:example.test"))
        assertThat(calls).containsExactly(listOf("!a:example.test"), listOf("!a:example.test"), emptyList<String>()).inOrder()
        val second = mutableListOf<List<String>>()
        BipeRoomSubscriptions { second += it }.update("chats", listOf("!c:example.test"))
        assertThat(second).containsExactly(listOf("!c:example.test"))
    }

    @Test(timeout = 5_000) fun concurrentOwnersNeverReplaceEachOther() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<List<String>>()
        val subscriptions = BipeRoomSubscriptions {
            calls += it
            if (calls.size == 1) { started.complete(Unit); release.await() }
        }
        val chats = async { subscriptions.update("chats", listOf("!a:example.test")) }
        started.await()
        val groups = async { subscriptions.update("groups", listOf("!b:example.test")) }
        release.complete(Unit)
        chats.await(); groups.await()
        subscriptions.remove("chats")
        assertThat(calls.last()).containsExactly("!b:example.test")
        assertThat(calls[1]).containsExactly("!a:example.test", "!b:example.test")
    }

    @Test fun SDKNameSurvivesMissingHeroMetadata() {
        assertThat(BipeChatRowName.resolve("!room:example.test", " Example Support ", null, null, "Unnamed")).isEqualTo("Example Support")
        assertThat(BipeChatRowName.resolve("!room:example.test", " ", "Example Person", "@person:example.test", "Unnamed")).isEqualTo("Example Person")
        assertThat(BipeChatRowName.resolve("!room:example.test", "!room:example.test", "@person:example.test", "@person:example.test", "Unnamed")).isEqualTo("person")
    }
}
