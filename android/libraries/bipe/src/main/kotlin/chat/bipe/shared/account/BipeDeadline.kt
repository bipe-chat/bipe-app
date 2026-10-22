// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Blocking SDK work cannot keep the UI waiting after the deadline, even if it ignores cancellation. */
object BipeDeadline {
    suspend fun <T> run(timeoutMillis: Long, operation: suspend () -> T): T {
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = CompletableDeferred<T>()
        worker.launch {
            try { result.complete(operation()) }
            catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return try { withTimeout(timeoutMillis) { result.await() } }
        finally { worker.cancel() }
    }
}
