// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.onboarding.BipeOnboardingSchedule
import chat.bipe.shared.onboarding.BipePinReminderMemory
import chat.bipe.shared.onboarding.BipePromptSlot
import chat.bipe.shared.settings.checkPin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec pin-reminder (bipe-chat/bipe#202): whether the spaced PIN check shows now on this account. */
class PinReminderModel(context: Context, userId: String) {
    private val memory = BipePinReminderMemory(context, userId)
    private val slot = BipePromptSlot(context, userId)
    private val _showsCard = MutableStateFlow(false)
    val showsCard: StateFlow<Boolean> = _showsCard.asStateFlow()

    init {
        memory.firstSeen
        refresh()
    }

    /** State due: the reminder time has come and the prompt slot allows it. */
    fun refresh() {
        _showsCard.value = memory.isDue() && slot.allows(BipeOnboardingPrompt.PIN_REMINDER)
    }

    fun shown() = slot.didShow(BipeOnboardingPrompt.PIN_REMINDER)

    /** The check result moves the schedule (pinReminderNext); "Agora não" asks again after a day. */
    fun checked(result: BipeOnboardingSchedule.PinResult) {
        memory.checked(result)
        _showsCard.value = false
    }
}

/** The card (registries/onboarding.json prompts.pinReminder). Same shape as the recovery reminder card. */
@Composable
fun PinReminderCard(model: PinReminderModel, open: () -> Unit) {
    val colors = BipeTheme.colors
    val prompt = BipeOnboardingPrompt.PIN_REMINDER
    LaunchedEffect(Unit) { model.shown() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(BipeScreen.PIN_REMINDER.id)
            .background(colors.accentWash, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(prompt.title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_pin_reminder_body, BipeRegistries.PIN_LENGTH.toString()),
            style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(prompt.primary.title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.bipeTapTarget().background(colors.accentFill, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = open)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            prompt.text.forEach {
                Text(
                    stringResource(it.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accentInk,
                    modifier = Modifier.bipeTapTarget().clickable(role = Role.Button) { model.checked(BipeOnboardingSchedule.PinResult.LATER) },
                )
            }
        }
    }
}

private enum class PinReminderPhase { EDITING, CHECKING, RIGHT, WRONG, BLOCKED, FAILED }

/** Spec pin-reminder: one check of the PIN with POST /v1/me/pin/check. The PIN is never logged. */
@Composable
fun PinReminderScreen(
    session: AccountSession,
    api: BipeApiClient,
    onResult: (BipeOnboardingSchedule.PinResult) -> Unit,
    onChangePin: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(PinReminderPhase.EDITING) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PeopleScaffold(stringResource(R.string.bipe_pin_reminder_title), BipeScreen.PIN_REMINDER.id, onClose) {
        Text(stringResource(R.string.bipe_pin_reminder_body, BipeRegistries.PIN_LENGTH.toString()), color = colors.inkSecondary)
        when (phase) {
            PinReminderPhase.RIGHT -> Text(stringResource(R.string.bipe_pin_reminder_right), color = colors.statusSuccess)
            PinReminderPhase.WRONG -> {
                Text(stringResource(R.string.bipe_pin_reminder_wrong), color = colors.trustDangerInk)
                Button(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_pin_change_title)) }
            }
            PinReminderPhase.BLOCKED ->
                Text(stringResource(R.string.bipe_pin_blocked_body, BipeRegistries.PIN_MAX_WRONG_TRIES.toString()), color = colors.trustDangerInk)
            else -> {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { text ->
                        pin = BipePin.sanitize(text)
                        if (phase == PinReminderPhase.FAILED) phase = PinReminderPhase.EDITING
                        if (pin.length == BipeRegistries.PIN_LENGTH && phase == PinReminderPhase.EDITING) {
                            val typed = pin
                            pin = ""
                            phase = PinReminderPhase.CHECKING
                            scope.launch {
                                phase = try {
                                    BipeApiClient.withTokenRetry({ session.accessToken() }) { api.checkPin(typed, it) }
                                    onResult(BipeOnboardingSchedule.PinResult.RIGHT)
                                    PinReminderPhase.RIGHT
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: BipeApiException.Server) {
                                    when (e.code) {
                                        BipeApiErrorCode.PIN_WRONG -> { onResult(BipeOnboardingSchedule.PinResult.WRONG); PinReminderPhase.WRONG }
                                        BipeApiErrorCode.PIN_BLOCKED -> { onResult(BipeOnboardingSchedule.PinResult.WRONG); PinReminderPhase.BLOCKED }
                                        else -> PinReminderPhase.FAILED
                                    }
                                } catch (_: Exception) {
                                    PinReminderPhase.FAILED
                                }
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.bipe_pin_reminder_action)) },
                    enabled = phase != PinReminderPhase.CHECKING,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (phase == PinReminderPhase.CHECKING) CircularProgressIndicator(color = colors.accentFill)
                if (phase == PinReminderPhase.FAILED) Text(stringResource(R.string.bipe_error_network), color = colors.trustDangerInk)
            }
        }
    }
}
