// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Settings logic of specs settings-root, settings-group, sign-out and data-export (bipe-shared product/screens),
// written from the specs and api/openapi.yaml.

package chat.bipe.shared.settings

import android.content.Context
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeSettingsRow
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Row gates of registries/settings.json (spec settings-group notes): null → shown; ios or android → that platform
 * only; flag:<name> → shown when that build flag is on; planned:<issue> and any unknown gate → hidden (fail closed).
 */
object BipeSettingsGate {
    fun isShown(gate: String?, platform: String = "android", flags: Set<String> = emptySet()): Boolean = when {
        gate == null -> true
        gate == "ios" || gate == "android" -> gate == platform
        gate.startsWith("flag:") -> gate.removePrefix("flag:") in flags
        else -> false
    }
}

fun BipeSettingsRow.isShownOnAndroid(flags: Set<String> = emptySet()): Boolean = BipeSettingsGate.isShown(gate, "android", flags)

/** Contract `MessagingSetting.who_can_message`. */
@Serializable
enum class BipeWhoCanMessage {
    @SerialName("everyone") EVERYONE,
    @SerialName("verified") VERIFIED,
    @SerialName("contacts") CONTACTS,
    ;

    /** The privacy row subtitle of settings-root. */
    val settingsSummary: Int
        get() = when (this) {
            EVERYONE -> R.string.bipe_settings_privacy_summary_everyone
            VERIFIED -> R.string.bipe_settings_privacy_summary_verified
            CONTACTS -> R.string.bipe_settings_privacy_summary_contacts
        }
}

/** One way back into the account after the phone is lost (spec sign-out, type WayBack). */
enum class BipeWayBack(val title: Int) {
    EMAIL(R.string.bipe_email_title),
    LINKED_ACCOUNT(R.string.bipe_linked_accounts_title),
    RECOVERY_KEY(R.string.bipe_account_recovery_key),
    SYNCED_TOKEN(R.string.bipe_app_sign_out_synced_token),
}

/**
 * The ways back of the account (spec settings-root notes): a confirmed recovery email, a linked Apple or Google account
 * (ADR 0096), a recovery key, and the recovery token synced to this phone's platform account.
 */
data class BipeWaysBack(val ways: List<BipeWayBack>) {
    val count: Int get() = ways.size

    /** One way back or none: the only warning mark in Settings (ADR 0112). */
    val needsWarning: Boolean get() = count <= 1

    /** The account row subtitle of settings-root. */
    fun summary(context: Context): String = when (count) {
        0 -> context.getString(R.string.bipe_settings_account_paths_none)
        1 -> context.getString(R.string.bipe_account_paths_warning)
        else -> context.getString(R.string.bipe_settings_account_paths, count.toString())
    }

    /** The status card text of settings-group account. Null with two ways back or more. */
    val warning: Int?
        get() = when (count) {
            0 -> R.string.bipe_fallback_reminder
            1 -> R.string.bipe_account_paths_warning
            else -> null
        }

    companion object {
        fun of(hasEmail: Boolean, hasLinkedAccount: Boolean, hasRecoveryKey: Boolean, hasSyncedToken: Boolean) = BipeWaysBack(
            buildList {
                if (hasEmail) add(BipeWayBack.EMAIL)
                if (hasLinkedAccount) add(BipeWayBack.LINKED_ACCOUNT)
                if (hasRecoveryKey) add(BipeWayBack.RECOVERY_KEY)
                if (hasSyncedToken) add(BipeWayBack.SYNCED_TOKEN)
            },
        )
    }
}

/** The file of GET /v1/me/export (spec data-export). */
object BipeDataExportFile {
    private val dispositionName = Regex("filename=\"?([^\";]+)\"?")
    private val plainName = Regex("bipe-dados-[A-Za-z0-9_-]+\\.json")

    /** The file name from content-disposition when it is a plain bipe-dados-*.json name, else bipe-dados-<today>.json. */
    fun filename(contentDisposition: String?, today: LocalDate = LocalDate.now()): String {
        val name = contentDisposition?.let { dispositionName.find(it)?.groupValues?.get(1) }
        return if (name != null && plainName.matches(name)) name else "bipe-dados-$today.json"
    }
}

/** The fields of GET /v1/me/account that the ways back need. */
@Serializable
data class BipeAccountWays(
    /** The verified recovery email. */
    @SerialName("email") val email: String? = null,
    @SerialName("recovery_token_set") val recoveryTokenSet: Boolean = false,
) {
    override fun toString(): String = "BipeAccountWays"
}

@Serializable
private data class MessagingSetting(@SerialName("who_can_message") val whoCanMessage: BipeWhoCanMessage)

@Serializable
private data class SupportRoom(@SerialName("room_id") val roomId: String)

/** `GET /v1/me/messaging`. */
suspend fun BipeApiClient.whoCanMessage(accessToken: String): BipeWhoCanMessage =
    send("GET", listOf("v1", "me", "messaging"), accessToken = accessToken, response = MessagingSetting.serializer()).whoCanMessage

/** `PUT /v1/me/messaging`. The server enforces it (ADR 0034). */
suspend fun BipeApiClient.setWhoCanMessage(value: BipeWhoCanMessage, accessToken: String): BipeWhoCanMessage = send(
    "PUT",
    listOf("v1", "me", "messaging"),
    body = encode(MessagingSetting.serializer(), MessagingSetting(value)),
    accessToken = accessToken,
    response = MessagingSetting.serializer(),
).whoCanMessage

/** `GET /v1/me/account`, the fields of the ways back. */
suspend fun BipeApiClient.accountWays(accessToken: String): BipeAccountWays =
    send("GET", listOf("v1", "me", "account"), accessToken = accessToken, response = BipeAccountWays.serializer())

/** `POST /v1/me/support`. The room ID of the caller's support room (ADR 0114). */
suspend fun BipeApiClient.supportRoomId(accessToken: String): String =
    send("POST", listOf("v1", "me", "support"), accessToken = accessToken, response = SupportRoom.serializer()).roomId
