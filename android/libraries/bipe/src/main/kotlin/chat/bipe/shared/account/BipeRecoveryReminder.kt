// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeRegistries
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Timing of the recovery-reminder card (bipe-chat/bipe#531, spec recovery-reminder; registries/accounts.json
 * recoveryReminder). The card never stops for good while the account has no Team-ID-independent way back.
 */
object BipeRecoveryReminder {
    /** True when the card may show: the account came to this phone firstAfterDays ago and the snooze has ended. */
    fun isDue(now: Instant, since: Instant, snoozedUntil: Instant?): Boolean {
        val first = since.plus(Duration.ofDays(BipeRegistries.RECOVERY_REMINDER_FIRST_AFTER_DAYS.toLong()))
        return !now.isBefore(first) && (snoozedUntil == null || !now.isBefore(snoozedUntil))
    }

    /** The end of the snooze after the [count]-th "Agora não" (0 for the first). Past the list, the last value repeats. */
    fun snoozedUntil(now: Instant, count: Int): Instant {
        val days = BipeRegistries.RECOVERY_REMINDER_SNOOZE_DAYS
        return now.plus(Duration.ofDays(days[count.coerceIn(0, days.size - 1)].toLong()))
    }
}

@Serializable
private data class RecoveryEmailRequest(@SerialName("email") val email: String, @SerialName("lang") val lang: String) {
    override fun toString(): String = "RecoveryEmailRequest"
}

/** `PUT /v1/me/email` (spec recovery-email-add). The server sends a link to confirm the address. Never logged. */
suspend fun BipeApiClient.setRecoveryEmail(email: String, accessToken: String) {
    val lang = if (Locale.getDefault().language == "pt") "pt" else "en"
    send<Unit>(
        "PUT",
        listOf("v1", "me", "email"),
        body = encode(RecoveryEmailRequest.serializer(), RecoveryEmailRequest(email, lang)),
        accessToken = accessToken,
        response = null,
    )
}
