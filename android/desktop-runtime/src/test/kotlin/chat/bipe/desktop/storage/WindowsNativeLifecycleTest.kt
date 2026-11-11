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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.*

/** A cached joined room, real list/timeline listeners and an offline native sync supervisor must all drain. */
class WindowsNativeLifecycleTest {
    @Test fun offlineAccountClosesAndRestoresTheSameDeviceAndSavedDraft() = runBlocking {
        assumeTrue("Requires Windows native APIs", Platform.isWindows())
        val room = "!lifecycle:example.test"
        val online = AtomicBoolean(true)
        val seeded = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                val path = exchange.requestURI.path
                val (status, body) = when {
                    path.endsWith("/versions") && online.get() -> 200 to
                        """{"versions":["v1.12"],"unstable_features":{"org.matrix.simplified_msc3575":true}}"""
                    path.endsWith("/versions") -> 503 to """{"errcode":"M_UNKNOWN","error":"Synthetic offline"}"""
                    path.endsWith("/sync") && exchange.requestMethod == "GET" && online.get() -> 200 to """{
                        "next_batch":"seeded", "rooms":{"join":{"$room":{
                          "state":{"events":[
                            {"type":"m.room.create","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}create","origin_server_ts":1,"content":{"creator":"@synthetic:example.test","room_version":"10"}},
                            {"type":"m.room.member","state_key":"@synthetic:example.test","sender":"@synthetic:example.test","event_id":"${'$'}member","origin_server_ts":2,"content":{"membership":"join","displayname":"Synthetic"}},
                            {"type":"m.room.name","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}name","origin_server_ts":3,"content":{"name":"Lifecycle fixture"}},
                            {"type":"m.room.power_levels","state_key":"","sender":"@synthetic:example.test","event_id":"${'$'}power","origin_server_ts":4,"content":{"users":{"@synthetic:example.test":100}}}
                          ]},
                          "timeline":{"events":[{"type":"m.room.message","sender":"@synthetic:example.test","event_id":"${'$'}message","origin_server_ts":5,"content":{"msgtype":"m.text","body":"Synthetic lifecycle message"}}],"limited":false,"prev_batch":"before"},
                          "unread_notifications":{"notification_count":0,"highlight_count":0}
                        }}}
                    }"""
                    path.endsWith("/sync") -> 400 to """{"errcode":"M_UNKNOWN","error":"Synthetic offline sync"}"""
                    path.endsWith("/messages") -> 200 to """{"chunk":[],"state":[],"start":"before"}"""
                    path.endsWith("/keys/upload") -> 200 to """{"one_time_key_counts":{"signed_curve25519":50}}"""
                    path.endsWith("/keys/query") -> 200 to """{"device_keys":{},"failures":{}}"""
                    path.endsWith("/keys/claim") -> 200 to """{"one_time_keys":{},"failures":{}}"""
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
                println("BIPE_NATIVE_LIFECYCLE stage=draft")
                val timeline = DesktopTimeline.open(first, room, labels)
                timeline.composer.state.first { it is ComposerState.Editing }
                timeline.composer.edit("Synthetic offline saved draft")
                timeline.composer.state.first { it is ComposerState.Editing && it.persistence == DraftPersistence.SAVED }
                offline.await()
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
                val draft = restoredTimeline.composer.state.first { it is ComposerState.Editing } as ComposerState.Editing
                assertEquals("Synthetic offline saved draft", draft.text)
                assertEquals(AccountCloseResult.Closed, restored.closeAsync().await())
                current = null
            }
        } finally {
            try { withContext(NonCancellable) { current?.let { assertEquals(AccountCloseResult.Closed, it.closeAsync().await()) } } }
            finally { server.stop(0); lease.close(); vault.remove(lease.slot); root.toFile().deleteRecursively() }
        }
    }
}
