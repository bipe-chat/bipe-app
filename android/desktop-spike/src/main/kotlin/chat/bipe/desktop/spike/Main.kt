// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.spike

import chat.bipe.core.harness.BadgeHarness
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.sdkGitSha

// Synthetic identity only. The harness never logs in, syncs or sends credentials to a server.
private const val HOMESERVER = "http://127.0.0.1:9"
private const val USER = "@windows-spike:example.test"
private const val DEVICE = "BIPEWINDOWSSPIKE"

fun main(args: Array<String>) = runBlocking {
    check(System.getProperty("os.name").startsWith("Windows")) { "This gate must run on Windows" }
    check(System.getProperty("os.arch") in setOf("amd64", "x86_64")) { "Only Windows x64 is tested here" }
    val vectors = File(args[0]).readText()
    // Core -> SDK -> core in one JVM: catch load conflicts and verify the actual SDK revision.
    println(BadgeHarness.run(vectors) {
        // The pinned SDK's build.rs emits GitclBuilder.sha(true), a short Git hash.
        val sdkHash = sdkGitSha()
        check(sdkHash.length in 7..40 && args[1].startsWith(sdkHash)) { "SDK source pin differs" }
    })

    val root = Files.createTempDirectory("bipe-windows-spike-").toFile()
    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val session = Session("synthetic-not-a-credential", null, USER, DEVICE, HOMESERVER,
                          null, SlidingSyncVersion.NATIVE)
    var cleanupMillis = 0L
    try {
        buildClient(root, key).also { client ->
            try {
                check(client.homeserver().trimEnd('/') == HOMESERVER)
                client.restoreSession(session)
                check(client.userId() == USER && client.deviceId() == DEVICE)
            } finally { client.destroy() }
        }
        // Reopen the same encrypted store with the same synthetic device.
        buildClient(root, key).also { client ->
            try {
                client.restoreSession(session)
                check(client.session().deviceId == DEVICE && client.userId() == USER)
            } finally { client.destroy() }
        }
        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            val unexpected = buildClient(root, wrongKey)
            unexpected.destroy()
            error("Encrypted store accepted a different key")
        } catch (_: ClientBuildException) {
            // A typed SDK store-open error is required; a linkage/runtime error must fail the job.
        } finally { wrongKey.fill(0) }

        println(BadgeHarness.run(vectors))
    } finally {
        key.fill(0)
        // Native SQLite workers may release handles after client.destroy() returns.
        // Require complete removal within a bounded interval; do not rely on GC or process exit.
        val cleanupStart = System.nanoTime()
        withTimeout(10_000) {
            while (!root.deleteRecursively()) delay(25)
        }
        check(!root.exists()) { "SDK left the temporary store open" }
        cleanupMillis = (System.nanoTime() - cleanupStart) / 1_000_000
    }
    // Emit success only after native teardown and filesystem cleanup have both passed.
    println("BIPE_WINDOWS_SPIKE {\"architecture\":\"x64\",\"sdkCoreSameJvm\":true," +
            "\"encryptedStoreReopen\":true,\"wrongKeyRejected\":true," +
            "\"temporaryStoreRemoved\":true,\"storeCleanupMs\":$cleanupMillis," +
            "\"realAccount\":false,\"sync\":false,\"compose\":false,\"arm64\":false}")
}

private suspend fun buildClient(root: File, key: ByteArray): Client {
    val store = SqliteStoreBuilder(File(root, "data").path, File(root, "cache").path).key(key)
    val builder = ClientBuilder().sqliteStore(store).homeserverUrl(HOMESERVER)
        .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
        .autoEnableCrossSigning(false).autoEnableBackups(false)
    try { return builder.build() }
    finally {
        builder.destroy()
        store.destroy()
    }
}
