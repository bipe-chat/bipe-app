// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import android.content.Context
import chat.bipe.shared.api.BipeBadgeGoneReason
import chat.bipe.shared.company.BipeCnpj
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.company.BipeCvmLines
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.BipeWhoCanMessage
import chat.bipe.shared.settings.setWhoCanMessage
import chat.bipe.shared.settings.whoCanMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** The notice for a seal that is no longer valid, else `null` (spec company-seal). */
fun BipeCompanySealState.notice(context: Context): String? = when (this) {
    is BipeCompanySealState.Revoked -> when (reason) {
        BipeBadgeGoneReason.NOT_ACTIVE, BipeBadgeGoneReason.NOT_FOUND -> context.getString(R.string.bipe_badge_company_revoked_not_active)
        else -> context.getString(R.string.bipe_badge_company_revoked)
    }
    BipeCompanySealState.Suspended -> context.getString(R.string.bipe_badge_company_suspended)
    is BipeCompanySealState.Verified, BipeCompanySealState.None -> null
}

/** Spec company-seal cvmLines: the CVM line of a verified seal (ADR 0077), from the signed badge only. */
val BipeCompanySealState.cvm: BipeCvmLines.Result
    get() = BipeCvmLines.forBadge((this as? BipeCompanySealState.Verified)?.payload)

fun BipeCvmLines.Line.text(context: Context): String = when (key) {
    BipeCvmLines.Key.REGISTERED -> context.getString(R.string.bipe_cvm_registered, arg.orEmpty())
    BipeCvmLines.Key.CANCELLED -> context.getString(R.string.bipe_cvm_cancelled, arg.orEmpty())
    BipeCvmLines.Key.NO_REGISTRATION -> context.getString(R.string.bipe_cvm_no_registration)
    BipeCvmLines.Key.BANK_NOTE -> context.getString(R.string.bipe_cvm_bank_note)
    BipeCvmLines.Key.STOP_ORDER -> context.getString(R.string.bipe_cvm_stop_order, arg.orEmpty())
}

/** Spec company-seal: the facts behind a company's seal, and what the seal does not promise (ADR 0042). */
@Composable
fun CompanySealDialog(state: BipeCompanySealState, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val verified = state as? BipeCompanySealState.Verified
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            Icon(
                painterResource((if (verified != null) PhosphorIcon.SEAL_CHECK else PhosphorIcon.SEAL_WARNING).drawable),
                contentDescription = null,
                tint = if (verified != null) colors.trustVerifiedInk else colors.trustCautionInk,
            )
        },
        title = { Text(stringResource(R.string.bipe_app_seal_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag(BipeScreen.COMPANY_SEAL.id)) {
                    Text(state.notice(context) ?: stringResource(R.string.bipe_badge_company), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                    // cvmDanger: a stop order is red with a filled icon, above the other facts. The rest is neutral.
                    val cvm = state.cvm
                    if (cvm.lines.isNotEmpty()) {
                        cvm.lines.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (cvm.danger) {
                                    Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    line.text(context),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (cvm.danger) FontWeight.Bold else null,
                                    color = if (cvm.danger) colors.trustDangerInk else colors.inkPrimary,
                                )
                            }
                        }
                        Text(stringResource(R.string.bipe_cvm_source), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                    verified?.payload?.let { payload ->
                        payload.legalName?.let { Fact(stringResource(R.string.bipe_badge_company_legal_name), it) }
                        payload.tradeName?.let { Fact(stringResource(R.string.bipe_badge_company_trade_name), it) }
                        payload.cnpj?.let { Fact(stringResource(R.string.bipe_badge_company_cnpj), BipeCnpj.formatted(it)) }
                        payload.checkedMonth?.let(BipeCnpj::checkedDate)?.let {
                            Fact(stringResource(R.string.bipe_badge_company_checked), it.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())))
                        }
                        Fact(
                            stringResource(R.string.bipe_badge_company_valid_until),
                            Instant.ofEpochSecond(payload.exp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
                        )
                        Text(stringResource(R.string.bipe_badge_company_body), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) } },
        containerColor = colors.surfaceRaised,
    )
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = BipeTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
    }
}

/** Spec blocked-list: the people the user blocked, with unblock on each row. Opens from Privacidade. */
@Composable
fun BlockedListScreen(people: PeopleService, openPerson: (String) -> Unit, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val ignored by people.ignoredUsers.collectAsState()
    var list by remember { mutableStateOf<List<Person>?>(null) }
    var unblocking by remember { mutableStateOf<Person?>(null) }
    LaunchedEffect(Unit) { people.start() }
    // Action appear, and again on each change of Client.ignoredUsers.
    LaunchedEffect(ignored) {
        val ids = ignored ?: return@LaunchedEffect
        list = people.people(ids.toList()).sortedBy { it.displayName.lowercase() }
    }
    PeopleScaffold(stringResource(R.string.bipe_privacy_blocked), BipeScreen.BLOCKED_LIST.id, onBack) {
        val current = list
        when {
            current == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accentFill)
            }
            current.isEmpty() -> Text(
                stringResource(R.string.bipe_privacy_blocked_none),
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(48.dp),
            )
            else -> current.forEach { person ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { openPerson(person.userId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(person.displayName, person.avatarUrl, people.media, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(person.displayName, color = colors.inkPrimary, maxLines = 1)
                            person.handle?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
                        }
                    }
                    OutlinedButton(onClick = { unblocking = person }) { Text(stringResource(R.string.bipe_action_unblock_person)) }
                }
            }
        }
    }
    unblocking?.let { person -> BlockPersonDialog(people, person.userId, person.displayName, onClose = { unblocking = null }) }
}

/**
 * Spec report-chat: reports a DM or a group to the Bipe team (the homeserver admins), with an optional reason. In the
 * same step the user can block the person (DM), delete the DM, or leave the group.
 */
@Composable
fun ReportChatScreen(people: PeopleService, roomId: String, isDm: Boolean, otherUserId: String?, onSent: (String) -> Unit, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    var alsoDelete by remember { mutableStateOf(false) }
    var alsoLeave by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var leaveFailed by remember { mutableStateOf(false) }

    // Action send: Room.reportRoom, then in order block, the dm-delete write and leave. A failed follow-up does not undo the report.
    fun send() {
        val room = runCatching { people.session.client.getRoom(roomId) }.getOrNull()
        if (room == null) {
            failed = true
            return
        }
        sending = true
        failed = false
        scope.launch {
            if (runCatching { room.reportRoom(reason.trim()) }.isFailure) {
                sending = false
                failed = true
                return@launch
            }
            if (isDm) {
                if (alsoBlock && otherUserId != null) runCatching { people.block(otherUserId) }
                if (alsoDelete) runCatching { people.clearDm(roomId) }
            } else if (alsoLeave && runCatching { room.leave() }.isFailure) {
                sending = false
                leaveFailed = true
                return@launch
            }
            onSent(context.getString(if (isDm) R.string.bipe_dm_reported else R.string.bipe_app_group_reported))
        }
    }

    PeopleScaffold(
        title = stringResource(if (isDm) R.string.bipe_dm_report else R.string.bipe_app_report_group),
        testTag = BipeScreen.REPORT_CHAT.id,
        onBack = { if (!sending) onBack() },
    ) {
        Text(stringResource(R.string.bipe_app_report_reason), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            placeholder = { Text(stringResource(R.string.bipe_app_report_reason_placeholder)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (isDm) R.string.bipe_dm_report_footer else R.string.bipe_app_report_group_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
        if (isDm) {
            if (otherUserId != null) SwitchRow(stringResource(R.string.bipe_app_report_also_block), alsoBlock) { alsoBlock = it }
            SwitchRow(stringResource(R.string.bipe_app_report_also_delete), alsoDelete) { alsoDelete = it }
        } else {
            // A DM never offers a leave (ADR 0141).
            SwitchRow(stringResource(R.string.bipe_app_report_also_leave), alsoLeave) { alsoLeave = it }
        }
        if (failed) Text(stringResource(R.string.bipe_app_report_chat_error), color = colors.trustDangerInk)
        Button(
            onClick = ::send,
            enabled = !sending,
            colors = ButtonDefaults.buttonColors(containerColor = colors.trustDangerInk, contentColor = colors.inkOnAccent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.inkOnAccent)
            } else {
                Text(stringResource(R.string.bipe_app_report_send_action))
            }
        }
    }
    if (leaveFailed) {
        AlertDialog(
            onDismissRequest = { onSent(context.getString(R.string.bipe_app_group_reported)) },
            title = { Text(stringResource(R.string.bipe_dm_report_leave_failed_title)) },
            text = { Text(stringResource(R.string.bipe_dm_report_leave_failed_body)) },
            confirmButton = {
                TextButton(onClick = { onSent(context.getString(R.string.bipe_app_group_reported)) }) { Text(stringResource(R.string.bipe_app_close)) }
            },
            containerColor = colors.surfaceRaised,
        )
    }
}

@Composable
fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = BipeTheme.colors.inkPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Spec who-can-message: the server enforces the choice (ADR 0038); the app only shows and saves it. */
@Composable
fun WhoCanMessageScreen(people: PeopleService, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(BipeWhoCanMessage.EVERYONE) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { people.withToken { people.api.whoCanMessage(it) } }
            .onSuccess { current = it; failed = false }
            .onFailure { failed = true }
    }
    PeopleScaffold(stringResource(R.string.bipe_messaging_title), BipeScreen.WHO_CAN_MESSAGE.id, onBack) {
        RowGroup {
            BipeWhoCanMessage.entries.forEach { value ->
                ListItem(
                    headlineContent = { Text(stringResource(whoTitle(value)), color = colors.inkPrimary) },
                    supportingContent = { Text(stringResource(whoBody(value)), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) },
                    trailingContent = if (value == current) {
                        { Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !saving, role = Role.RadioButton) {
                        if (value == current || saving) return@clickable
                        // Shows the new choice at once and puts the old one back on failure.
                        val old = current
                        current = value
                        saving = true
                        scope.launch {
                            runCatching { people.withToken { people.api.setWhoCanMessage(value, it) } }
                                .onSuccess { current = it; failed = false }
                                .onFailure { current = old; failed = true }
                            saving = false
                        }
                    }.semantics { selected = value == current },
                )
            }
        }
        if (failed) Text(stringResource(R.string.bipe_error_network), color = colors.trustDangerInk)
    }
}

fun whoTitle(value: BipeWhoCanMessage): Int = when (value) {
    BipeWhoCanMessage.EVERYONE -> R.string.bipe_messaging_everyone
    BipeWhoCanMessage.VERIFIED -> R.string.bipe_messaging_verified
    BipeWhoCanMessage.CONTACTS -> R.string.bipe_messaging_contacts
}

private fun whoBody(value: BipeWhoCanMessage): Int = when (value) {
    BipeWhoCanMessage.EVERYONE -> R.string.bipe_messaging_everyone_body
    BipeWhoCanMessage.VERIFIED -> R.string.bipe_messaging_verified_body
    BipeWhoCanMessage.CONTACTS -> R.string.bipe_messaging_contacts_body
}
