// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeDeadline
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.fail
import org.junit.Test

class DeadlineTest {
    @Test(timeout = 5_000)
    fun returnsValueAndError() = runBlocking {
        assertThat(BipeDeadline.run(2_000) { 42 }).isEqualTo(42)
        val expected = IllegalStateException("synthetic error")
        try {
            BipeDeadline.run(2_000) { throw expected }
            fail("The operation's error was lost")
        } catch (error: IllegalStateException) {
            assertThat(error).isSameInstanceAs(expected)
        }
    }

    @Test(timeout = 5_000)
    fun timeoutDoesNotWaitForBlockingWork() = runBlocking {
        val release = CountDownLatch(1)
        try {
            try {
                BipeDeadline.run(100) {
                    // Models a blocking FFI call that ignores coroutine cancellation.
                    release.await()
                    42
                }
                fail("Blocking work prevented the timeout")
            } catch (_: TimeoutCancellationException) { }
            assertThat(BipeDeadline.run(2_000) { 7 }).isEqualTo(7)
        } finally {
            release.countDown()
        }
    }

    @Test(timeout = 5_000)
    fun cancellationReturnsBeforeUncooperativeWorkFinishes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val task = async {
            BipeDeadline.run(10_000) {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                42
            }
        }
        started.await()
        task.cancel()
        task.join()
        assertThat(task.isCancelled).isTrue()
        release.complete(Unit)
        Unit
    }
}
