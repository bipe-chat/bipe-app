// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import kotlinx.coroutines.flow.Flow

/**
 * The app lock, which is Element's lock screen with an 8-digit PIN (features/lockscreen). The app lock checks the PIN
 * on the device. The lock is off by default: the user turns it on in Sua conta. While the lock is on and the phone
 * holds one account, the app lock PIN and the Bipe PIN are the same PIN: Bipe sets one after it sets the other.
 */
interface BipeAppLock {
    /** Sets the PIN that unlocks the app. A set PIN turns the lock on. */
    suspend fun setPin(pin: String)

    suspend fun isPinSet(): Boolean

    /** `true` while the lock is on (a PIN is set). */
    fun pinSetFlow(): Flow<Boolean>

    /** Counts a wrong PIN like the lock screen does. */
    suspend fun verifyPin(pin: String): Boolean

    /** Turns the lock off: removes the PIN and the biometric unlock. The caller proves the owner first. */
    suspend fun removePin()
}
