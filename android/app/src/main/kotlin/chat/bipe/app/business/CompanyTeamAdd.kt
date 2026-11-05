// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.linkcomputer.LinkComputerModel
import chat.bipe.app.linkcomputer.LinkComputerScreen
import chat.bipe.app.settings.SettingsModel
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeSeat
import chat.bipe.shared.api.createSeat
import chat.bipe.shared.api.removeSeat
import chat.bipe.shared.api.seats
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AddPhase { NAME, WARNING, CODE, WAITING, DONE, EXPIRED }

/**
 * Spec company-team-add: name, what the person will read, the code on this phone, then the wait for the person's phone
 * to claim the seat. Both phones are in one place. The Responsável never scans a code from someone else (ADR 0251).
 */
@Composable
fun CompanyTeamAddScreen(home: WorkHomeModel, settings: SettingsModel, accountName: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val business = home.business
    var phase by remember { mutableStateOf(AddPhase.NAME) }
    var name by remember { mutableStateOf("") }
    var seat by remember { mutableStateOf<BipeSeat?>(null) }
    var grantDone by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val limit = home.seats?.maxPeople ?: 0
    val trimmed = name.trim()
    val added = seat
    if (phase == AddPhase.CODE && added != null) {
        val person = remember(added.id) { LinkComputerModel.PersonToAdd(added.id, added.name, accountName) }
        LinkComputerScreen(settings, onClose = {
            // Action cancel before the grant is done drops the seat. After it, action progress waits for the claim.
            if (grantDone) {
                phase = AddPhase.WAITING
            } else {
                scope.launch {
                    runCatching { business.withToken { removeSeat(added.id, it) } }
                    onClose()
                }
            }
        }, mode = LinkComputerModel.Mode.PHONE, person = person, onGrantDone = { grantDone = true })
        return
    }
    if (phase == AddPhase.WAITING && added != null) {
        LaunchedEffect(added.id) {
            // The server always sends the end of the window; 15 minutes is its value in registries/accounts.json.
            val end = added.windowExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now().plusSeconds(900)
            while (Instant.now().isBefore(end)) {
                val list = runCatching { business.withToken { seats(it) } }.getOrNull()
                if (list?.seats?.firstOrNull { it.id == added.id }?.status == "active") {
                    home.loadSeats()
                    phase = AddPhase.DONE
                    return@LaunchedEffect
                }
                delay(2_000)
            }
            runCatching { business.withToken { removeSeat(added.id, it) } }
            home.loadSeats()
            phase = AddPhase.EXPIRED
        }
    }
    BackHandler(enabled = phase != AddPhase.WAITING, onBack = onClose)
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag(BipeScreen.COMPANY_TEAM_ADD.id),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.bipe_team_add), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
        when (phase) {
            AddPhase.NAME -> {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bipe_team_add_name)) }, modifier = Modifier.fillMaxWidth())
                BipeButton(onClick = { phase = AddPhase.WARNING }, enabled = trimmed.isNotEmpty() && trimmed.length <= 60, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_team_add_continue))
                }
                BipeOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_cancel)) }
            }
            AddPhase.WARNING -> {
                Text(stringResource(R.string.bipe_team_add_warning, trimmed, accountName), color = colors.inkPrimary)
                failure?.let { Text(if (it == R.string.bipe_team_add_limit) stringResource(it, limit.toString()) else stringResource(it), color = colors.trustDangerInk) }
                BipeButton(onClick = {
                    failure = null
                    scope.launch {
                        // Action open: the waiting seat, then the code.
                        try {
                            seat = business.withToken { createSeat(trimmed, it) }
                            phase = AddPhase.CODE
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failure = if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.SEAT_LIMIT) R.string.bipe_team_add_limit else seatMessage(error)
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_team_add_continue)) }
                BipeOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_cancel)) }
            }
            AddPhase.CODE, AddPhase.WAITING -> CircularProgressIndicator(color = colors.accentFill)
            AddPhase.DONE -> {
                Text(stringResource(R.string.bipe_team_add_done, trimmed), color = colors.inkPrimary)
                BipeButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_close)) }
            }
            AddPhase.EXPIRED -> {
                Text(stringResource(R.string.bipe_team_add_expired), color = colors.inkPrimary)
                BipeButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_app_close)) }
            }
        }
    }
}
