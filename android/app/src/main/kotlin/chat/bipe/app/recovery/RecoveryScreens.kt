// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.signup.BipeField
import chat.bipe.app.signup.FieldFooter
import chat.bipe.app.signup.PrimaryButton
import chat.bipe.app.signup.StepLayout
import chat.bipe.app.signup.TextButtonRow
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeRecoveryPurpose
import chat.bipe.shared.generated.BipeOnboardingRecoveryRow
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen

/** "Já tenho conta": recovery-start and the steps it opens. */
@Composable
fun RecoveryFlowScreen(model: RecoveryFlowModel, onClose: () -> Unit) {
    val path by model.path.collectAsState()
    BackHandler { if (!model.back()) onClose() }
    Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            when (path.lastOrNull()) {
                null -> RecoveryStartScreen(model, onClose)
                RecoveryFlowModel.Route.PIN -> RecoveryPinScreen(model)
                RecoveryFlowModel.Route.EMAIL -> RecoveryEmailScreen(model)
                RecoveryFlowModel.Route.NEW_PIN -> RecoveryNewPinScreen(model, confirm = false)
                RecoveryFlowModel.Route.NEW_PIN_CONFIRM -> RecoveryNewPinScreen(model, confirm = true)
                RecoveryFlowModel.Route.BETA -> RecoveryBetaScreen(model)
            }
        }
    }
}

/** The digit field of the PIN steps: the system digit keyboard behind a row of dots. */
@Composable
internal fun PinField(pin: String, onChange: (String) -> Unit) {
    val colors = BipeTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = pin,
        onValueChange = onChange,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.surfaceBase),
        cursorBrush = SolidColor(colors.surfaceBase),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Box {
                Box(Modifier.size(1.dp)) { inner() }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    repeat(BipeRegistries.PIN_LENGTH) { index ->
                        Box(Modifier.size(14.dp).background(if (index < pin.length) colors.accentFill else colors.surfaceSunken, CircleShape))
                    }
                }
            }
        },
    )
}

// recovery-start

@Composable
private fun RecoveryStartScreen(model: RecoveryFlowModel, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val hasSyncedToken by model.hasSyncedToken.collectAsState()
    LaunchedEffect(Unit) { model.appear() }
    StepLayout(BipeScreen.RECOVERY_START, stringResource(R.string.bipe_recovery_title)) {
        // Read so that the token row shows once appear found a token.
        hasSyncedToken.let { }
        model.rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) {
                        when (row) {
                            BipeOnboardingRecoveryRow.TOKEN -> model.chooseToken()
                            BipeOnboardingRecoveryRow.EMAIL -> model.chooseEmail()
                            BipeOnboardingRecoveryRow.BETA -> model.chooseBeta()
                            BipeOnboardingRecoveryRow.OLD_PHONE -> Unit
                        }
                    }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(row.title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                val body = if (row == BipeOnboardingRecoveryRow.EMAIL) {
                    stringResource(row.body, BipeRegistries.RECOVERY_DELAY_HOURS_EMAIL.toString())
                } else {
                    stringResource(row.body)
                }
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
        TextButtonRow(stringResource(R.string.bipe_app_close), onClick = onClose)
    }
}

// recovery-pin

@Composable
private fun RecoveryPinScreen(model: RecoveryFlowModel) {
    val factor by model.factor.collectAsState()
    val pin by model.pin.collectAsState()
    val phase by model.pinPhase.collectAsState()
    val triesLeft by model.triesLeft.collectAsState()
    val error by model.pinError.collectAsState()
    val blocked = phase == RecoveryFlowModel.PinPhase.BLOCKED
    StepLayout(
        BipeScreen.RECOVERY_PIN,
        stringResource(if (blocked) R.string.bipe_pin_blocked_title else R.string.bipe_pin_enter_title),
        if (factor == RecoveryFlowModel.Factor.Token) stringResource(R.string.bipe_recovery_token_found) else null,
    ) {
        if (blocked) {
            Text(
                stringResource(R.string.bipe_pin_blocked_body, BipeRegistries.PIN_MAX_WRONG_TRIES.toString()),
                style = MaterialTheme.typography.bodyLarge,
                color = BipeTheme.colors.inkPrimary,
            )
        } else {
            PinField(pin, model::editPin)
            if (phase == RecoveryFlowModel.PinPhase.CHECKING) Progress()
            val current = error
            if (phase == RecoveryFlowModel.PinPhase.WRONG) {
                FieldFooter(stringResource(R.string.bipe_pin_wrong, triesLeft.toString()), isError = true)
            } else if (current != null) {
                FieldFooter(current, isError = true)
            }
            FieldFooter(stringResource(R.string.bipe_pin_never_asked))
        }
        TextButtonRow(stringResource(R.string.bipe_pin_forgot), onClick = model::forgotPin)
    }
}

@Composable
private fun RecoveryNewPinScreen(model: RecoveryFlowModel, confirm: Boolean) {
    val pin by model.newPin.collectAsState()
    val problem by model.newPinProblem.collectAsState()
    val phase by model.pinPhase.collectAsState()
    val error by model.pinError.collectAsState()
    StepLayout(
        BipeScreen.RECOVERY_PIN,
        stringResource(if (confirm) R.string.bipe_pin_confirm_title else R.string.bipe_recovery_new_pin_title),
        if (confirm) null else stringResource(R.string.bipe_pin_create_body, BipeRegistries.PIN_LENGTH.toString()),
    ) {
        PinField(pin) { model.editNewPin(it, confirm) }
        problem?.let { FieldFooter(it, isError = true) }
        val current = error
        if (phase == RecoveryFlowModel.PinPhase.CHECKING) {
            Progress()
        } else if (current != null) {
            FieldFooter(current, isError = true)
        }
    }
}

// recovery-email

@Composable
private fun RecoveryEmailScreen(model: RecoveryFlowModel) {
    val email by model.email.collectAsState()
    val phase by model.emailPhase.collectAsState()
    val invalid by model.emailInvalid.collectAsState()
    val error by model.emailError.collectAsState()
    StepLayout(
        BipeScreen.RECOVERY_EMAIL,
        stringResource(R.string.bipe_recovery_email),
        stringResource(R.string.bipe_recovery_email_body, BipeRegistries.RECOVERY_DELAY_HOURS_EMAIL.toString()),
    ) {
        if (phase == RecoveryFlowModel.EmailPhase.SENT) {
            Text(stringResource(R.string.bipe_recovery_email_sent), style = MaterialTheme.typography.bodyLarge, color = BipeTheme.colors.inkPrimary)
        } else {
            BipeField(stringResource(R.string.bipe_recovery_email_field), email, model::editEmail, KeyboardType.Email)
            val current = error
            if (invalid) {
                FieldFooter(stringResource(R.string.bipe_signup_email_invalid), isError = true)
            } else if (current != null) {
                FieldFooter(current, isError = true)
            }
            PrimaryButton(stringResource(R.string.bipe_app_recovery_email_send), busy = phase == RecoveryFlowModel.EmailPhase.SENDING, onClick = model::send)
        }
    }
}

// beta sign-in

@Composable
private fun RecoveryBetaScreen(model: RecoveryFlowModel) {
    val colors = BipeTheme.colors
    val email by model.betaEmail.collectAsState()
    val password by model.betaPassword.collectAsState()
    val busy by model.betaBusy.collectAsState()
    val error by model.betaError.collectAsState()
    StepLayout(BipeScreen.RECOVERY_START, stringResource(R.string.bipe_recovery_beta), stringResource(R.string.bipe_recovery_beta_body)) {
        BipeField(stringResource(R.string.bipe_signup_email), email, model::editBetaEmail, KeyboardType.Email)
        val label = stringResource(R.string.bipe_recovery_beta_password)
        BasicTextField(
            value = password,
            onValueChange = model::editBetaPassword,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
            cursorBrush = SolidColor(colors.accentFill),
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (password.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                    inner()
                }
            },
        )
        error?.let { FieldFooter(it, isError = true) }
        PrimaryButton(
            stringResource(R.string.bipe_action_continue),
            enabled = email.isNotEmpty() && password.isNotEmpty(),
            busy = busy,
            onClick = model::signInBeta,
        )
    }
}

@Composable
private fun Progress() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BipeTheme.colors.accentFill, modifier = Modifier.size(28.dp))
    }
}

// recovery-waiting

/** Spec recovery-waiting. [foregroundCount] changes each time the app comes to the foreground. */
@Composable
fun RecoveryWaitingScreen(model: RecoveryWaitingModel, foregroundCount: Int) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val phase by model.phase.collectAsState()
    val purpose by model.purpose.collectAsState()
    val readyAt by model.readyAt.collectAsState()
    LaunchedEffect(foregroundCount) { model.appear() }
    val date = DateUtils.formatDateTime(context, readyAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
    val access = purpose == BipeRecoveryPurpose.ACCESS
    val message = when (phase) {
        RecoveryWaitingModel.Phase.READY -> stringResource(if (access) R.string.bipe_recovery_ready else R.string.bipe_recovery_pin_ready)
        RecoveryWaitingModel.Phase.CANCELLED -> stringResource(R.string.bipe_recovery_cancelled)
        RecoveryWaitingModel.Phase.EXPIRED -> stringResource(R.string.bipe_error_recovery_invalid)
        RecoveryWaitingModel.Phase.FAILED -> stringResource(R.string.bipe_error_network)
        else -> stringResource(if (access) R.string.bipe_recovery_waiting_body else R.string.bipe_recovery_waiting_pin_body, date)
    }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_WAITING.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
            Text(
                stringResource(R.string.bipe_recovery_waiting_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            if (phase == RecoveryWaitingModel.Phase.READY && access) {
                FieldFooter(stringResource(R.string.bipe_recovery_history_note))
            }
            if (phase == RecoveryWaitingModel.Phase.CLAIMING) CircularProgressIndicator(color = colors.accentFill)
            Spacer(Modifier.weight(1f))
            when (phase) {
                RecoveryWaitingModel.Phase.CANCELLED, RecoveryWaitingModel.Phase.EXPIRED ->
                    PrimaryButton(stringResource(R.string.bipe_app_retry), onClick = model::startOver)
                RecoveryWaitingModel.Phase.FAILED -> PrimaryButton(stringResource(R.string.bipe_app_retry), onClick = model::retry)
                else -> Unit
            }
        }
    }
}

// recovery-alert

/** Spec recovery-alert: full screen over chat-list while a recovery of this account waits. */
@Composable
fun RecoveryAlertScreen(model: RecoveryAlertModel) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val phase by model.phase.collectAsState()
    val notice by model.notice.collectAsState()
    BackHandler { model.close() }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_ALERT.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.bipe_recovery_alert_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            if (phase == RecoveryAlertModel.Phase.CANCELLED) {
                Text(stringResource(R.string.bipe_recovery_alert_cancelled), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                Spacer(Modifier.weight(1f))
                PrimaryButton(stringResource(R.string.bipe_action_continue), onClick = model::close)
            } else {
                Text(notice?.let(model::alertMessage).orEmpty(), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                if (phase == RecoveryAlertModel.Phase.FAILED) FieldFooter(stringResource(R.string.bipe_error_generic), isError = true)
                Spacer(Modifier.weight(1f))
                if (notice?.let(model::canBeCancelled) == true) {
                    val enabled = phase != RecoveryAlertModel.Phase.CANCELLING
                    Text(
                        stringResource(R.string.bipe_recovery_alert_cancel),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.trustDangerInk,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.trustDangerWash, RoundedCornerShape(14.dp))
                            .clickable(enabled = enabled, role = Role.Button) { model.cancel(context) }
                            .padding(vertical = 14.dp),
                    )
                }
                TextButtonRow(stringResource(R.string.bipe_recovery_alert_keep), onClick = model::keep)
            }
        }
    }
}
