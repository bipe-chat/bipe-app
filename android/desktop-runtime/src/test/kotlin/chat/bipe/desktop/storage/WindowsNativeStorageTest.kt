// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import chat.bipe.desktop.account.AccountCloseResult
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.chat.ChatLabels
import chat.bipe.desktop.chat.ComposerState
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.chat.LoadPhase
import chat.bipe.desktop.settings.AccountAppearance
import chat.bipe.desktop.settings.AppearancePhase
import chat.bipe.desktop.settings.AppearancePolicy
import chat.bipe.desktop.settings.AppearanceSync
import chat.bipe.desktop.settings.MatrixSettingsReader
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Actual Windows APIs and the pinned SDK DLL. Synthetic credentials and loopback fixtures only. */
class WindowsNativeStorageTest {
    @Before fun windowsHost() { assumeTrue("Requires Windows native APIs", Platform.isWindows()) }

    @Test fun credentialStructureMatchesTheWindowsX64Abi() {
        assertEquals(8, Native.POINTER_SIZE)
        assertEquals(80, WindowsStoreKeyVault.Credential().size())
    }

    @Test fun credentialManagerStoresAnOwnedCopyAndDeletionIsIdempotent() {
        val vault = testVault()
        val slot = AccountSlot.create()
        val key = randomKey()
        try {
            assertNull(vault.read(slot))
            vault.create(slot, key)
            val copy = vault.read(slot)!!
            try {
                assertArrayEquals(key, copy)
                copy.fill(0)
                val second = vault.read(slot)!!
                try { assertArrayEquals(key, second) } finally { second.fill(0) }
            } finally { copy.fill(0) }
            expect(StorageCode.IDENTITY_MISMATCH) { vault.create(slot, randomKey().also { it.fill(0) }) }
            vault.remove(slot)
            assertNull(vault.read(slot))
            vault.remove(slot)
        } finally { key.fill(0); vault.remove(slot) }
    }

    @Test fun dpapiRejectsDifferentEntropyAndDamagedCiphertext() {
        val protection = WindowsDataProtection()
        val clear = "synthetic-session-only".toByteArray()
        val key = randomKey()
        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            val sealed = protection.protect(clear, key)
            val opened = protection.unprotect(sealed, key)
            try { assertArrayEquals(clear, opened) } finally { opened.fill(0) }
            expect(StorageCode.NATIVE_FAILURE) { protection.unprotect(sealed, wrongKey) }
            val damaged = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            expect(StorageCode.NATIVE_FAILURE) { protection.unprotect(damaged, key) }
        } finally { clear.fill(0); key.fill(0); wrongKey.fill(0) }
    }

    @Test fun protectedAppearanceCacheCannotBeReadUnderAnotherAccountKey() {
        val root = Files.createTempDirectory("bipe-appearance-dpapi-")
        val vault = testVault()
        val repository = AccountRepository(root, vault, WindowsDataProtection())
        val first = repository.provision()
        val second = repository.provision()
        val clear = """{"marker":"synthetic-private-preference","pending":["palette"]}"""
        try {
            first.writeAppearance(clear)
            assertEquals(clear, first.readAppearance())
            val sealed = Files.readAllBytes(first.paths.root.resolve("appearance.bin"))
            assertFalse(sealed.toString(Charsets.ISO_8859_1).contains("synthetic-private-preference"))
            Files.write(second.paths.root.resolve("appearance.bin"), sealed)
            expect(StorageCode.NATIVE_FAILURE) { second.readAppearance() }
            first.writeFavourites(clear)
            assertEquals(clear, first.readFavourites())
            val favourites = Files.readAllBytes(first.paths.root.resolve("favourites.bin"))
            assertFalse(favourites.toString(Charsets.ISO_8859_1).contains("synthetic-private-preference"))
            Files.write(second.paths.root.resolve("favourites.bin"), favourites)
            expect(StorageCode.NATIVE_FAILURE) { second.readFavourites() }
        } finally {
            first.close(); second.close(); vault.remove(first.slot); vault.remove(second.slot)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun largeOAuthSessionRoundTripsWithoutPlaintextInTheAccountDirectory() {
        val root = Files.createTempDirectory("bipe-dpapi-test-")
        val vault = testVault()
        val repository = AccountRepository(root, vault, WindowsDataProtection())
        val lease = repository.provision()
        val payload = "synthetic-oauth-private-".repeat(500) // Larger than the WinCred generic blob limit.
        try {
            lease.persistSession(session().copy(oauthData = payload))
            val disk = Files.readAllBytes(lease.paths.root.resolve(AccountRepository.RECORD))
            assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic-oauth-private"))
            assertFalse(disk.toString(Charsets.ISO_8859_1).contains("synthetic-access"))
            lease.close()
            repository.open(lease.slot).use { reopened ->
                assertEquals(payload, reopened.savedSession()!!.oauthData)
                assertEquals(RevocationResult.Complete, reopened.revoke())
            }
            repository.removeRevokedFiles(lease.slot)
            assertFalse(Files.exists(lease.paths.root))
        } finally {
            lease.close()
            vault.remove(lease.slot)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun nativeSdkReopensWithSameProtectedKeyAndDeviceAndRefusesMissingKeyOrStore() = runBlocking {
        val root = Files.createTempDirectory("bipe-sdk-vault-test-")
        val vault = testVault()
        val repository = AccountRepository(root, vault, WindowsDataProtection())
        val lease = repository.provision()
        val slot = lease.slot
        var runtime: MatrixAccountRuntime? = null
        try {
            val linked = MatrixAccountRuntime.open(lease, "http://127.0.0.1:9", "Bipe Windows native storage test")
            runtime = linked
            linked.lifetime.async { linked.client.restoreSession(session()) }.await()
            linked.completePhoneLink(repository) // Synthetic protocol boundary: no real phone approval claimed.
            assertEquals(AccountCloseResult.Closed, linked.closeAsync().await())
            val restored = MatrixAccountRuntime.restore(repository.open(slot), "Bipe Windows native storage test")
            runtime = restored
            restored.lifetime.async {
                assertEquals("SYNTHETIC_DEVICE", restored.client.session().deviceId)
                assertEquals("@synthetic:example.test", restored.client.userId())
            }.await()
            assertEquals(AccountCloseResult.Closed, restored.closeAsync().await())
            runtime = null

            val key = vault.read(slot)!!
            try {
                vault.remove(slot)
                expect(StorageCode.MISSING_KEY) { repository.open(slot) }
                assertTrue(Files.exists(lease.paths.data.resolve(SdkStoreLayout.databases.first())))
                vault.create(slot, key)
            } finally { key.fill(0) }

            val database = lease.paths.data.resolve(SdkStoreLayout.databases.first())
            Files.delete(database)
            expect(StorageCode.MISSING_STORE) { repository.open(slot) }
            assertFalse(Files.exists(database))
        } finally {
            withContext(NonCancellable) {
                runtime?.let { assertEquals(AccountCloseResult.Closed, it.closeAsync().await()) }
            }
            lease.close()
            vault.remove(slot)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun nativeTimelineOpensAndReopensWithoutRetiringItsAccount() = runBlocking {
        val roomId = "!windows-timeline:example.test"
        val mediaAuthenticated = AtomicBoolean()
        val settingsAuthenticated = AtomicBoolean()
        val serverSettings = AtomicReference("""{"palette":"acai","readReceipts":false,"future":"synthetic-sensitive-setting"}""")
        val thumbnail = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1sAAAAASUVORK5CYII=")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val requestBody = exchange.requestBody.use { it.readBytes() }
                val path = exchange.requestURI.path
                if (path.contains("/media/thumbnail/")) {
                    mediaAuthenticated.set(exchange.requestHeaders.getFirst("Authorization") == "Bearer synthetic-access")
                    exchange.responseHeaders.set("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, thumbnail.size.toLong())
                    exchange.responseBody.use { it.write(thumbnail) }
                    return@createContext
                }
                val (status, body) = when {
                    path.endsWith("/account_data/chat.bipe.settings") -> {
                        settingsAuthenticated.set(exchange.requestHeaders.getFirst("Authorization") == "Bearer synthetic-access")
                        if (exchange.requestMethod == "PUT") { serverSettings.set(requestBody.toString(Charsets.UTF_8)); 200 to "{}" }
                        else 200 to serverSettings.get()
                    }
                    path.endsWith("/devices") -> 200 to """{"devices":[{"device_id":"SYNTHETIC_DEVICE","display_name":"Test computer"},{"device_id":"OTHER_SYNTHETIC_DEVICE","display_name":"Test phone"}]}"""
                    path.endsWith("/versions") -> 200 to """{"versions":["v1.12"],"unstable_features":{"org.matrix.simplified_msc3575":true}}"""
                    exchange.requestMethod == "POST" && (path.contains("/join/") || path.endsWith("/join")) ->
                        200 to """{"room_id":"$roomId"}"""
                    path.endsWith("/messages") -> 200 to """{"chunk":[],"start":"synthetic","state":[]}"""
                    path.endsWith("/sync") -> 503 to """{"errcode":"M_UNKNOWN","error":"Synthetic offline sync"}"""
                    else -> {
                        println("BIPE_NATIVE_TIMELINE fixture_unhandled=${exchange.requestMethod} $path")
                        404 to """{"errcode":"M_NOT_FOUND","error":"Synthetic fixture"}"""
                    }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        val root = Files.createTempDirectory("bipe-native-timeline-")
        val vault = testVault()
        val repository = AccountRepository(root, vault, WindowsDataProtection())
        val lease = repository.provision()
        var runtime: MatrixAccountRuntime? = null
        try {
            server.start()
            withTimeout(30_000) {
                val homeserver = "http://127.0.0.1:${server.address.port}"
                println("BIPE_NATIVE_TIMELINE stage=client")
                val linked = MatrixAccountRuntime.open(lease, homeserver, "Bipe Windows timeline test")
                runtime = linked
                linked.lifetime.async { linked.client.restoreSession(session().copy(homeserverUrl = homeserver)) }.await()
                linked.completePhoneLink(repository) // Synthetic session boundary; no real phone/account is used.
                println("BIPE_NATIVE_TIMELINE stage=authenticated_thumbnail")
                assertArrayEquals(thumbnail, linked.media.thumbnail("mxc://example.test/synthetic-photo", 96))
                assertTrue("The SDK must authenticate the media request", mediaAuthenticated.get())
                println("BIPE_NATIVE_TIMELINE stage=account_appearance")
                val appearance = AccountAppearance.open(linked, AppearancePolicy(listOf("acai", "goiaba"), "acai", listOf("marca", "frutas"), "marca"))
                appearance.pickPalette("goiaba")
                val saved = appearance.phase.first { it is AppearancePhase.Ready && it.sync != AppearanceSync.SAVING } as AppearancePhase.Ready
                assertEquals(AppearanceSync.IDLE, saved.sync)
                assertEquals("goiaba", saved.values.palette(appearance.policy))
                assertTrue(settingsAuthenticated.get())
                val event = Json.parseToJsonElement(serverSettings.get()).jsonObject
                assertEquals(false, event.getValue("readReceipts").jsonPrimitive.boolean)
                assertEquals("synthetic-sensitive-setting", event.getValue("future").jsonPrimitive.content)
                assertFalse(Files.readAllBytes(lease.paths.root.resolve("appearance.bin")).toString(Charsets.ISO_8859_1).contains("synthetic-sensitive-setting"))
                val devices = linked.lifetime.async { MatrixSettingsReader(linked.client).devices() }.await()
                assertEquals(2, devices.size)
                assertEquals("Test computer", devices.single { it.current }.name)
                println("BIPE_NATIVE_TIMELINE stage=sync")
                val sync = linked.startSync()
                assertSame(sync, linked.startSync())
                println("BIPE_NATIVE_TIMELINE stage=join")
                linked.client.joinRoomById(roomId).destroy()
                repeat(2) {
                    println("BIPE_NATIVE_TIMELINE stage=open pass=$it")
                    val timeline = DesktopTimeline.open(linked, roomId, ChatLabels("Room", "Person", "Locked", "Unsupported"))
                    try {
                        assertTrue(timeline.rows.first { it !is LoadPhase.Loading } is LoadPhase.Ready<*>)
                        assertTrue(timeline.composer.state.first { it != ComposerState.Loading } is ComposerState.Editing)
                    } finally {
                        println("BIPE_NATIVE_TIMELINE stage=close_timeline pass=$it")
                        timeline.close()
                    }
                    assertTrue(linked.lifetime.isCurrent())
                }
            }
        } finally {
            try {
                println("BIPE_NATIVE_TIMELINE stage=close_account")
                withContext(NonCancellable) {
                    runtime?.let { assertEquals(AccountCloseResult.Closed, it.closeAsync().await()) }
                }
            } finally {
                server.stop(0)
                lease.close()
                vault.remove(lease.slot)
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun testVault() = WindowsStoreKeyVault("chat.bipe.tests/windows/${UUID.randomUUID()}")
    private fun randomKey() = ByteArray(WindowsStoreKeyVault.KEY_SIZE).also { SecureRandom().nextBytes(it) }
}
