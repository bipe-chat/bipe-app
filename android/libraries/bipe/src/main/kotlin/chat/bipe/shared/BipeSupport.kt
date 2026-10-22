// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import androidx.annotation.StringRes
import kotlin.coroutines.cancellation.CancellationException

/** Looks up Bipe's Android strings, so the logic in this module does not hold a [Context]. */
interface BipeStrings {
    fun getString(@StringRes id: Int, vararg args: Any): String

    companion object {
        fun from(context: Context): BipeStrings = ContextStrings(context.applicationContext)
    }
}

private class ContextStrings(private val context: Context) : BipeStrings {
    override fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)
}

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

