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
import org.junit.Assert.assertEquals
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

    /** Starts an attempt and waits until the native login is running. */
    private suspend fun started(attempt: PhoneLinkAttempt) = attempt.start()!!.also { protocol.ready.await() }

    /** The phase after the native login ends with [outcome]: null finishes it, an exception fails it. */
    private suspend fun endedWith(outcome: Throwable?, commit: suspend () -> Unit = { commits.incrementAndGet(); Unit }): PhoneLinkPhase {
        val attempt = attempt(commit)
        val task = started(attempt)
        if (outcome == null) protocol.finish.complete(Unit) else protocol.finish.completeExceptionally(outcome)
        task.join()
        return attempt.phase.value
    }

    @Test fun aSecondStartDoesNotCreateASecondNativeLogin() = runBlocking {
        val attempt = attempt()
        val task = started(attempt)
        val second = attempt.start()
        protocol.finish.complete(Unit); task.join()
        assertEquals(mapOf("second start" to null, "native logins" to 1, "phase" to PhoneLinkPhase.Linked, "commits" to 1),
            mapOf("second start" to second, "native logins" to protocol.starts.get(), "phase" to attempt.phase.value, "commits" to commits.get()))
    }

    @Test fun linkedRequiresTheProtocolReturnAndDurableCommit() = runBlocking {
        val beginCommit = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val attempt = attempt { beginCommit.complete(Unit); finishCommit.await(); commits.incrementAndGet(); Unit }
        val task = started(attempt)
        val seen = mutableListOf<Any?>()
        protocol.emit(PhoneLinkUpdate.QrReady(LinkQrPayload(byteArrayOf(0, 1, -1))))
        seen += attempt.phase.value is PhoneLinkPhase.Qr
        protocol.emit(PhoneLinkUpdate.ImportingKeys)
        seen += commits.get(); seen += attempt.phase.value
        protocol.finish.complete(Unit); beginCommit.await()
        seen += attempt.phase.value
        finishCommit.complete(Unit); task.join()
        seen += attempt.phase.value
        assertEquals(listOf(true, 0, PhoneLinkPhase.ImportingKeys, PhoneLinkPhase.ImportingKeys, PhoneLinkPhase.Linked), seen)
    }

    @Test fun checkCodePreservesLeadingZeroAndRejectsDoubleSubmit() = runBlocking {
        val attempt = attempt()
        val task = started(attempt)
        val challenge = ControlledChallenge()
        protocol.emit(PhoneLinkUpdate.CheckRequested(challenge))
        val submitting = attempt.submitCheckCode("07")!!
        val second = attempt.submitCheckCode("42")
        challenge.started.await()
        protocol.emit(PhoneLinkUpdate.WaitingForApproval)
        val approving = attempt.phase.value
        challenge.finish.complete(Unit); submitting.join()
        protocol.finish.complete(Unit); task.join()
        assertEquals(mapOf("second submit" to null, "sent code" to 7u.toUByte(), "native sends" to 1, "phase" to PhoneLinkPhase.Approving),
            mapOf("second submit" to second, "sent code" to challenge.value, "native sends" to challenge.calls.get(), "phase" to approving))
    }

    @Test fun malformedAndUnicodeDigitsCannotReachTheNativeSender() = runBlocking {
        val attempt = attempt()
        started(attempt)
        val challenge = ControlledChallenge()
        protocol.emit(PhoneLinkUpdate.CheckRequested(challenge))
        val inputs = listOf("7", "007", " 7", "７７", "١٢", "ab", "-1")
        val answers = inputs.associateWith { input ->
            attempt.submitCheckCode(input) to (attempt.phase.value as PhoneLinkPhase.CheckCode).invalidInput
        }
        attempt.cancel()
        assertEquals(inputs.associateWith { null to true } to 0, answers to challenge.calls.get())
    }

    @Test fun lateProgressCannotMoveBackFromApprovalOrChangeATerminalResult() = runBlocking {
        val attempt = attempt()
        val task = started(attempt)
        listOf(PhoneLinkUpdate.ImportingKeys, PhoneLinkUpdate.WaitingForApproval, PhoneLinkUpdate.QrReady(LinkQrPayload(byteArrayOf(1))))
            .forEach(protocol::emit)
        val beforeFinish = attempt.phase.value
        protocol.finish.complete(Unit); task.join()
        protocol.emit(PhoneLinkUpdate.CheckRequested(ControlledChallenge()))
        val afterLateCheck = attempt.phase.value
        // A late close of the login screen cannot close the successfully linked account.
        attempt.cancel()
        assertEquals(listOf(PhoneLinkPhase.ImportingKeys, PhoneLinkPhase.Linked, true), listOf(beforeFinish, afterLateCheck, owner.isCurrent()))
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
        val task = started(attempt)
        attempt.cancel()
        nativeFinished.complete(Unit)
        withTimeout(2_000) { task.join(); owner.closeAsync().await() }
        assertEquals(PhoneLinkPhase.Cancelled to 0, attempt.phase.value to commits.get())
    }

    @Test fun durableStorageFailureCannotPresentLinked() = runBlocking {
        val missingKey = endedWith(null) { throw StorageFailure(StorageCode.MISSING_KEY) }
        val ownerAfterMissingKey = owner.isCurrent()
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.STORAGE) to false, missingKey to ownerAfterMissingKey)
    }

    @Test fun duplicateLinkIsAnExplicitFailureAndNeverReplacesTheExistingAccount() = runBlocking {
        assertEquals(PhoneLinkPhase.Failed(PhoneLinkFailure.DUPLICATE_ACCOUNT),
            endedWith(null) { throw StorageFailure(StorageCode.DUPLICATE_ACCOUNT) })
    }

    @Test fun expiryHasASafeFiniteState() = runBlocking {
        val phase = endedWith(PhoneLinkException(PhoneLinkFailure.EXPIRED))
        assertEquals(Triple(PhoneLinkPhase.Failed(PhoneLinkFailure.EXPIRED), 0, 1), Triple(phase, commits.get(), protocol.closes.get()))
    }

    @Test fun unexpectedSdkErrorDoesNotExposeItsMessageInTheFailureState() = runBlocking {
        val phase = endedWith(IllegalStateException("synthetic-private-credential"))
        assertEquals(Triple(PhoneLinkPhase.Failed(PhoneLinkFailure.PROTOCOL), false, 0),
            Triple(phase, "synthetic-private-credential" in phase.toString(), commits.get()))
    }

    @Test fun qrBytesAndCopyFallbackPreserveAllBinaryValuesWithoutExposingMutableStorage() {
        val input = byteArrayOf(0, 77, 65, 84, 82, 73, 88, -128, -1, 10)
        val payload = LinkQrPayload(input)
        val original = payload.copyBytes()
        input.fill(0)
        payload.copyBytes().fill(1)
        assertEquals(listOf("AE1BVFJJWID/Cg==", original.toList(), "LinkQrPayload(redacted)"),
            listOf(payload.copyText(), payload.copyBytes().toList(), payload.toString()))
    }

    @Test fun missingWindowsRegistrationNeverFallsBackToAnotherPlatform() {
        val failure = runCatching { WindowsLoginRegistration(" ") }.exceptionOrNull()
        assertEquals(PhoneLinkFailure.REGISTRATION_REQUIRED, (failure as? PhoneLinkException)?.reason)
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
