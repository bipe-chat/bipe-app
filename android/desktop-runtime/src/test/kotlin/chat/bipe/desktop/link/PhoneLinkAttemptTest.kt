// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.storage.AccountRepository
import chat.bipe.desktop.storage.MemoryProtection
import chat.bipe.desktop.storage.MemoryVault
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PhoneLinkAttemptTest {
    private val root = Files.createTempDirectory("bipe-link-test-")
    private val lease = AccountRepository(root, MemoryVault(), MemoryProtection()).provision()
    private val owner = AccountLifetime(lease)
    private val protocol = ControlledProtocol()
    private val commits = AtomicInteger()
    private fun attempt(commit: suspend () -> Unit = { commits.incrementAndGet(); Unit }) =
        PhoneLinkAttempt(owner, protocol, commit)

    @After fun cleanup() = runBlocking {
        owner.closeAsync().await()
        lease.close()
        root.toFile().deleteRecursively()
        Unit
    }

    @Test fun aSecondStartDoesNotCreateASecondNativeLogin() = runBlocking {
        val attempt = attempt()
        val task = attempt.start()!!
        protocol.ready.await()
        assertNull(attempt.start())
        assertEquals(1, protocol.starts.get())
        protocol.finish.complete(Unit)
        task.join()
        assertEquals(PhoneLinkPhase.Linked, attempt.phase.value)
        assertEquals(1, commits.get())
    }

    @Test fun linkedRequiresTheProtocolReturnAndDurableCommit() = runBlocking {
        val beginCommit = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val attempt = attempt { beginCommit.complete(Unit); finishCommit.await(); commits.incrementAndGet(); Unit }
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.emit(PhoneLinkUpdate.QrReady(LinkQrPayload(byteArrayOf(0, 1, -1))))
        assertTrue(attempt.phase.value is PhoneLinkPhase.Qr)
        protocol.emit(PhoneLinkUpdate.ImportingKeys)
        assertEquals(0, commits.get())
        assertEquals(PhoneLinkPhase.ImportingKeys, attempt.phase.value)
        protocol.finish.complete(Unit)
        beginCommit.await()
        assertEquals(PhoneLinkPhase.ImportingKeys, attempt.phase.value)
        finishCommit.complete(Unit)
        task.join()
        assertEquals(PhoneLinkPhase.Linked, attempt.phase.value)
    }

    @Test fun checkCodePreservesLeadingZeroAndRejectsDoubleSubmit() = runBlocking {
        val attempt = attempt()
        val task = attempt.start()!!
        protocol.ready.await()
        val challenge = ControlledChallenge()
        protocol.emit(PhoneLinkUpdate.CheckRequested(challenge))
        val submitting = attempt.submitCheckCode("07")!!
        assertNull(attempt.submitCheckCode("42"))
        challenge.started.await()
        assertEquals(7u.toUByte(), challenge.value)
        assertEquals(1, challenge.calls.get())
        protocol.emit(PhoneLinkUpdate.WaitingForApproval)
        assertEquals(PhoneLinkPhase.Approving, attempt.phase.value)
        challenge.finish.complete(Unit)
        submitting.join()
        protocol.finish.complete(Unit)
        task.join()
    }

    @Test fun malformedAndUnicodeDigitsCannotReachTheNativeSender() = runBlocking {
        val attempt = attempt()
        attempt.start()
        protocol.ready.await()
        val challenge = ControlledChallenge()
        protocol.emit(PhoneLinkUpdate.CheckRequested(challenge))
        for (input in listOf("7", "007", " 7", "７７", "١٢", "ab", "-1")) {
            assertNull(attempt.submitCheckCode(input))
            assertTrue((attempt.phase.value as PhoneLinkPhase.CheckCode).invalidInput)
        }
        assertEquals(0, challenge.calls.get())
        attempt.cancel()
    }

    @Test fun lateProgressCannotMoveBackFromApprovalOrChangeATerminalResult() = runBlocking {
        val attempt = attempt()
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.emit(PhoneLinkUpdate.ImportingKeys)
        protocol.emit(PhoneLinkUpdate.WaitingForApproval)
        protocol.emit(PhoneLinkUpdate.QrReady(LinkQrPayload(byteArrayOf(1))))
        assertEquals(PhoneLinkPhase.ImportingKeys, attempt.phase.value)
        protocol.finish.complete(Unit)
        task.join()
        protocol.emit(PhoneLinkUpdate.CheckRequested(ControlledChallenge()))
        assertEquals(PhoneLinkPhase.Linked, attempt.phase.value)
        // A late close of the login screen cannot close the successfully linked account.
        attempt.cancel()
        assertTrue(owner.isCurrent())
    }

    @Test fun cancelledNativeWorkThatFinishesLateCannotCommit() = runBlocking {
        val nativeFinished = CompletableDeferred<Unit>()
        val stubborn = object : PhoneLinkProtocol {
            override suspend fun generate(onUpdate: (PhoneLinkUpdate) -> Unit) = withContext(NonCancellable) {
                protocol.ready.complete(Unit)
                nativeFinished.await()
                onUpdate(PhoneLinkUpdate.ImportingKeys)
            }
            override suspend fun close() {}
        }
        val attempt = PhoneLinkAttempt(owner, stubborn) { commits.incrementAndGet(); Unit }
        val task = attempt.start()!!
        protocol.ready.await()
        attempt.cancel()
        nativeFinished.complete(Unit)
        withTimeout(2_000) { task.join(); owner.closeAsync().await() }
        assertEquals(PhoneLinkPhase.Cancelled, attempt.phase.value)
        assertEquals(0, commits.get())
    }

    @Test fun durableStorageFailureCannotPresentLinked() = runBlocking {
        val attempt = attempt { throw StorageFailure(StorageCode.MISSING_KEY) }
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.finish.complete(Unit)
        task.join()
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.STORAGE), attempt.phase.value)
        assertFalse(owner.isCurrent())
    }

    @Test fun duplicateLinkIsAnExplicitFailureAndNeverReplacesTheExistingAccount() = runBlocking {
        val attempt = attempt { throw StorageFailure(StorageCode.DUPLICATE_ACCOUNT) }
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.finish.complete(Unit)
        task.join()
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.DUPLICATE_ACCOUNT), attempt.phase.value)
    }

    @Test fun expiryHasASafeFiniteState() = runBlocking {
        val attempt = attempt()
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.finish.completeExceptionally(PhoneLinkException(PhoneLinkFailure.EXPIRED))
        task.join()
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.EXPIRED), attempt.phase.value)
        assertEquals(0, commits.get())
        assertEquals(1, protocol.closes.get())
    }

    @Test fun unexpectedSdkErrorDoesNotExposeItsMessageInTheFailureState() = runBlocking {
        val attempt = attempt()
        val task = attempt.start()!!
        protocol.ready.await()
        protocol.finish.completeExceptionally(IllegalStateException("synthetic-private-credential"))
        task.join()
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.PROTOCOL), attempt.phase.value)
        assertFalse(attempt.phase.value.toString().contains("synthetic-private-credential"))
        assertEquals(0, commits.get())
    }

    @Test fun qrBytesAndCopyFallbackPreserveAllBinaryValuesWithoutExposingMutableStorage() {
        val input = byteArrayOf(0, 77, 65, 84, 82, 73, 88, -128, -1, 10)
        val payload = LinkQrPayload(input)
        assertEquals("AE1BVFJJWID/Cg==", payload.copyText())
        val original = payload.copyBytes()
        input.fill(0)
        payload.copyBytes().fill(1)
        assertArrayEquals(original, payload.copyBytes())
        assertEquals("LinkQrPayload(redacted)", payload.toString())
    }

    @Test fun missingWindowsRegistrationNeverFallsBackToAnotherPlatform() {
        try { WindowsLoginRegistration(" "); fail("Registration must be explicit") }
        catch (failure: PhoneLinkException) { assertEquals(PhoneLinkFailure.REGISTRATION_REQUIRED, failure.reason) }
    }

    private class ControlledProtocol : PhoneLinkProtocol {
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        private var update: ((PhoneLinkUpdate) -> Unit)? = null
        override suspend fun generate(onUpdate: (PhoneLinkUpdate) -> Unit) {
            starts.incrementAndGet()
            update = onUpdate
            ready.complete(Unit)
            finish.await()
        }
        fun emit(value: PhoneLinkUpdate) { update!!(value) }
        override suspend fun close() { closes.incrementAndGet() }
    }

    private class ControlledChallenge : CheckCodeChallenge {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var value: UByte? = null
        override suspend fun submit(code: UByte) {
            calls.incrementAndGet()
            value = code
            started.complete(Unit)
            finish.await()
        }
    }
}
