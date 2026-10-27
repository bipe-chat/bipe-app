// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import android.text.format.DateUtils
import chat.bipe.shared.BipePhoneNumber
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.InstantSerializer
import chat.bipe.shared.generated.BipeRegistries
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /v1/me/phone`: the state of the caller's own linked number, and the number (ADR 0191). */
@Serializable
data class BipeMyPhone(
    @SerialName("linked") val linked: Boolean = false,
    @SerialName("discoverable_by_phone") val discoverableByPhone: Boolean = false,
    @Serializable(with = InstantSerializer::class) @SerialName("linked_at") val linkedAt: Instant? = null,
    /** After this the number stops being findable until it is confirmed again. */
    @Serializable(with = InstantSerializer::class) @SerialName("confirm_by") val confirmBy: Instant? = null,
    /** The caller's own linked number in E.164 (ADR 0191). Null when not linked or for a link made before the server stored it. */
    @SerialName("number") val number: String? = null,
)

/** `GET /v1/me/phone`. */
suspend fun BipeApiClient.myPhone(accessToken: String): BipeMyPhone =
    send("GET", listOf("v1", "me", "phone"), accessToken = accessToken, response = BipeMyPhone.serializer())

@Serializable
private data class DiscoverySettings(@SerialName("by_phone") val byPhone: Boolean)

/** `PUT /v1/me/discovery`. Same call as BipeKit `setDiscoverableByPhone` on iOS. */
suspend fun BipeApiClient.setDiscoverableByPhone(value: Boolean, accessToken: String) {
    send(
        "PUT",
        listOf("v1", "me", "discovery"),
        body = encode(DiscoverySettings.serializer(), DiscoverySettings(value)),
        accessToken = accessToken,
        response = DiscoverySettings.serializer(),
    )
}

/**
 * Spec phone-link-status (bipe-chat/bipe#470): the state of the linked number and its yearly confirmation. A linked
 * number stays findable until `confirm_by`; the app warns [BipeRegistries.PHONE_LINK_CONFIRM_WARN_DAYS] before it.
 */
sealed class BipePhoneLinkState {
    data object NotLinked : BipePhoneLinkState()

    /** [until] is null for a link without a confirm_by (an older server). */
    data class Linked(val until: Instant?) : BipePhoneLinkState()

    data class ConfirmSoon(val by: Instant) : BipePhoneLinkState()

    data class Expired(val since: Instant) : BipePhoneLinkState()

    /** The caution colour and mark on the row, and the in-app notice. */
    val needsAttention: Boolean
        get() = this is ConfirmSoon || this is Expired

    /** confirm_by, for the once-per-date notice. */
    val confirmBy: Instant?
        get() = when (this) {
            NotLinked -> null
            is Linked -> until
            is ConfirmSoon -> by
            is Expired -> since
        }

    /** The value of the privacy row number. */
    fun summary(context: Context): String = when (this) {
        NotLinked -> context.getString(R.string.bipe_privacy_number_not_linked)
        is Linked -> until?.let { context.getString(R.string.bipe_phone_linked_until, format(context, it)) }
            ?: context.getString(R.string.bipe_privacy_number_linked)
        is ConfirmSoon -> context.getString(R.string.bipe_phone_confirm_soon, format(context, by))
        is Expired -> context.getString(R.string.bipe_phone_confirm_expired)
    }

    /**
     * The value of the privacy row number with the own linked number (ADR 0191, bipe-chat/bipe#787): the number formatted
     * for reading next to the state. Without a number (not linked, or a link made before the server stored it): the state only.
     */
    fun summary(context: Context, number: String?): String {
        if (this == NotLinked || number.isNullOrEmpty()) return summary(context)
        return context.getString(R.string.bipe_privacy_number_value, BipePhoneNumber.display(number), summary(context))
    }

    companion object {
        fun of(phone: BipeMyPhone, now: Instant = Instant.now()): BipePhoneLinkState {
            if (!phone.linked) return NotLinked
            val confirmBy = phone.confirmBy ?: return Linked(null)
            return when {
                !confirmBy.isAfter(now) -> Expired(confirmBy)
                Duration.between(now, confirmBy) <= Duration.ofDays(BipeRegistries.PHONE_LINK_CONFIRM_WARN_DAYS.toLong()) -> ConfirmSoon(confirmBy)
                else -> Linked(confirmBy)
            }
        }

        private fun format(context: Context, instant: Instant): String =
            DateUtils.formatDateTime(context, instant.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
    }
}
