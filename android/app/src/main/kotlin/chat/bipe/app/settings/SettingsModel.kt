// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAccountDeletion
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeSettingsGroup
import chat.bipe.shared.settings.BipeAccountWays
import chat.bipe.shared.settings.BipeWaysBack
import chat.bipe.shared.settings.BipeWhoCanMessage
import chat.bipe.shared.settings.accountWays
import chat.bipe.shared.settings.setWhoCanMessage
import chat.bipe.shared.settings.whoCanMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.RecoveryState

/**
 * The state that settings-root and settings-group read: who can message, the ways back, the notification permission.
 * One model for the whole settings stack, so a group shows what the root already read.
 */
class SettingsModel(
    private val context: Context,
    val session: AccountSession,
    val api: BipeApiClient,
    private val scope: CoroutineScope,
) {
    data class State(
        val whoCanMessage: BipeWhoCanMessage? = null,
        val account: BipeAccountWays? = null,
        val hasRecoveryKey: Boolean = false,
        val notificationsOn: Boolean? = null,
        /** ADR 0140: the waiting deletion, from GET /v1/me/account. */
        val deletion: BipeAccountDeletion? = null,
        /** ADR 0087: a company account (account-delete shows bipe_deletion_point_company). */
        val isCompany: Boolean = false,
    ) {
        /** Null until GET /v1/me/account answers. */
        val waysBack: BipeWaysBack?
            get() = account?.let {
                BipeWaysBack.of(
                    hasEmail = it.email != null,
                    // Linked Apple or Google accounts come with bipe-chat/bipe#307.
                    hasLinkedAccount = false,
                    hasRecoveryKey = hasRecoveryKey,
                    // Android keeps the recovery token on this phone only: the synced store needs an ADR (bipe-chat/bipe#554).
                    hasSyncedToken = false,
                )
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Spec settings-root, action appear: reads the summaries. Each fills in when it arrives. */
    fun load() {
        _state.update {
            it.copy(
                hasRecoveryKey = runCatching { session.client.encryption().recoveryState() == RecoveryState.ENABLED }
                    .getOrDefault(false),
                notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            )
        }
        scope.launch {
            runCatching { authorized { api.whoCanMessage(it) } }.onSuccess { value -> _state.update { it.copy(whoCanMessage = value) } }
        }
        scope.launch {
            runCatching { authorized { api.accountWays(it) } }.onSuccess { value -> _state.update { it.copy(account = value) } }
        }
        scope.launch {
            runCatching { authorized { api.account(it) } }.onSuccess { value ->
                DeletionDates.remember(context, session.userId, value.deletion?.dueAt)
                _state.update { it.copy(deletion = value.deletion, isCompany = value.accountType == "company") }
            }
        }
    }

    /**
     * Spec account-delete, action cancelPending: POST /v1/me/deletions/{id}/cancel. A 404 means it no longer waits, which
     * is what the person wanted. False when the cancel failed.
     */
    suspend fun cancelDeletion(deletion: BipeAccountDeletion): Boolean {
        val result = runCatching { authorized { api.cancelDeletion(deletion.id, it) } }
        val gone = (result.exceptionOrNull() as? BipeApiException.Server)?.code == BipeApiErrorCode.NOT_FOUND
        if (result.isFailure && !gone) return false
        DeletionDates.remember(context, session.userId, null)
        _state.update { it.copy(deletion = null) }
        load()
        return true
    }

    /** The who-can-message picker: PUT /v1/me/messaging. False when the save failed; the value stays as it was. */
    suspend fun setWhoCanMessage(value: BipeWhoCanMessage): Boolean {
        val saved = runCatching { authorized { api.setWhoCanMessage(value, it) } }.getOrNull() ?: return false
        _state.update { it.copy(whoCanMessage = saved) }
        return true
    }

    /** Runs a bipe-api request with the account's access token, retrying once after a token refresh. */
    suspend fun <T> authorized(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)

    /** The subtitle of a root row. Null when the group has nothing to sum up or the value has not arrived. */
    fun summary(state: State, group: BipeSettingsGroup): String? = when (group) {
        BipeSettingsGroup.PRIVACY -> state.whoCanMessage?.let { context.getString(it.settingsSummary) }
        BipeSettingsGroup.ACCOUNT -> state.waysBack?.summary(context)
        BipeSettingsGroup.NOTIFICATIONS -> state.notificationsOn?.let {
            context.getString(if (it) R.string.bipe_settings_notifications_on else R.string.bipe_settings_notifications_off)
        }
        BipeSettingsGroup.PROFILE, BipeSettingsGroup.CHATS, BipeSettingsGroup.HELP -> null
    }

    fun needsAttention(state: State, group: BipeSettingsGroup): Boolean =
        group == BipeSettingsGroup.ACCOUNT && state.waysBack?.needsWarning == true
}
