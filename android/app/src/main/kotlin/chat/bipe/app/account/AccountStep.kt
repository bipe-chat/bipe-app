// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import chat.bipe.shared.account.BipeDeadline

/**
 * One step of opening an account (spec account-start): a time limit, so a step that never returns becomes a failure, and
 * a log line before and after, so `adb logcat -s account-start` shows where a start stops. Never logs a password or a
 * token.
 */
object AccountStep {
    const val TAG = "account-start"

    /** The time limit of each step. */
    private const val LIMIT_MS = 20_000L

    /** The step did not finish within the time limit. */
    class TimedOut(step: String) : Exception("$step timed out")

    /** The saved session has no SDK store on this phone. */
    class NoStore : Exception("no SDK store")

    suspend fun <T> run(name: String, operation: suspend () -> T): T {
        Log.i(TAG, "$name: start")
        return try {
            BipeDeadline.run(LIMIT_MS, operation).also { Log.i(TAG, "$name: done") }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$name: timed out")
            // Not a CancellationException: the caller treats it as a failed step, not as its own cancellation.
            throw TimedOut(name)
        } catch (e: Exception) {
            Log.w(TAG, "$name: failed, ${e.javaClass.simpleName}")
            throw e
        }
    }
}
