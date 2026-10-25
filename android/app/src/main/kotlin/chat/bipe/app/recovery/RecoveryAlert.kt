// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.DeletionDates
import chat.bipe.app.settings.OwnerCheck
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAccountDeletion
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeRecovery
import chat.bipe.shared.api.BipeRecoveryMethod
import chat.bipe.shared.api.BipeRecoveryPurpose
import chat.bipe.shared.api.BipeRecoveryState
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec recovery-alert (bipe-shared product/screens/recovery-alert.json): on a signed-in phone, a full alert while someone
 * gets back into this account elsewhere, with a cancel; and a notice after a sign-in with the recovery token.
 * Spec deletion-alert (ADR 0140): the same alert while a deletion asked on another device waits. A recovery alert goes first.
 */
class RecoveryAlertModel(
    context: Context,
    private val api: BipeApiClient,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    enum class Phase { SHOWN, CANCELLING, CANCELLED, FAILED }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dismissedKey = "dismissed.${session.userId}"
    private val sinceKey = "since.${session.userId}"

    private val _notice = MutableStateFlow<BipeRecovery?>(null)
    val notice: StateFlow<BipeRecovery?> = _notice.asStateFlow()
    private val _deletion = MutableStateFlow<BipeAccountDeletion?>(null)
    val deletion: StateFlow<BipeAccountDeletion?> = _deletion.asStateFlow()
    private val appContext = context.applicationContext
    private val _phase = MutableStateFlow(Phase.SHOWN)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Action check: on launch and on foreground, from GET /v1/me/account. */
    fun check() {
        if ((_notice.value != null || _deletion.value != null) && _phase.value != Phase.CANCELLED) return
        scope.launch {
            val status = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.account(token) }
            }.getOrNull() ?: return@launch
            val since = alertsSince()
            val dismissed = dismissedIds()
            // A completed recovery older than this phone's session is this phone's own sign-in, or an old one.
            _notice.value = status.recoveries
                .filter { hasAlert(it) && it.id !in dismissed }
                .filter { it.state == BipeRecoveryState.WAITING || it.createdAt.toEpochMilli() > since }
                .maxByOrNull { it.createdAt }
            DeletionDates.remember(appContext, session.userId, status.deletion?.dueAt)
            _deletion.value = status.deletion?.takeIf { _notice.value == null && !it.fromThisDevice && it.id !in dismissed }
            _phase.value = Phase.SHOWN
        }
    }

    /** Action cancel: for a waiting recovery, the owner check and then the cancel. */
    fun cancel(confirmOwner: suspend () -> Boolean) {
        val pending = _deletion.value
        if (_notice.value == null && pending != null) {
            cancelDeletion(pending, confirmOwner)
            return
        }
        val current = _notice.value ?: return
        if (current.state != BipeRecoveryState.WAITING) return
        scope.launch {
            if (!confirmOwner()) return@launch
            _phase.value = Phase.CANCELLING
            _phase.value = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.cancelRecovery(current.id, token) }
            }.fold({
                dismiss(current.id)
                Phase.CANCELLED
            }, { Phase.FAILED })
        }
    }

    /**
     * Spec deletion-alert, action cancel: the owner check, then POST /v1/me/deletions/{id}/cancel. A 404 means the request
     * no longer waits. A phone with no lock cancels without the check: a cancel only keeps the account.
     */
    private fun cancelDeletion(pending: BipeAccountDeletion, confirmOwner: suspend () -> Boolean) {
        scope.launch {
            if (OwnerCheck.canCheckOwner(appContext) && !confirmOwner()) return@launch
            _phase.value = Phase.CANCELLING
            val result = runCatching {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { token -> api.cancelDeletion(pending.id, token) }
            }
            val gone = (result.exceptionOrNull() as? BipeApiException.Server)?.code == BipeApiErrorCode.NOT_FOUND
            _phase.value = if (result.isSuccess || gone) {
                DeletionDates.remember(appContext, session.userId, null)
                dismiss(pending.id)
                Phase.CANCELLED
            } else {
                Phase.FAILED
            }
        }
    }

    /** Action keep: it was me. Dismisses the notice on this phone; the recovery or the deletion goes on. */
    fun keep() {
        val current = _notice.value
        if (current != null) {
            dismiss(current.id)
            _notice.value = null
            return
        }
        val pending = _deletion.value ?: return
        dismiss(pending.id)
        _deletion.value = null
    }

    /** Closes the cancelled message. */
    fun close() {
        _notice.value = null
        _deletion.value = null
        _phase.value = Phase.SHOWN
    }

    private fun dismissedIds(): Set<String> = prefs.getStringSet(dismissedKey, emptySet()).orEmpty()

    private fun dismiss(id: String) {
        prefs.edit().putStringSet(dismissedKey, dismissedIds() + id).apply()
    }

    /** The first check of this account on this phone. */
    private fun alertsSince(): Long {
        val stored = prefs.getLong(sinceKey, 0)
        if (stored > 0) return stored
        val now = System.currentTimeMillis()
        prefs.edit().putLong(sinceKey, now).apply()
        return now
    }

    private companion object {
        const val PREFS = "recovery-alert"

        /** A waiting recovery can be cancelled; a completed token recovery is a notice only. */
        fun hasAlert(recovery: BipeRecovery): Boolean = when (recovery.state) {
            BipeRecoveryState.WAITING -> true
            BipeRecoveryState.COMPLETED -> recovery.purpose == BipeRecoveryPurpose.ACCESS && recovery.method == BipeRecoveryMethod.TOKEN
            else -> false
        }
    }
}

/** The alert text per method and purpose. Each takes readyAt formatted by the system. */
private fun alertMessage(context: Context, recovery: BipeRecovery): String {
    val date = DateUtils.formatDateTime(
        context,
        (recovery.readyAt ?: recovery.createdAt).toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    return when {
        recovery.state == BipeRecoveryState.COMPLETED -> context.getString(R.string.bipe_recovery_alert_token)
        recovery.purpose == BipeRecoveryPurpose.PIN_RESET -> context.getString(R.string.bipe_recovery_alert_pin_reset, date)
        recovery.method == BipeRecoveryMethod.GUARDIAN -> context.getString(R.string.bipe_recovery_alert_guardian, date)
        recovery.method == BipeRecoveryMethod.CPF -> context.getString(R.string.bipe_recovery_alert_cpf, date)
        else -> context.getString(R.string.bipe_recovery_alert_email, date)
    }
}

/** The alert covers the screen while a recovery waits: it is not a banner the person can miss. */
@Composable
fun RecoveryAlertScreen(model: RecoveryAlertModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    val notice by model.notice.collectAsState()
    val pendingDeletion by model.deletion.collectAsState()
    val phase by model.phase.collectAsState()
    val recovery = notice
    val deletion = pendingDeletion.takeIf { recovery == null }
    if (recovery == null && deletion == null) return
    val title = stringResource(if (deletion != null) R.string.bipe_deletion_alert_title else R.string.bipe_recovery_alert_title)
    val ownerTitle = if (deletion != null) stringResource(R.string.bipe_deletion_cancel_reason) else title
    BackHandler { if (phase == RecoveryAlertModel.Phase.CANCELLED) model.close() }
    val screen = if (deletion != null) BipeScreen.DELETION_ALERT else BipeScreen.RECOVERY_ALERT
    Surface(modifier = Modifier.fillMaxSize().testTag(screen.id), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            if (phase == RecoveryAlertModel.Phase.CANCELLED) {
                Text(stringResource(if (deletion != null) R.string.bipe_deletion_cancelled else R.string.bipe_recovery_alert_cancelled), color = colors.inkPrimary)
                Spacer(Modifier.weight(1f))
                Button(onClick = model::close, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_action_continue))
                }
                return@Column
            }
            val message = if (recovery != null) {
                alertMessage(context, recovery)
            } else {
                stringResource(R.string.bipe_deletion_alert_body, DeletionDates.text(context, deletion!!.dueAt))
            }
            Text(message, color = colors.inkPrimary)
            if (phase == RecoveryAlertModel.Phase.FAILED) {
                Text(stringResource(R.string.bipe_error_network), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
            }
            Spacer(Modifier.weight(1f))
            if (deletion != null || recovery?.state == BipeRecoveryState.WAITING) {
                Button(
                    onClick = { model.cancel { OwnerCheck.owner(context, ownerTitle) } },
                    enabled = phase != RecoveryAlertModel.Phase.CANCELLING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bipe_recovery_alert_cancel))
                }
                if (phase == RecoveryAlertModel.Phase.CANCELLING) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentFill)
                    }
                }
            }
            TextButton(onClick = model::keep, enabled = phase != RecoveryAlertModel.Phase.CANCELLING, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_recovery_alert_keep), color = colors.accentInk)
            }
        }
    }
}
