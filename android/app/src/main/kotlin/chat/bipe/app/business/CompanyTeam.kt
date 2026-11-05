// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.linkcomputer.LinkComputerModel
import chat.bipe.app.linkcomputer.LinkComputerScreen
import chat.bipe.app.settings.SettingsModel
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeSeatDevice
import chat.bipe.shared.api.BipeSeatList
import chat.bipe.shared.api.assignSeatDevice
import chat.bipe.shared.api.openSeatWindow
import chat.bipe.shared.api.removeSeatDevice
import chat.bipe.shared.generated.BipeScreen

/**
 * Spec company-team: Quem atende (ADR 0251, ADR 0252). One person is a complete state. Only the owner sees devices and
 * actions. Calling a person comes with bipe-chat/bipe#1056.
 */
@Composable
fun CompanyTeamScreen(home: WorkHomeModel, session: AccountSession, run: (suspend () -> Unit) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val business = home.business
    val settings = remember(session) { SettingsModel(context.applicationContext, session, business.api, scope) }
    var accountName by remember { mutableStateOf("") }
    var linkingOwnPhone by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        accountName = runCatching { session.client.displayName() }.getOrNull().orEmpty()
        home.loadSeats()
    }
    if (linkingOwnPhone) {
        LinkComputerScreen(settings, onClose = {
            linkingOwnPhone = false
            run { home.loadSeats() }
        }, mode = LinkComputerModel.Mode.PHONE)
        return
    }
    val list = home.seats
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(BipeScreen.COMPANY_TEAM.id)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
            Text(stringResource(R.string.bipe_team_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        }
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(16.dp))
        } else {
            val isOwner = list.me.role == "owner"
            list.activeSeats.forEach { seat ->
                val mine = list.me.seatId == seat.id
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if (mine) stringResource(R.string.bipe_team_you) else seat.name, color = colors.inkPrimary)
                    Text(stringResource(if (seat.role == "owner") R.string.bipe_team_role_owner else R.string.bipe_team_role_attendant),
                        style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    if (mine && seat.role != "owner") {
                        Text(stringResource(R.string.bipe_team_you_here), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    seat.devices.forEach { device ->
                        Text(deviceText(device), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
            }
            if (list.activeSeats.size == 1) {
                Text(stringResource(R.string.bipe_team_alone_body, accountName), style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            val mySeat = list.me.seatId
            if (isOwner && mySeat != null) {
                TextButton(onClick = {
                    run {
                        runCatching { business.withToken { openSeatWindow(mySeat, it) } }
                        linkingOwnPhone = true
                    }
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.bipe_team_add_own)) }
            }
            if (isOwner && list.unassignedDevices.isNotEmpty()) UnassignedDevices(home, list, run)
            if (list.activeSeats.size > 1) {
                Text(stringResource(R.string.bipe_team_shared_title), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
                listOf(R.string.bipe_team_shared_chats, R.string.bipe_team_shared_read, R.string.bipe_team_shared_changes).forEach { line ->
                    Text(stringResource(line), color = colors.inkPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun UnassignedDevices(home: WorkHomeModel, list: BipeSeatList, run: (suspend () -> Unit) -> Unit) {
    val colors = BipeTheme.colors
    val business = home.business
    Text(stringResource(R.string.bipe_team_unassigned_title), style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
    list.unassignedDevices.forEach { device ->
        var picking by remember(device.deviceId) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(deviceText(device), color = colors.inkPrimary)
            Row {
                TextButton(onClick = { picking = true }) { Text(stringResource(R.string.bipe_team_device_assign)) }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    list.activeSeats.forEach { seat ->
                        DropdownMenuItem(text = { Text(seat.name) }, onClick = {
                            picking = false
                            run { home.seatAction { business.withToken { assignSeatDevice(device.deviceId, seat.id, it) } } }
                        })
                    }
                }
                TextButton(onClick = {
                    run { home.seatAction { business.withToken { removeSeatDevice(device.deviceId, it) } } }
                }) { Text(stringResource(R.string.bipe_team_device_remove)) }
            }
        }
    }
    Text(stringResource(R.string.bipe_team_unassigned_body), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun deviceText(device: BipeSeatDevice): String =
    device.displayName ?: stringResource(if (device.kind == "web") R.string.bipe_team_device_web else R.string.bipe_team_device_phone)
