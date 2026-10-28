// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

/**
 * The app lock try limit (bipe-chat/bipe#777, spec app-lock typeDigit): the first wrong PINs are free, then each wrong
 * PIN starts a wait that grows. The count lives in the lock file, so closing the app does not reset it. A match or a
 * biometric unlock clears it. iOS has the same values (BipeAppLockTries in BipeAppLock.swift).
 */
object BipeAppLockTries {
    /** Wrong PINs in a row before the first wait. */
    const val FREE_TRIES = 5

    /** The wait in milliseconds after wrong PIN [FREE_TRIES], [FREE_TRIES] + 1, ...; the last value repeats. */
    val WAITS: List<Long> = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    /** The wait after this many wrong PINs in a row, or null while tries are free. */
    fun wait(failures: Int): Long? {
        if (failures < FREE_TRIES) return null
        return WAITS[minOf(failures - FREE_TRIES, WAITS.size - 1)]
    }

    /** The end of the wait, or null when it is over or there is none. */
    fun waitEnd(failures: Int, lastFailure: Long, now: Long): Long? {
        val wait = wait(failures) ?: return null
        val end = lastFailure + wait
        return if (end > now) end else null
    }
}
