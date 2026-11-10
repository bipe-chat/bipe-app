// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.recovery.RecoveryAlertModel
import chat.bipe.app.recovery.RecoveryEmailAddScreen
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.api.BipeAccountStatus
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeRecoveryStarted
import chat.bipe.shared.api.BipeRecoveryState
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The account behind a Bipe PIN pad: reads the ways out and runs the reset on this phone (spec account-pin-check). */
class PinAccount(val session: AccountSession, val api: BipeApiClient) {
    val userId: String get() = session.userId

    suspend fun <T> authorized(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)

    /** GET /v1/me/account. */
    suspend fun read(): BipeAccountStatus = authorized { api.account(it) }
}

fun AccountSession.pinAccount(api: BipeApiClient): PinAccount = PinAccount(this, api)

/**
 * The ways out of a forgotten Bipe PIN (ADR 0283 rules 12 to 14). [phone]: this phone has strong biometrics for the
 * owner check (security.md rule 14). A linked Apple or Google account never resets the PIN (ADR 0096).
 */
data class PinWaysOut(val phone: Boolean, val email: String?) {
    val hasNone: Boolean get() = !phone && email == null
}

/**
 * A PIN reset that this phone started (POST /v1/me/pin/reset, ADR 0283 rule 13). The claim secret stays in this phone's
 * vault (Android Keystore), never synced. The claim applies the new PIN after the wait.
 */
object PinResetKeeper {
    sealed interface Outcome {
        data class Waiting(val readyAt: Instant) : Outcome
        data object Done : Outcome
        data object Cancelled : Outcome
        data object Expired : Outcome
    }

    suspend fun kept(context: Context, userId: String): BipeRecoveryStarted? =
        BipeAccountVault(context.applicationContext).read().pinResets[userId]

    /** Sends the new PIN and keeps the reset. This phone shows no recovery alert for its own reset. */
    suspend fun start(context: Context, account: PinAccount, newPin: String): BipeRecoveryStarted {
        val started = account.authorized { account.api.startPinReset(newPin, it) }
        BipeAccountVault(context.applicationContext).savePinReset(account.userId, started)
        RecoveryAlertModel.dismissOnThisPhone(context, account.userId, listOf(started.recoveryId))
        return started
    }

    /** Claims the kept reset. Null when nothing is kept or the server cannot answer now. */
    suspend fun claim(context: Context, userId: String, api: BipeApiClient): Outcome? {
        val vault = BipeAccountVault(context.applicationContext)
        val kept = vault.read().pinResets[userId] ?: return null
        val outcome = try {
            val claim = api.claimRecovery(kept.recoveryId, kept.claimSecret)
            when (claim.state) {
                BipeRecoveryState.WAITING -> return Outcome.Waiting(claim.readyAt ?: kept.readyAt)
                BipeRecoveryState.COMPLETED -> Outcome.Done
                BipeRecoveryState.CANCELLED -> Outcome.Cancelled
                BipeRecoveryState.EXPIRED -> Outcome.Expired
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BipeApiException) {
            if ((e as? BipeApiException.Server)?.code != BipeApiErrorCode.RECOVERY_INVALID) return null
            Outcome.Expired
        }
        vault.deletePinReset(userId)
        return outcome
    }

    /** POST /v1/me/recoveries/{id}/cancel. A 404 means the reset no longer waits. */
    suspend fun cancel(context: Context, account: PinAccount) {
        val vault = BipeAccountVault(context.applicationContext)
        val kept = vault.read().pinResets[account.userId] ?: return
        try {
            account.authorized { account.api.cancelRecovery(kept.recoveryId, it) }
        } catch (e: BipeApiException.Server) {
            if (e.code != BipeApiErrorCode.NOT_FOUND) throw e
        }
        vault.deletePinReset(account.userId)
    }
}

private sealed interface ForgotPhase {
    data object Loading : ForgotPhase
    data class Ways(val ways: PinWaysOut) : ForgotPhase
    data object NewPin : ForgotPhase
    data object Sending : ForgotPhase
    data class Waiting(val readyAt: Instant, val cancelling: Boolean = false) : ForgotPhase
    data class Done(val hasEmail: Boolean) : ForgotPhase
    data object Cancelled : ForgotPhase
    data object Expired : ForgotPhase
    data class Failed(val text: String) : ForgotPhase
}

/**
 * The forgot sheet of a Bipe PIN pad (spec account-pin-check forgot, resetHere, claim, cancelReset, addEmail): only the
 * ways out that the account has, and the reset on this phone. After the reset, an account with no recovery email gets
 * recovery-email-add.
 */
@Composable
fun PinForgotDialog(account: PinAccount, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val setup = remember { PinSetupState() }
    var phase by remember { mutableStateOf<ForgotPhase>(ForgotPhase.Loading) }
    var showsEmail by remember { mutableStateOf(false) }

    fun errorText(e: BipeApiException): String = when {
        e is BipeApiException.Network -> context.getString(R.string.bipe_error_network)
        e is BipeApiException.Server && e.code == BipeApiErrorCode.RATE_LIMITED -> context.getString(R.string.bipe_error_rate_limited)
        else -> context.getString(R.string.bipe_error_generic)
    }

    suspend fun show(outcome: PinResetKeeper.Outcome) {
        phase = when (outcome) {
            is PinResetKeeper.Outcome.Waiting -> ForgotPhase.Waiting(outcome.readyAt)
            PinResetKeeper.Outcome.Cancelled -> ForgotPhase.Cancelled
            PinResetKeeper.Outcome.Expired -> ForgotPhase.Expired
            PinResetKeeper.Outcome.Done -> {
                view.bipeHaptic(BipeHaptic.Success)
                ForgotPhase.Done(runCatching { account.read() }.getOrNull()?.email != null)
            }
        }
    }

    // Action forgot: a kept reset first (claim), else the ways out from GET /v1/me/account.
    LaunchedEffect(Unit) {
        val outcome = if (PinResetKeeper.kept(context, account.userId) != null) PinResetKeeper.claim(context, account.userId, account.api) else null
        if (outcome != null) {
            show(outcome)
            return@LaunchedEffect
        }
        phase = try {
            val status = account.read()
            ForgotPhase.Ways(PinWaysOut(OwnerCheck.approvalMethod(context) == OwnerCheck.ApprovalMethod.BIOMETRICS, status.email))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BipeApiException) {
            ForgotPhase.Failed(errorText(e))
        }
    }

    fun send(pin: String) {
        phase = ForgotPhase.Sending
        scope.launch {
            phase = try {
                val started = PinResetKeeper.start(context, account, pin)
                view.bipeHaptic(BipeHaptic.Success)
                ForgotPhase.Waiting(started.readyAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BipeApiException) {
                view.bipeHaptic(BipeHaptic.Error)
                val code = (e as? BipeApiException.Server)?.code
                when (code) {
                    BipeApiErrorCode.PIN_WEAK -> {
                        setup.reset(e.localizedMessage(context))
                        ForgotPhase.NewPin
                    }
                    BipeApiErrorCode.PIN_RESET_PENDING -> ForgotPhase.Failed(context.getString(R.string.bipe_pin_reset_pending))
                    else -> ForgotPhase.Failed(errorText(e))
                }
            }
        }
    }

    val current = phase
    Dialog(onDismissRequest = { if (current != ForgotPhase.Sending) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(enabled = current != ForgotPhase.Sending, onBack = onClose)
        if (showsEmail) {
            RecoveryEmailAddScreen(account.api, account.session, current = null, pending = null, pinSet = true, onSent = {}, onClose = onClose)
            return@Dialog
        }
        if (current == ForgotPhase.NewPin || current == ForgotPhase.Sending) {
            PinPad(
                title = stringResource(if (setup.step == PinSetupState.Step.CREATE) R.string.bipe_recovery_new_pin_title else R.string.bipe_pin_confirm_title),
                pin = setup.pin,
                message = setup.message(context),
                error = setup.problem,
                isBusy = current == ForgotPhase.Sending,
                onDigit = { digit -> setup.type(context, view, digit, ::send) },
                onDelete = setup::delete,
            )
            return@Dialog
        }
        Surface(Modifier.fillMaxSize(), color = colors.surfaceBase) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.bipe_pin_forgot_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
                val line: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary) }
                when (current) {
                    ForgotPhase.Loading, ForgotPhase.NewPin, ForgotPhase.Sending -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp))
                    }
                    is ForgotPhase.Ways -> {
                        if (current.ways.phone) {
                            line(stringResource(R.string.bipe_pin_forgot_phone, BipeRegistries.RECOVERY_DELAY_HOURS_EMAIL.toString()))
                            val reason = stringResource(R.string.bipe_pin_reset_biometric)
                            val cancel = stringResource(R.string.bipe_app_cancel)
                            BipeButton(
                                onClick = {
                                    // Action resetHere: strong biometrics only, never the screen lock (security.md rule 14).
                                    scope.launch {
                                        if (OwnerCheck.strongBiometrics(context, reason, cancel)) {
                                            setup.reset()
                                            phase = ForgotPhase.NewPin
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.bipe_pin_forgot_phone_action)) }
                        }
                        current.ways.email?.let { line(stringResource(R.string.bipe_pin_forgot_email, it, BipeRegistries.RECOVERY_DELAY_HOURS_EMAIL.toString())) }
                        if (current.ways.hasNone) line(stringResource(R.string.bipe_pin_forgot_none))
                    }
                    is ForgotPhase.Waiting -> {
                        Text(stringResource(R.string.bipe_recovery_waiting_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                        val date = DateUtils.formatDateTime(context, current.readyAt.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
                        line(stringResource(R.string.bipe_recovery_waiting_pin_body, date))
                        BipeOutlinedButton(
                            onClick = {
                                phase = current.copy(cancelling = true)
                                scope.launch {
                                    phase = try {
                                        PinResetKeeper.cancel(context, account)
                                        ForgotPhase.Cancelled
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: BipeApiException) {
                                        ForgotPhase.Failed(errorText(e))
                                    }
                                }
                            },
                            enabled = !current.cancelling,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.bipe_pin_reset_cancel)) }
                    }
                    is ForgotPhase.Done -> {
                        line(stringResource(R.string.bipe_recovery_pin_ready))
                        if (!current.hasEmail) {
                            line(stringResource(R.string.bipe_pin_reset_add_email))
                            BipeButton(onClick = { showsEmail = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_fallback_email)) }
                        }
                    }
                    ForgotPhase.Cancelled -> line(stringResource(R.string.bipe_pin_reset_cancelled))
                    ForgotPhase.Expired -> line(stringResource(R.string.bipe_pin_reset_expired))
                    is ForgotPhase.Failed -> Text(current.text, style = MaterialTheme.typography.bodyLarge, color = colors.trustDangerInk)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_close), color = colors.accentInk)
                }
            }
        }
    }
}
