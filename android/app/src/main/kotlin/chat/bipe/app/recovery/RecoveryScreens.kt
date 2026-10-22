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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboardingRecoveryRow
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.api.BipeRecoveryPurpose

/** The recovery flow: recovery-start and the screens it opens. account-start shows it. */
@Composable
fun RecoveryFlowScreen(model: RecoveryFlowModel, onClose: () -> Unit) {
    val route by model.route.collectAsState()
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    BackHandler {
        if (!model.back() && route == RecoveryFlowModel.Route.START) onClose()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) {
        when (route) {
            RecoveryFlowModel.Route.START -> RecoveryStartScreen(model, onClose)
            RecoveryFlowModel.Route.PIN -> RecoveryPinScreen(model)
            RecoveryFlowModel.Route.EMAIL -> RecoveryEmailScreen(model)
            RecoveryFlowModel.Route.BETA -> RecoveryBetaScreen(model)
            RecoveryFlowModel.Route.WAITING -> RecoveryWaitingScreen(model)
        }
    }
}

// Shared pieces

@Composable
private fun RecoveryStep(
    screen: BipeScreen,
    title: String,
    message: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(screen.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_app_cancel), tint = colors.glyphPrimary)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        }
        content()
    }
}

@Composable
private fun FilledButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(title)
    }
}

@Composable
private fun Footer(text: String, isError: Boolean = false) {
    val colors = BipeTheme.colors
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.trustDangerInk else colors.inkSecondary)
}

@Composable
private fun Busy() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BipeTheme.colors.accentFill)
    }
}

// recovery-start

@Composable
private fun RecoveryStartScreen(model: RecoveryFlowModel, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val rows by model.rows.collectAsState()
    RecoveryStep(BipeScreen.RECOVERY_START, stringResource(R.string.bipe_recovery_title), onBack = onClose) {
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, RoundedCornerShape(12.dp))
                    .clickable { model.choose(row) }
                    .padding(16.dp),
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
    }
}

// recovery-pin

@Composable
private fun RecoveryPinScreen(model: RecoveryFlowModel) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val factor by model.factor.collectAsState()
    val pin by model.pin.collectAsState()
    val phase by model.pinPhase.collectAsState()
    val error by model.pinError.collectAsState()
    val recoveryInvalid by model.recoveryInvalid.collectAsState()
    val newPinProblem by model.newPinProblem.collectAsState()
    val newPinMismatch by model.newPinMismatch.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(phase) { if (phase != RecoveryFlowModel.PinPhase.BLOCKED) runCatching { focus.requestFocus() } }

    val title = when (phase) {
        RecoveryFlowModel.PinPhase.BLOCKED -> stringResource(R.string.bipe_pin_blocked_title)
        RecoveryFlowModel.PinPhase.NEW_PIN -> stringResource(R.string.bipe_recovery_new_pin_title)
        RecoveryFlowModel.PinPhase.NEW_PIN_CONFIRM -> stringResource(R.string.bipe_pin_confirm_title)
        else -> stringResource(R.string.bipe_pin_enter_title)
    }
    val message = when (phase) {
        RecoveryFlowModel.PinPhase.BLOCKED -> stringResource(R.string.bipe_pin_blocked_body, BipeRegistries.PIN_MAX_WRONG_TRIES.toString())
        RecoveryFlowModel.PinPhase.NEW_PIN -> stringResource(R.string.bipe_pin_create_body, BipeRegistries.PIN_LENGTH.toString())
        RecoveryFlowModel.PinPhase.NEW_PIN_CONFIRM -> null
        else -> if (factor == RecoveryFlowModel.Factor.TOKEN) stringResource(R.string.bipe_recovery_token_found) else null
    }
    RecoveryStep(BipeScreen.RECOVERY_PIN, title, message, onBack = { model.back() }) {
        if (phase == RecoveryFlowModel.PinPhase.BLOCKED) {
            FilledButton(stringResource(R.string.bipe_pin_forgot), onClick = model::forgotPin)
            return@RecoveryStep
        }
        // The system digit keyboard brings delete and paste. The field stays one dot wide; the dots show the count.
        BasicTextField(
            value = pin,
            onValueChange = model::editPin,
            enabled = phase != RecoveryFlowModel.PinPhase.CHECKING,
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
        if (phase == RecoveryFlowModel.PinPhase.CHECKING) Busy()
        val problemText = when {
            newPinMismatch -> stringResource(R.string.bipe_pin_mismatch)
            newPinProblem != null -> newPinProblem?.localizedMessage(context)
            else -> error
        }
        if (problemText != null) Footer(problemText, isError = true)
        if (recoveryInvalid) FilledButton(stringResource(R.string.bipe_action_continue), onClick = model::backToStart)
        Footer(stringResource(R.string.bipe_pin_never_asked))
        if (phase != RecoveryFlowModel.PinPhase.NEW_PIN && phase != RecoveryFlowModel.PinPhase.NEW_PIN_CONFIRM) {
            TextButton(onClick = model::forgotPin) {
                Text(stringResource(R.string.bipe_pin_forgot), color = colors.accentInk)
            }
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
    RecoveryStep(
        BipeScreen.RECOVERY_EMAIL,
        stringResource(R.string.bipe_recovery_email),
        stringResource(R.string.bipe_recovery_email_body, BipeRegistries.RECOVERY_DELAY_HOURS_EMAIL.toString()),
        onBack = { model.back() },
    ) {
        if (phase == RecoveryFlowModel.EmailPhase.SENT) {
            Footer(stringResource(R.string.bipe_recovery_email_sent))
            return@RecoveryStep
        }
        OutlinedTextField(
            value = email,
            onValueChange = model::editEmail,
            label = { Text(stringResource(R.string.bipe_recovery_email_field)) },
            singleLine = true,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) Footer(stringResource(R.string.bipe_signup_email_invalid), isError = true)
        error?.let { Footer(it, isError = true) }
        FilledButton(stringResource(R.string.bipe_app_recovery_email_send), enabled = phase != RecoveryFlowModel.EmailPhase.SENDING, onClick = model::sendEmail)
        if (phase == RecoveryFlowModel.EmailPhase.SENDING) Busy()
    }
}

// beta sign-in

@Composable
private fun RecoveryBetaScreen(model: RecoveryFlowModel) {
    val email by model.betaEmail.collectAsState()
    val password by model.betaPassword.collectAsState()
    val busy by model.betaBusy.collectAsState()
    val error by model.betaError.collectAsState()
    RecoveryStep(BipeScreen.RECOVERY_START, stringResource(R.string.bipe_recovery_beta), stringResource(R.string.bipe_recovery_beta_body), onBack = { model.back() }) {
        OutlinedTextField(
            value = email,
            onValueChange = model::editBetaEmail,
            label = { Text(stringResource(R.string.bipe_signup_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = model::editBetaPassword,
            label = { Text(stringResource(R.string.bipe_recovery_beta_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Footer(it, isError = true) }
        FilledButton(stringResource(R.string.bipe_action_continue), enabled = model.canSubmitBeta(email, password, busy), onClick = model::submitBeta)
        if (busy) Busy()
    }
}

// recovery-waiting

@Composable
private fun RecoveryWaitingScreen(model: RecoveryFlowModel) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val pending by model.pending.collectAsState()
    val phase by model.waitingPhase.collectAsState()
    RecoveryStep(BipeScreen.RECOVERY_WAITING, stringResource(R.string.bipe_recovery_waiting_title)) {
        val kept = pending
        when (phase) {
            RecoveryFlowModel.WaitingPhase.WAITING, RecoveryFlowModel.WaitingPhase.CLAIMING -> {
                if (kept != null) {
                    val date = DateUtils.formatDateTime(
                        context,
                        kept.readyAt.toEpochMilli(),
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    )
                    val body = if (kept.purpose == BipeRecoveryPurpose.PIN_RESET) R.string.bipe_recovery_waiting_pin_body else R.string.bipe_recovery_waiting_body
                    Text(stringResource(body, date), color = colors.inkPrimary)
                }
                if (phase == RecoveryFlowModel.WaitingPhase.CLAIMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bipe_app_start_loading), color = colors.inkSecondary)
                    }
                }
            }
            RecoveryFlowModel.WaitingPhase.READY -> if (kept?.purpose == BipeRecoveryPurpose.PIN_RESET) {
                Text(stringResource(R.string.bipe_recovery_pin_ready), color = colors.inkPrimary)
                FilledButton(stringResource(R.string.bipe_action_continue), onClick = model::startOver)
            } else {
                Text(stringResource(R.string.bipe_recovery_ready), color = colors.inkPrimary)
                Footer(stringResource(R.string.bipe_recovery_history_note))
            }
            RecoveryFlowModel.WaitingPhase.CANCELLED -> {
                Text(stringResource(R.string.bipe_recovery_cancelled), color = colors.inkPrimary)
                FilledButton(stringResource(R.string.bipe_action_continue), onClick = model::startOver)
            }
            RecoveryFlowModel.WaitingPhase.EXPIRED -> {
                Text(stringResource(R.string.bipe_error_recovery_invalid), color = colors.inkPrimary)
                FilledButton(stringResource(R.string.bipe_action_continue), onClick = model::startOver)
            }
            RecoveryFlowModel.WaitingPhase.FAILED -> {
                Footer(stringResource(R.string.bipe_error_network), isError = true)
                FilledButton(stringResource(R.string.bipe_app_retry), onClick = model::pollNow)
            }
        }
    }
}
