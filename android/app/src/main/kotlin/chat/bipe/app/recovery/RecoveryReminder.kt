// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.settings.AppLockModel
import chat.bipe.app.settings.AppLockPinCheck
import chat.bipe.app.settings.OwnerCheck
import chat.bipe.shared.BipeSignupRules
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeRecoveryReminder
import chat.bipe.shared.account.setRecoveryEmail
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.BipeAccountWays
import chat.bipe.shared.settings.accountWays
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec recovery-reminder (bipe-chat/bipe#531): the card above the chat list for an account without a way back that
 * survives the App Store transfer. The rule is the server's (independent_recovery); timing is BipeRecoveryReminder.
 * Android has no Team ID problem; it shows the same card so a move to an iPhone keeps a way back (ADR 0031).
 */
class RecoveryReminderModel(
    context: Context,
    val api: BipeApiClient,
    val session: AccountSession,
    private val scope: CoroutineScope,
) {
    data class State(
        /** Null until GET /v1/me/account answers. */
        val account: BipeAccountWays? = null,
        val snoozedUntil: Instant? = null,
    )

    private val preferences = context.getSharedPreferences("bipe.recoveryReminder", Context.MODE_PRIVATE)
    private val sinceKey = "since.${session.userId}"
    private val snoozeKey = "snoozedUntil.${session.userId}"
    private val countKey = "count.${session.userId}"
    private val _state = MutableStateFlow(
        State(snoozedUntil = preferences.getLong(snoozeKey, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // "Came to this phone": the first time this account's chat list opened here.
        if (!preferences.contains(sinceKey)) preferences.edit().putLong(sinceKey, System.currentTimeMillis()).apply()
    }

    /** State visible. Unknown account state (offline, older server) shows no card. */
    fun showsCard(state: State): Boolean {
        if (state.account?.independentRecovery != false) return false
        val since = Instant.ofEpochMilli(preferences.getLong(sinceKey, System.currentTimeMillis()))
        return BipeRecoveryReminder.isDue(Instant.now(), since, state.snoozedUntil)
    }

    /** Reads the account state. Runs on open and when the app comes back, so the card goes after the link is opened. */
    fun load() {
        scope.launch {
            val value = runCatching { BipeApiClient.withTokenRetry({ session.accessToken() }) { api.accountWays(it) } }.getOrNull()
                ?: return@launch
            _state.value = _state.value.copy(account = value)
        }
    }

    /** Action dismiss: "Agora não" hides the card for the next snooze. */
    fun dismiss() {
        val count = preferences.getInt(countKey, 0)
        val until = BipeRecoveryReminder.snoozedUntil(Instant.now(), count)
        preferences.edit().putInt(countKey, count + 1).putLong(snoozeKey, until.toEpochMilli()).apply()
        _state.value = _state.value.copy(snoozedUntil = until)
    }
}

/** The card (registries/onboarding.json prompts.recovery). Same shape as the handle prompt card. */
@Composable
fun RecoveryReminderCard(model: RecoveryReminderModel, syncOff: Boolean = false, addEmail: () -> Unit) {
    val colors = BipeTheme.colors
    val prompt = BipeOnboardingPrompt.RECOVERY
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(BipeScreen.RECOVERY_REMINDER.id)
            .background(colors.accentWash, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(prompt.title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        prompt.body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        // State syncOff (bipe-chat/bipe#202): the synced store does not back the recovery token up from this phone.
        if (syncOff) Text(stringResource(R.string.bipe_fallback_sync_off_android), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(prompt.primary.title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.bipeTapTarget().background(colors.accentFill, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = addEmail)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            prompt.text.forEach {
                Text(
                    stringResource(it.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accentInk,
                    modifier = Modifier.bipeTapTarget().clickable(role = Role.Button, onClick = model::dismiss),
                )
            }
        }
    }
}

/** Spec recovery-email-add, phase. PIN_CHECK: the app lock PIN on a phone with no screen lock. */
private enum class EmailAddPhase { CONFIRM_IDENTITY, PIN_CHECK, EDITING, SENDING, SENT, FAILED }

/**
 * Spec recovery-email-add: add or change the recovery e-mail on a signed-in phone, after the owner check.
 * [current] and [pending] come from GET /v1/me/account; [onSent] reloads it after 202.
 */
@Composable
fun RecoveryEmailAddScreen(
    api: BipeApiClient,
    session: AccountSession,
    current: String?,
    pending: String?,
    onSent: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lock = remember { AppLockModel.get(context) }
    var phase by remember { mutableStateOf(EmailAddPhase.CONFIRM_IDENTITY) }
    var email by remember { mutableStateOf(pending ?: current ?: "") }
    var emailInvalid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var sentTo by remember { mutableStateOf("") }
    val reason = stringResource(R.string.bipe_email_change_reason)
    BackHandler(enabled = phase != EmailAddPhase.SENDING, onBack = onClose)

    // Action confirmIdentity (as pin-change): the owner check, or the app lock PIN on a phone with no screen lock when
    // the lock is on; else the screen goes on.
    LaunchedEffect(Unit) {
        when {
            OwnerCheck.canCheckOwner(context) -> if (OwnerCheck.owner(context, reason)) phase = EmailAddPhase.EDITING else onClose()
            lock.store.isOn -> phase = EmailAddPhase.PIN_CHECK
            else -> phase = EmailAddPhase.EDITING
        }
    }

    // Action send: PUT /v1/me/email. The address is never logged.
    fun send() {
        val address = email.trim()
        if (!BipeSignupRules.isPlausibleEmail(address)) {
            emailInvalid = true
            return
        }
        phase = EmailAddPhase.SENDING
        scope.launch {
            try {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { api.setRecoveryEmail(address, it) }
                view.bipeHaptic(BipeHaptic.Success)
                sentTo = address
                phase = EmailAddPhase.SENT
                onSent()
            } catch (failure: BipeApiException) {
                val code = (failure as? BipeApiException.Server)?.code
                if (code == BipeApiErrorCode.BAD_REQUEST) {
                    emailInvalid = true
                    phase = EmailAddPhase.EDITING
                } else {
                    error = when {
                        failure is BipeApiException.Network -> R.string.bipe_error_network
                        code == BipeApiErrorCode.EMAIL_TAKEN -> R.string.bipe_error_email_taken
                        code == BipeApiErrorCode.EMAIL_UNAVAILABLE -> R.string.bipe_error_email_unavailable
                        code == BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                        else -> R.string.bipe_error_generic
                    }
                    phase = EmailAddPhase.FAILED
                }
            }
        }
    }

    val colors = BipeTheme.colors
    when (phase) {
        EmailAddPhase.CONFIRM_IDENTITY -> Surface(Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_EMAIL_ADD.id), color = colors.surfaceBase) {}
        EmailAddPhase.PIN_CHECK -> AppLockPinCheck(stringResource(R.string.bipe_email_title), onPass = { phase = EmailAddPhase.EDITING }, onCancel = onClose)
        EmailAddPhase.SENT -> Surface(Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_EMAIL_ADD.id), color = colors.surfaceBase) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    stringResource(R.string.bipe_email_sent, sentTo),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                    textAlign = TextAlign.Center,
                )
                BipeButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
            }
        }
        else -> Surface(Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_EMAIL_ADD.id), color = colors.surfaceBase) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bipe_email_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk, modifier = Modifier.bipeTapTarget().clickable(role = Role.Button, onClick = onClose).padding(8.dp))
                }
                Text(current ?: stringResource(R.string.bipe_account_email_none), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                if (pending != null) Text(stringResource(R.string.bipe_email_pending), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                Text(stringResource(R.string.bipe_signup_email), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary)
                BasicTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        emailInvalid = false
                        if (phase == EmailAddPhase.FAILED) phase = EmailAddPhase.EDITING
                    },
                    enabled = phase != EmailAddPhase.SENDING,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                    cursorBrush = SolidColor(colors.accentFill),
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp),
                )
                if (emailInvalid) Text(stringResource(R.string.bipe_signup_email_invalid), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk)
                if (phase == EmailAddPhase.FAILED) error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
                Text(stringResource(R.string.bipe_email_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                BipeButton(onClick = ::send, enabled = email.isNotBlank() && phase != EmailAddPhase.SENDING, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_email_send))
                }
            }
        }
    }
}
