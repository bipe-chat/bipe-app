// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import chat.bipe.desktop.account.AccountCloseResult
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.chat.*
import com.sun.jna.Platform
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.*
import kotlinx.serialization.json.*

/** A cached joined room, real list/timeline listeners and an offline native sync supervisor must all drain. */
class WindowsNativeLifecycleTest {
    @Test fun offlineAccountClosesAndRestoresTheSameDeviceAndSavedDraft() = runBlocking {
        assumeTrue("Requires Windows native APIs", Platform.isWindows())
        val room = "!lifecycle:example.test"
        val online = AtomicBoolean(true)
        val seeded = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val markedUnread = AtomicBoolean(false)
        val syncResponses = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val requestBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val path = exchange.requestURI.path
                if (path.contains("/tags/") && exchange.requestMethod in listOf("PUT", "DELETE")) commands += "${exchange.requestMethod} tag"
                if ((path.contains("/receipt/") || path.endsWith("/read_markers")) && exchange.requestMethod == "POST") commands += "receipt"
                if (path.contains("/account_data/") && exchange.requestMethod == "PUT") {
                    runCatching { Json.parseToJsonElement(requestBody).jsonObject["unread"]?.jsonPrimitive?.booleanOrNull }
                        .getOrNull()?.let { markedUnread.set(it); commands += "unread:$it" }
                }
                // Subscribe before releasing the first sync failure: the SDK observer emits changes, not an initial value.
                if (path.endsWith("/sync") && exchange.requestMethod == "POST") check(syncResponses.await(20, TimeUnit.SECONDS))
                val (status, body) = when {
                    path.endsWith("/versions") && online.get() -> 200 to
                        """{"versions":["v1.12"],"unstable_features":{"org.matrix.simplified_msc3575":true}}"""
                    path.endsWith("/versions") -> 503 to """{"errcode":"M_UNKNOWN","error":"Synthetic offline"}"""
                    // Explicit V2 calls seed/echo the synthetic local store; the app's sliding supervisor stays offline.
                    path.endsWith("/sync") && exchange.requestMethod == "GET" -> 200 to """{
                        "next_batch":"seeded-${requests.get()}", "rooms":{"join":{"$room":{
                          "state":{"events":[
                            {"type":"m.room.create","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}create","origin_server_ts":1,"content":{"creator":"@synthetic:example.test","room_version":"10"}},
                            {"type":"m.room.member","state_key":"@synthetic:example.test","sender":"@synthetic:example.test","event_id":"${'$'}member","origin_server_ts":2,"content":{"membership":"join","displayname":"Synthetic"}},
                            {"type":"m.room.member","state_key":"@peer:example.test","sender":"@peer:example.test","event_id":"${'$'}peer","origin_server_ts":2,"content":{"membership":"join","displayname":"Peer"}},
                            {"type":"m.room.name","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}name","origin_server_ts":3,"content":{"name":"Lifecycle fixture"}},
                            {"type":"m.room.power_levels","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}power","origin_server_ts":4,"content":{"users":{"@synthetic:example.test":100}}}
                          ]},
                          "timeline":{"events":[{"type":"m.room.message","sender":"@peer:example.test","event_id":"${'$'}message","origin_server_ts":5,"content":{"msgtype":"m.text","body":"Synthetic lifecycle message"}}],"limited":false,"prev_batch":"before"},
                          "account_data":{"events":[{"type":"m.marked_unread","content":{"unread":${markedUnread.get()}}}]},
                          "unread_notifications":{"notification_count":0,"highlight_count":0}
                        }}}
                    }"""
                    path.endsWith("/sync") -> 400 to """{"errcode":"M_UNKNOWN","error":"Synthetic offline sync"}"""
                    path.endsWith("/messages") -> 200 to """{"chunk":[{"type":"m.room.message","sender":"@peer:example.test","event_id":"${'$'}message","origin_server_ts":5,"content":{"msgtype":"m.text","body":"Synthetic lifecycle message"}}],"state":[],"start":"before"}"""
                    path.endsWith("/keys/upload") -> 200 to """{"one_time_key_counts":{"signed_curve25519":50}}"""
                    path.endsWith("/keys/query") -> 200 to """{"device_keys":{},"failures":{}}"""
                    path.endsWith("/keys/claim") -> 200 to """{"one_time_keys":{},"failures":{}}"""
                    path.contains("/tags/") && exchange.requestMethod in listOf("PUT", "DELETE") -> 200 to "{}"
                    (path.contains("/receipt/") || path.endsWith("/read_markers")) && exchange.requestMethod == "POST" -> 200 to "{}"
                    path.contains("/account_data/") && exchange.requestMethod == "PUT" -> 200 to "{}"
                    else -> 404 to """{"errcode":"M_NOT_FOUND","error":"Synthetic fixture"}"""
                }
                if (requests.incrementAndGet() <= 60) println("BIPE_NATIVE_LIFECYCLE http=${exchange.requestMethod} $path status=$status")
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        val root = Files.createTempDirectory("bipe-native-offline-")
        val vault = WindowsStoreKeyVault("chat.bipe.tests/lifecycle/${UUID.randomUUID()}")
        val repository = AccountRepository(root, vault, WindowsDataProtection())
        val lease = repository.provision()
        var current: MatrixAccountRuntime? = null
        val labels = ChatLabels("Room", "Person", "Locked", "Unsupported")
        try {
            server.start()
            withTimeout(75_000) {
                val homeserver = "http://127.0.0.1:${server.address.port}"
                val first = MatrixAccountRuntime.open(lease, homeserver, "Bipe lifecycle test")
                current = first
                first.lifetime.async { first.client.restoreSession(session().copy(homeserverUrl = homeserver)) }.await()
                first.completePhoneLink(repository)
                println("BIPE_NATIVE_LIFECYCLE stage=seed")
                val seed = first.client.syncV2(SyncSettingsV2(timeoutMs = 100uL), object : SyncListenerV2 {
                    override fun onUpdate(response: SyncResponseV2) { seeded.complete(Unit) }
                })
                try { seeded.await() } finally { seed.cancel(); seed.destroy() }
                online.set(false)
                println("BIPE_NATIVE_LIFECYCLE stage=offline_list")
                val list = DesktopRoomList.open(first, labels)
                val rows = list.rows.first { it is LoadPhase.Ready && it.value.any { row -> row.id == room } } as LoadPhase.Ready<List<ChatRow>>
                assertEquals("Lifecycle fixture", rows.value.single { it.id == room }.name)
                val offline = CompletableDeferred<Unit>()
                val state = first.startSync().state(object : SyncServiceStateObserver {
                    override fun onUpdate(state: SyncServiceState) {
                        println("BIPE_NATIVE_LIFECYCLE sync=$state")
                        if (state == SyncServiceState.OFFLINE) offline.complete(Unit)
                    }
                })
                first.lifetime.own(chat.bipe.desktop.account.TaskResource(state))
                syncResponses.countDown()
                println("BIPE_NATIVE_LIFECYCLE stage=draft")
                val timeline = DesktopTimeline.open(first, room, labels)
                // V2 seeds room summaries before the live event cache exists. Populate that cache via the real pagination API.
                timeline.loadEarlier().join()
                timeline.rows.first { it is LoadPhase.Ready && it.value.any { row -> row.body == "Synthetic lifecycle message" } }
                timeline.composer.state.first { it is ComposerState.Editing }
                timeline.composer.edit("Synthetic offline saved draft")
                timeline.composer.state.first { it is ComposerState.Editing && it.persistence == DraftPersistence.SAVED }
                offline.await()
                println("BIPE_NATIVE_LIFECYCLE stage=explicit_room_controls")
                val chat = rows.value.single { it.id == room }
                list.archive.set(chat, true)
                list.archive.phase.first { it is ArchivePhase.Ready && room in it.markers && it.operations.isEmpty() }
                list.archive.set(chat.copy(archived = true), false)
                list.archive.phase.first { it is ArchivePhase.Ready && room !in it.markers && it.operations.isEmpty() }
                assertTrue(commands.contains("PUT tag")); assertTrue(commands.contains("DELETE tag"))
                list.readActions.set(room, true)
                list.readActions.phase.first { it is ReadActionPhase.Idle }
                assertTrue(list.readActions.phase.value.failures.isEmpty())
                // The SDK does not optimistically update m.marked_unread after PUT. Echo it before clearing it again.
                val echoed = CompletableDeferred<Unit>()
                val echo = first.client.syncV2(SyncSettingsV2(timeoutMs = 100uL), object : SyncListenerV2 {
                    override fun onUpdate(response: SyncResponseV2) { echoed.complete(Unit) }
                })
                try { echoed.await() } finally { echo.cancel(); echo.destroy() }
                list.readActions.set(room, false)
                list.readActions.phase.first { it is ReadActionPhase.Idle }
                assertTrue(list.readActions.phase.value.failures.isEmpty())
                val ordered = synchronized(commands) { commands.toList() }
                assertTrue(ordered.indexOf("unread:true") >= 0)
                assertTrue(ordered.indexOf("receipt") in (ordered.indexOf("unread:true") + 1) until ordered.indexOf("unread:false"))
                println("BIPE_NATIVE_LIFECYCLE stage=close_offline")
                // The account owns and closes the list/timeline. No GC, file deletion or manual SDK pause is used by the test.
                assertEquals(AccountCloseResult.Closed, first.closeAsync().await())
                println("BIPE_NATIVE_LIFECYCLE stage=restore_offline")
                val restored = MatrixAccountRuntime.restore(repository.open(lease.slot), "Bipe lifecycle test")
                current = restored
                assertEquals("SYNTHETIC_DEVICE", restored.client.session().deviceId)
                val restoredList = DesktopRoomList.open(restored, labels)
                val cached = restoredList.rows.first { it is LoadPhase.Ready && it.value.any { row -> row.id == room } } as LoadPhase.Ready<List<ChatRow>>
                assertEquals("Lifecycle fixture", cached.value.single { it.id == room }.name)
                val restoredTimeline = DesktopTimeline.open(restored, room, labels)
                restoredTimeline.rows.first { it is LoadPhase.Ready && it.value.any { row -> row.body == "Synthetic lifecycle message" } }
                val draft = restoredTimeline.composer.state.first { it is ComposerState.Editing } as ComposerState.Editing
                assertEquals("Synthetic offline saved draft", draft.text)
                assertEquals(AccountCloseResult.Closed, restored.closeAsync().await())
                current = null
            }
        } finally {
            syncResponses.countDown()
            try { withContext(NonCancellable) { current?.let { assertEquals(AccountCloseResult.Closed, it.closeAsync().await()) } } }
            finally { server.stop(0); lease.close(); vault.remove(lease.slot); root.toFile().deleteRecursively() }
        }
    }
}
