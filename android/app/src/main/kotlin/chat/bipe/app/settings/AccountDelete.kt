// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountList
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeAccountDeletion
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Spec account-delete (ADR 0140): what happens, the switch to delete every message, the device owner check, the senha,
 * then POST /v1/me/deletion. The server deletes the account after [BipeRegistries.DELETION_COOLDOWN_HOURS].
 */
@Composable
fun AccountDeleteDialog(model: SettingsModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<DeletePhase>(DeletePhase.Explain) }
    var redactMessages by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val ownerReason = stringResource(R.string.bipe_deletion_owner_reason)
    val isLastAccount = remember { AccountList(context).order.size <= 1 }

    fun submit(entered: String) {
        phase = DeletePhase.Requesting
        scope.launch {
            // Action submitPin: the senha is never kept.
            phase = try {
                val deletion = model.authorized { model.api.requestDeletion(entered, redactMessages, it) }
                DeletionDates.remember(context, model.session.userId, deletion.dueAt)
                view.bipeHaptic(BipeHaptic.Success)
                model.load()
                DeletePhase.Requested(deletion.dueAt)
            } catch (error: BipeApiException) {
                val code = (error as? BipeApiException.Server)?.code
                when (code) {
                    BipeApiErrorCode.PIN_WRONG -> {
                        view.bipeHaptic(BipeHaptic.Error)
                        pinError = error.localizedMessage(context)
                        DeletePhase.EnterPin
                    }
                    BipeApiErrorCode.PIN_BLOCKED, BipeApiErrorCode.PIN_NOT_SET, BipeApiErrorCode.RATE_LIMITED ->
                        DeletePhase.Failed(error.localizedMessage(context))
                    BipeApiErrorCode.DELETION_PENDING -> {
                        // Another device asked first: the banner or the alert shows it.
                        model.load()
                        onDismiss()
                        DeletePhase.Explain
                    }
                    else -> DeletePhase.Failed(context.getString(R.string.bipe_deletion_error))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DeletePhase.Failed(context.getString(R.string.bipe_deletion_error))
            }
        }
    }

    Dialog(
        onDismissRequest = { if (phase != DeletePhase.Requesting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.ACCOUNT_DELETE.id), color = colors.surfaceBase) {
            when (val current = phase) {
                DeletePhase.Explain -> Column(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.bipe_deletion_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
                    Text(stringResource(R.string.bipe_deletion_intro, BipeRegistries.DELETION_COOLDOWN_HOURS.toString()), color = colors.inkPrimary)
                    // One line per point: never joined into one sentence (linguagem.md section 6).
                    Point(stringResource(R.string.bipe_deletion_point_handle, BipeRegistries.HANDLE_RELEASE_HOLD_DAYS.toString()))
                    Point(stringResource(R.string.bipe_deletion_point_groups))
                    Point(stringResource(R.string.bipe_deletion_point_data))
                    Point(stringResource(R.string.bipe_deletion_point_kept, BipeRegistries.DELETION_VERIFICATION_KEEP_DAYS.toString()))
                    if (state.isCompany) Point(stringResource(R.string.bipe_deletion_point_company))
                    if (isLastAccount) Point(stringResource(R.string.bipe_deletion_point_last_account))
                    SettingsSwitchItem(
                        stringResource(R.string.bipe_deletion_redact),
                        stringResource(R.string.bipe_deletion_redact_footer),
                        redactMessages,
                    ) { redactMessages = it }
                    OutlinedButton(
                        onClick = {
                            // Action continue: the device owner check. A phone with no lock cannot check its owner: fail closed.
                            if (!OwnerCheck.canCheckOwner(context)) {
                                phase = DeletePhase.Failed(context.getString(R.string.bipe_deletion_error))
                            } else {
                                scope.launch {
                                    if (OwnerCheck.owner(context, ownerReason)) {
                                        pin = ""
                                        pinError = null
                                        phase = DeletePhase.EnterPin
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.trustDangerInk),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.bipe_action_continue))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk)
                    }
                }
                DeletePhase.EnterPin, DeletePhase.Requesting -> PinPad(
                    title = stringResource(R.string.bipe_deletion_pin_title),
                    pin = pin,
                    error = pinError,
                    isBusy = current == DeletePhase.Requesting,
                    onDigit = { digit ->
                        if (current == DeletePhase.EnterPin && pin.length < BipeRegistries.PIN_LENGTH) {
                            if (pin.isEmpty()) pinError = null
                            pin += digit
                            if (pin.length == BipeRegistries.PIN_LENGTH) {
                                val entered = pin
                                pin = ""
                                submit(entered)
                            }
                        }
                    },
                    onDelete = { pin = pin.dropLast(1) },
                )
                is DeletePhase.Requested -> Message(
                    stringResource(R.string.bipe_deletion_requested, DeletionDates.text(context, current.dueAt)),
                    PhosphorIcon.CHECK,
                    onDismiss,
                )
                is DeletePhase.Failed -> Message(current.text, PhosphorIcon.WARNING_CIRCLE, onDismiss)
            }
        }
    }
}

private sealed interface DeletePhase {
    data object Explain : DeletePhase
    data object EnterPin : DeletePhase
    data object Requesting : DeletePhase
    data class Requested(val dueAt: Instant) : DeletePhase
    data class Failed(val text: String) : DeletePhase
}

@Composable
private fun Point(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BipeTheme.colors.inkPrimary)
}

@Composable
private fun Message(text: String, icon: PhosphorIcon, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
        Text(text, color = colors.inkPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.bipe_action_continue))
        }
    }
}

/** The banner at the top of Sua conta while a deletion this phone asked for waits (spec account-delete). */
@Composable
fun DeletionBanner(deletion: BipeAccountDeletion, isCancelling: Boolean, onCancel: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    val cancelLabel = stringResource(R.string.bipe_a11y_deletion_cancel)
    Surface(color = colors.trustDangerWash, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bipe_deletion_banner, DeletionDates.text(context, deletion.dueAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.trustDangerInk,
            )
            OutlinedButton(
                onClick = onCancel,
                enabled = !isCancelling,
                modifier = Modifier.semantics { contentDescription = cancelLabel },
            ) {
                Text(stringResource(R.string.bipe_deletion_cancel))
            }
        }
    }
}

/**
 * Due times of waiting deletions, kept on this phone so the app knows the account is gone when its session ends after
 * the due time (spec account-delete notes: bipe_deletion_done).
 */
object DeletionDates {
    private const val PREFS = "bipe_deletion"

    fun text(context: Context, instant: Instant): String = DateUtils.formatDateTime(
        context,
        instant.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    fun remember(context: Context, userId: String, dueAt: Instant?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (dueAt == null) prefs.remove(userId) else prefs.putLong(userId, dueAt.toEpochMilli())
        prefs.apply()
    }

    /** True when this account had a deletion whose due time has passed. */
    fun isPast(context: Context, userId: String): Boolean {
        val due = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(userId, 0)
        return due > 0 && due <= System.currentTimeMillis()
    }
}
