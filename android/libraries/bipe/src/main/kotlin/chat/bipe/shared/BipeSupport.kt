// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but catches only [Exception]: an [Error] still crashes, and a
 * [CancellationException] is thrown again so that coroutine cancellation keeps working.
 */
inline fun <T> runCatchingExceptions(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
