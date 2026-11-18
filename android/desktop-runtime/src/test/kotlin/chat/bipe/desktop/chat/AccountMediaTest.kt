// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.*
import chat.bipe.desktop.storage.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountMediaTest {
    @Test fun sameMediaUrlHasSeparateAccountCachesAndReturnedBytesCannotMutateTheCache() = runBlocking {
        withMediaOwner { first -> withMediaOwner { second ->
            val calls = AtomicInteger()
            val a = AccountMedia.create(first) { _, _ -> calls.incrementAndGet(); byteArrayOf(1) }
            val b = AccountMedia.create(second) { _, _ -> calls.incrementAndGet(); byteArrayOf(2) }
            a.thumbnail(URL, 96)!!.fill(9)
            assertArrayEquals(byteArrayOf(1), a.thumbnail(URL, 96))
            assertArrayEquals(byteArrayOf(2), b.thumbnail(URL, 96))
            assertEquals(2, calls.get())
        } }
    }

    @Test fun concurrentRequestsShareOneDownloadAndFailuresCanBeRetried() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val media = AccountMedia.create(owner) { _, _ ->
                entered.complete(Unit); release.await()
                if (calls.incrementAndGet() == 1) error("Synthetic thumbnail unavailable")
                byteArrayOf(3)
            }
            val requests = List(8) { async(start = CoroutineStart.UNDISPATCHED) { media.thumbnail(URL, 96) } }
            entered.await(); release.complete(Unit)
            assertTrue(requests.awaitAll().all { it == null })
            assertEquals(1, calls.get())
            assertArrayEquals(byteArrayOf(3), media.thumbnail(URL, 96))
            assertEquals(2, calls.get())
        }
    }

    @Test fun rejectsNonMatrixLocationsAndBoundsThumbnailSizeAndPayload() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val media = AccountMedia.create(owner) { _, pixels ->
                assertEquals(256, pixels)
                calls.incrementAndGet()
                ByteArray(2 * 1024 * 1024 + 1)
            }
            for (url in listOf("https://example.test/photo", "file:///secret", "mxc://example.test/", "mxc://user@example.test/id", "$URL?token=private"))
                assertNull(media.thumbnail(url, 900))
            assertEquals(0, calls.get())
            assertNull(media.thumbnail(URL, 900))
            assertEquals(1, calls.get())
        }
    }

    @Test fun retiringAnAccountCancelsRequestsAndRejectsLateResults() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val media = AccountMedia.create(owner) { _, _ ->
                withContext(NonCancellable) { entered.complete(Unit); release.await(); byteArrayOf(4) }
            }
            val request = async { runCatching { media.thumbnail(URL, 96) } }
            entered.await()
            val closing = owner.closeAsync()
            release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertTrue(request.await().isFailure)
            assertTrue(runCatching { media.thumbnail(URL, 96) }.isFailure)
        }
    }

    @Test fun eventPicturesCacheSmallImagesOnlyAndRefuseOversizedDownloads() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val sizes = mapOf(SMALL to 1_000, LARGE to 3 * 1024 * 1024, HUGE to 16 * 1024 * 1024 + 1)
            val media = AccountMedia.create(owner, FakeBackend(content = { json -> calls.incrementAndGet(); ByteArray(sizes.getValue(json)) { 1 } }))
            assertEquals(1_000, media.eventPicture(SMALL)!!.size)
            assertEquals(1_000, media.eventPicture(SMALL)!!.size)
            assertEquals(1, calls.get())
            assertEquals(3 * 1024 * 1024, media.eventPicture(LARGE)!!.size)
            assertEquals(3 * 1024 * 1024, media.eventPicture(LARGE)!!.size)
            assertEquals(3, calls.get())
            assertNull(media.eventPicture(HUGE))
            assertNull(media.eventPicture(""))
            assertEquals(4, calls.get())
        }
    }

    @Test fun saveSendsTheEventSourceAndRejectsARelativeDestination() = runBlocking {
        withMediaOwner { owner ->
            val folder = Files.createTempDirectory("bipe-media-save-")
            try {
                val saved = mutableListOf<String>()
                val media = AccountMedia.create(owner, FakeBackend(save = { json, name, mime, path -> saved += "$json|$name|$mime|${path.fileName}" }))
                val file = MessageMedia(MessageMedia.Kind.FILE, "nota.pdf", sourceJson = SMALL)
                media.save(file, folder.resolve("nota.pdf"))
                assertEquals(listOf("$SMALL|nota.pdf|application/octet-stream|nota.pdf"), saved)
                assertTrue(runCatching { media.save(file, java.nio.file.Path.of("nota.pdf")) }.isFailure)
                assertTrue(runCatching { media.save(file, folder.resolve("missing/nota.pdf")) }.isFailure)
                assertEquals(1, saved.size)
            } finally { folder.toFile().deleteRecursively() }
        }
    }

    private class FakeBackend(
        private val content: suspend (String) -> ByteArray = { error("unused") },
        private val save: suspend (String, String, String, java.nio.file.Path) -> Unit = { _, _, _, _ -> error("unused") },
    ) : MediaBackend {
        override suspend fun thumbnail(url: String, pixels: Int): ByteArray = error("unused")
        override suspend fun content(sourceJson: String) = content.invoke(sourceJson)
        override suspend fun save(sourceJson: String, filename: String, mimeType: String, destination: java.nio.file.Path) =
            save.invoke(sourceJson, filename, mimeType, destination)
    }

    companion object {
        private const val URL = "mxc://example.test/synthetic-photo"
        private const val SMALL = """{"url":"mxc://example.test/small"}"""
        private const val LARGE = """{"url":"mxc://example.test/large"}"""
        private const val HUGE = """{"url":"mxc://example.test/huge"}"""
    }
}

internal suspend fun withMediaOwner(block: suspend (AccountLifetime) -> Unit) {
    val root = Files.createTempDirectory("bipe-media-owner-")
    val owner = AccountLifetime(AccountRepository(root, MemoryVault(), MemoryProtection()).provision())
    try { withTimeout(5_000) { block(owner) } }
    finally { assertEquals(AccountCloseResult.Closed, owner.closeAsync().await()); root.toFile().deleteRecursively() }
}
