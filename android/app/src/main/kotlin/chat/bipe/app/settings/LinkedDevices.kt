// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.app.common.BipeToasts
import chat.bipe.shared.bipeHaptic
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.timeline.AssistantDraftsStore
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.devices.BipeDevices
import chat.bipe.shared.devices.signOutDevice
import chat.bipe.shared.devices.signOutOtherDevices
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.weblink.webLinkPlace
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec linked-devices (ADR 0291): every device of the account with its kind and details, this phone first, and
 * signing out one device or all others natively through bipe-api after the biometric check.
 */
class LinkedDevicesModel(private val context: Context, val model: SettingsModel, private val scope: CoroutineScope) {
    sealed interface Phase {
        data object Loading : Phase
        data class Loaded(val rows: List<BipeDevices.Row>) : Phase
        data object Failed : Phase
    }

    /** What the sign-out sheet asks about. */
    sealed interface SignOutTarget {
        data class One(val row: BipeDevices.Row) : SignOutTarget
        data object Others : SignOutTarget
    }

    enum class SignOutPhase { IDLE, CONFIRM_IDENTITY, LOADING, FAILED }

    data class State(
        val phase: Phase = Phase.Loading,
        /** Place per IP (GET /v1/web-link/place). The IP goes to no outside service. */
        val places: Map<String, String> = emptyMap(),
        val signOutTarget: SignOutTarget? = null,
        val signOutPhase: SignOutPhase = SignOutPhase.IDLE,
        /** The device whose device-detail is open. */
        val detail: String? = null,
    ) {
        val rows: List<BipeDevices.Row> get() = (phase as? Phase.Loaded)?.rows.orEmpty()
        val devices: List<BipeDevices.Row> get() = rows.filter { it.section != "programs" }
        val programs: List<BipeDevices.Row> get() = rows.filter { it.section == "programs" }

        /** Action askSignOutOthers is shown when two or more other devices exist. */
        val showsSignOutOthers: Boolean get() = rows.count { it.canSignOut } >= 2

        fun place(row: BipeDevices.Row): String? = row.ip?.let { places[it] }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Action appear: the devices, the entries of the drafts room, deviceRows, then the place of each row. */
    fun load() {
        scope.launch {
            val list = DeviceEntries.list(model.session)
            if (list == null) {
                _state.update { if (it.rows.isEmpty()) it.copy(phase = Phase.Failed) else it }
                return@launch
            }
            _state.update { state ->
                state.copy(phase = Phase.Loaded(list.rows), detail = state.detail?.takeIf { id -> list.rows.any { it.deviceId == id } })
            }
            model.setDeviceCounts(list.counts)
            for (ip in list.rows.mapNotNull { it.ip }.toSet()) {
                if (_state.value.places.containsKey(ip)) continue
                val place = runCatching { model.authorized { model.api.webLinkPlace(ip, it) }.label() }.getOrNull() ?: continue
                _state.update { it.copy(places = it.places + (ip to place)) }
            }
        }
    }

    /** Action retry. */
    fun retry() {
        _state.update { it.copy(phase = Phase.Loading) }
        load()
    }

    /** Action openDevice. */
    fun open(row: BipeDevices.Row?) = _state.update { it.copy(detail = row?.deviceId) }

    fun askSignOut(target: SignOutTarget) = _state.update { it.copy(signOutTarget = target) }

    fun cancelSignOut() {
        val phase = _state.value.signOutPhase
        if (phase == SignOutPhase.IDLE || phase == SignOutPhase.FAILED) {
            _state.update { it.copy(signOutTarget = null, signOutPhase = SignOutPhase.IDLE) }
        }
    }

    /** The title of the approving device for device-detail linkedBy. */
    fun name(deviceId: String): String =
        _state.value.rows.firstOrNull { it.deviceId == deviceId }?.title(context) ?: deviceId

    /** Action confirmSignOut and confirmSignOutOthers: the biometric check, then the bipe-api route, then a reload. */
    fun confirmSignOut(activity: Context) {
        val target = _state.value.signOutTarget ?: return
        if (_state.value.signOutPhase != SignOutPhase.IDLE) return
        scope.launch {
            _state.update { it.copy(signOutPhase = SignOutPhase.CONFIRM_IDENTITY) }
            if (!OwnerCheck.owner(activity, activity.getString(R.string.bipe_linked_devices_sign_out_biometric))) {
                _state.update { it.copy(signOutPhase = SignOutPhase.IDLE) }
                return@launch
            }
            _state.update { it.copy(signOutPhase = SignOutPhase.LOADING) }
            val done = runCatching {
                when (target) {
                    is SignOutTarget.One -> model.authorized { model.api.signOutDevice(target.row.deviceId, it) }
                    SignOutTarget.Others -> model.authorized { model.api.signOutOtherDevices(it) }
                }
            }.isSuccess
            if (!done) {
                _state.update { it.copy(signOutPhase = SignOutPhase.FAILED) }
                return@launch
            }
            _state.update { it.copy(signOutTarget = null, signOutPhase = SignOutPhase.IDLE) }
            BipeToasts.show(context, R.string.bipe_app_linked_devices_signed_out)
            load()
        }
    }
}

/** The device list of the group Aparelhos e assistentes (ADR 0291), kept while the group screen is shown. */
@Composable
fun rememberLinkedDevices(model: SettingsModel): LinkedDevicesModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { LinkedDevicesModel(context.applicationContext, model, scope) }
    // A device entry that arrives later in the drafts room fills its row.
    val drafts by AssistantDraftsStore.state.collectAsState()
    LaunchedEffect(devices, drafts.deviceEntries) { devices.load() }
    return devices
}

/** True while device-detail is in front: the group screen shows only that page. */
@Composable
fun LinkedDevicesPage(devices: LinkedDevicesModel): Boolean {
    val state by devices.state.collectAsState()
    val row = state.detail?.let { id -> state.rows.firstOrNull { it.deviceId == id } } ?: return false
    DeviceDetail(devices, state, row)
    return true
}

/** Spec linked-devices: the sections Aparelhos and Assistentes e programas (ADR 0291). */
fun LazyListScope.linkedDevicesItems(state: LinkedDevicesModel.State, devices: LinkedDevicesModel) {
    item(key = "linked-devices-header") {
        Text(
            stringResource(R.string.bipe_devices_section_devices),
            style = MaterialTheme.typography.titleSmall,
            color = BipeTheme.colors.inkSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag(BipeScreen.LINKED_DEVICES.id),
        )
    }
    when (state.phase) {
        LinkedDevicesModel.Phase.Loading -> item(key = "linked-devices-loading") {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BipeTheme.colors.accentFill)
            }
        }
        LinkedDevicesModel.Phase.Failed -> item(key = "linked-devices-failed") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bipe_linked_devices_error), color = BipeTheme.colors.inkSecondary)
                TextButton(onClick = devices::retry) { Text(stringResource(R.string.bipe_app_retry)) }
            }
        }
        is LinkedDevicesModel.Phase.Loaded -> if (state.devices.isEmpty()) {
            item(key = "linked-devices-empty") {
                Text(stringResource(R.string.bipe_linked_devices_empty), color = BipeTheme.colors.inkSecondary, modifier = Modifier.padding(16.dp))
            }
        } else {
            items(state.devices, key = { "linked-device-${it.deviceId}" }) { row ->
                DeviceRow(row, state.place(row), devices)
            }
            if (state.showsSignOutOthers) {
                item(key = "linked-devices-sign-out-others") {
                    TextButton(
                        onClick = { devices.askSignOut(LinkedDevicesModel.SignOutTarget.Others) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Text(stringResource(R.string.bipe_devices_sign_out_others), color = BipeTheme.colors.trustDangerInk)
                    }
                }
            }
        }
    }
    item(key = "linked-devices-footer") {
        Text(
            stringResource(R.string.bipe_linked_devices_footer),
            style = MaterialTheme.typography.bodySmall,
            color = BipeTheme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (state.programs.isNotEmpty()) {
        item(key = "linked-programs-header") {
            Text(
                stringResource(R.string.bipe_devices_section_programs),
                style = MaterialTheme.typography.titleSmall,
                color = BipeTheme.colors.inkSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(state.programs, key = { "linked-program-${it.deviceId}" }) { row ->
            DeviceRow(row, state.place(row), devices)
        }
    }
}

/** The sign-out sheet and its error, for linked-devices and device-detail. */
@Composable
fun LinkedDevicesDialogs(devices: LinkedDevicesModel) {
    val context = LocalContext.current
    val state by devices.state.collectAsState()
    val colors = BipeTheme.colors
    val target = state.signOutTarget
    if (target != null && state.signOutPhase == LinkedDevicesModel.SignOutPhase.IDLE) {
        val others = target == LinkedDevicesModel.SignOutTarget.Others
        AlertDialog(
            onDismissRequest = devices::cancelSignOut,
            title = {
                Text(
                    when (target) {
                        is LinkedDevicesModel.SignOutTarget.One -> stringResource(R.string.bipe_linked_devices_sign_out_title, target.row.title(context))
                        LinkedDevicesModel.SignOutTarget.Others -> stringResource(R.string.bipe_devices_sign_out_others_title)
                    },
                )
            },
            text = { Text(stringResource(if (others) R.string.bipe_devices_sign_out_others_body else R.string.bipe_linked_devices_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = { devices.confirmSignOut(context) }) {
                    Text(
                        stringResource(if (others) R.string.bipe_devices_sign_out_others else R.string.bipe_linked_devices_sign_out),
                        color = colors.trustDangerInk,
                    )
                }
            },
            dismissButton = { TextButton(onClick = devices::cancelSignOut) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (state.signOutPhase == LinkedDevicesModel.SignOutPhase.LOADING) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.bipe_app_linked_devices_sign_out_loading))
                }
            },
            confirmButton = {},
        )
    }
    if (state.signOutPhase == LinkedDevicesModel.SignOutPhase.FAILED) {
        AlertDialog(
            onDismissRequest = devices::cancelSignOut,
            title = { Text(stringResource(R.string.bipe_linked_devices_title)) },
            text = { Text(stringResource(R.string.bipe_app_linked_devices_sign_out_error)) },
            confirmButton = { TextButton(onClick = devices::cancelSignOut) { Text(stringResource(R.string.bipe_app_close)) } },
        )
    }
}

/** Kind, system and app, and "fora do Brasil" for a program that sends data out of Brazil. */
private fun details(context: Context, row: BipeDevices.Row): String = buildList {
    add(row.kindLabel(context))
    row.os?.let { add(it) }
    row.app?.takeIf { it != row.name }?.let { add(it) }
    if (row.leavesBrazil) add(context.getString(R.string.bipe_devices_outside_brazil))
}.joinToString(" · ")

private fun lastUsed(context: Context, row: BipeDevices.Row): String? {
    val ts = row.lastSeen?.takeIf { !row.isThis } ?: return null
    val ago = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return context.getString(R.string.bipe_linked_devices_last_used, ago)
}

private fun icon(row: BipeDevices.Row): PhosphorIcon = PhosphorIcon.entries.firstOrNull { it.id == row.icon } ?: PhosphorIcon.WARNING

/** One row: opens device-detail; the menu signs the device out (action askSignOut). */
@Composable
private fun DeviceRow(row: BipeDevices.Row, place: String?, devices: LinkedDevicesModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { devices.open(row) }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(icon(row).drawable),
            contentDescription = null,
            tint = if (row.warning) colors.trustCautionInk else colors.accentInk,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            if (row.isThis) {
                Text(
                    stringResource(R.string.bipe_devices_this_device),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accentInk,
                )
            }
            Text(row.title(context), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            Text(details(context, row), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            val use = listOfNotNull(lastUsed(context, row), place).joinToString(" · ")
            if (use.isNotEmpty()) Text(use, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            if (row.warning) {
                Text(stringResource(R.string.bipe_devices_unknown_warning), style = MaterialTheme.typography.bodySmall, color = colors.trustCautionInk)
            }
        }
        if (row.canSignOut) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.bipeTapTarget()) {
                    Icon(
                        painterResource(PhosphorIcon.DOTS_THREE.drawable),
                        contentDescription = stringResource(R.string.bipe_linked_devices_sign_out),
                        tint = colors.glyphPrimary,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bipe_linked_devices_sign_out), color = colors.trustDangerInk) },
                        onClick = {
                            menu = false
                            devices.askSignOut(LinkedDevicesModel.SignOutTarget.One(row))
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = colors.lineSubtle, modifier = Modifier.padding(start = 16.dp))
}

/** Spec device-detail (ADR 0291): everything Bipe knows about one device. */
@Composable
private fun DeviceDetail(devices: LinkedDevicesModel, state: LinkedDevicesModel.State, row: BipeDevices.Row) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val colors = BipeTheme.colors
    val settings by devices.model.state.collectAsState()
    BackHandler { devices.open(null) }
    val title = if (row.isThis) stringResource(R.string.bipe_devices_this_device) else row.title(context)
    SettingsPage(title, BipeScreen.DEVICE_DETAIL.id, onBack = { devices.open(null) }) {
        item(key = "device-head") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(icon(row).drawable),
                    contentDescription = null,
                    tint = if (row.warning) colors.trustCautionInk else colors.accentInk,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(row.title(context), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                    Text(details(context, row), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
            }
            if (row.warning) {
                Text(
                    stringResource(R.string.bipe_devices_unknown_warning),
                    color = colors.trustCautionInk,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (row.isThis) {
                Text(
                    stringResource(R.string.bipe_devices_this_device_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        val lines = buildList {
            lastUsed(context, row)?.let { add(it) }
            state.place(row)?.let { add(context.getString(R.string.bipe_device_detail_place, it)) }
            row.ip?.let { add(context.getString(R.string.bipe_device_detail_ip, it)) }
            row.linkedAt?.let {
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                add(context.getString(R.string.bipe_device_detail_linked_at, date))
            }
            row.linkedBy?.let { add(context.getString(R.string.bipe_device_detail_linked_by, devices.name(it))) }
            add(context.getString(if (row.attested) R.string.bipe_device_detail_attested else R.string.bipe_device_detail_not_attested))
        }
        items(lines, key = { "device-line-$it" }) { line ->
            Text(line, color = colors.inkPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        }
        // Action copyId: the one place that shows the device ID (ADR 0291).
        item(key = "device-id") {
            Text(
                stringResource(R.string.bipe_device_detail_id, row.deviceId),
                color = colors.inkPrimary,
                modifier = Modifier.fillMaxWidth().clickable {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, row.deviceId))
                    view.bipeHaptic(BipeHaptic.Tap)
                    BipeToasts.copied(context, R.string.bipe_device_detail_id_copied)
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (row.kind == "assistant") {
            // Action toggleAssistantSend: the same value as the row assistantSend of the group.
            item(key = "device-assistant-send") {
                SettingsSwitchItem(
                    stringResource(R.string.bipe_privacy_assistant_send),
                    stringResource(R.string.bipe_privacy_assistant_send_footer),
                    isOn = settings.assistantSend ?: false,
                    enabled = settings.assistantSend != null,
                ) { isOn ->
                    scope.launch { if (devices.model.setAssistantSend(isOn)) view.bipeHaptic(BipeHaptic.Tick) }
                }
            }
        }
        if (row.canSignOut) {
            item(key = "device-sign-out") {
                TextButton(
                    onClick = { devices.askSignOut(LinkedDevicesModel.SignOutTarget.One(row)) },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.bipe_linked_devices_sign_out), color = colors.trustDangerInk)
                }
            }
        }
    }
    LinkedDevicesDialogs(devices)
}
