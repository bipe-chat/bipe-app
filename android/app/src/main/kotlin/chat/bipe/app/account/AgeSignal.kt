// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import chat.bipe.shared.account.BipeAgeSignalSource
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ADR 0229, bipe-chat/bipe#994: the age comes from the OS, never from a date of birth. After sign-in and on foreground,
 * when the server has no signal or one older than [BipeRegistries.AGE_SIGNAL_RECHECK_DAYS], the app reads the OS and
 * sends the range. The server maps it to a band; the app keeps nothing.
 */
class AgeSignalModel(
    private val api: BipeApiClient,
    private val session: AccountSession,
    private val source: BipeAgeSignalSource,
    private val scope: CoroutineScope,
) {
    private var running: Job? = null

    /** A failed read or send does nothing; the next check tries again. */
    fun check() {
        if (running?.isActive == true) return
        running = scope.launch {
            val status = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.account(token) }
            }.getOrNull() ?: return@launch
            // An older server has no age_band: it has no age route either.
            if (status.ageBand == null) return@launch
            val checked = status.ageCheckedAt
            if (checked != null && Duration.between(checked, Instant.now()) < Duration.ofDays(BipeRegistries.AGE_SIGNAL_RECHECK_DAYS.toLong())) {
                return@launch
            }
            val signal = source.read()
            runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.sendAgeSignal(signal, token) }
            }
        }
    }
}
