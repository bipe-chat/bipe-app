// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MembershipState

/** Spec group-leave (bipe-shared product/screens/group-leave.json). */
class GroupLeaveModel(
    private val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
) {
    enum class Warning { NONE, ALONE, LAST_ADMIN }

    enum class Phase { CONFIRM, LEAVING, FAILED }

    private val _warning = MutableStateFlow(Warning.NONE)
    val warning: StateFlow<Warning> = _warning.asStateFlow()
    private val _phase = MutableStateFlow(Phase.CONFIRM)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Action appear: reads RoomInfo and the members' roles to set warning. */
    fun appear() {
        val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return
        scope.launch {
            val info = runCatching { room.roomInfo() }.getOrNull() ?: return@launch
            if (info.joinedMembersCount <= 1uL) {
                _warning.value = Warning.ALONE
                return@launch
            }
            val members = GroupPeople.members(room, cachedOnly = true) ?: return@launch
            val admins = members.filter { it.membership is MembershipState.Join && GroupPeople.isAdmin(it) }
            if (admins.size == 1 && admins.first().userId == session.userId) _warning.value = Warning.LAST_ADMIN
        }
    }

    /** Action leave. */
    fun leave(onLeft: () -> Unit) {
        if (_phase.value == Phase.LEAVING) return
        val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return
        _phase.value = Phase.LEAVING
        scope.launch {
            runCatching { room.leave() }
                .onSuccess { onLeft() }
                .onFailure { _phase.value = Phase.FAILED }
        }
    }
}

/** Spec group-leave: a confirm sheet. onChooseAdmin opens group-members; onLeft closes the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLeaveSheet(session: AccountSession, roomId: String, onDismiss: () -> Unit, onChooseAdmin: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { GroupLeaveModel(session, roomId, scope) }
    LaunchedEffect(model) { model.appear() }
    val colors = BipeTheme.colors
    val warning by model.warning.collectAsState()
    val phase by model.phase.collectAsState()
    val leaving = phase == GroupLeaveModel.Phase.LEAVING
    ModalBottomSheet(
        onDismissRequest = { if (!leaving) onDismiss() },
        containerColor = colors.surfaceRaised,
        modifier = Modifier.testTag(BipeScreen.GROUP_LEAVE.id),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bipe_app_group_leave_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            val body = when (warning) {
                GroupLeaveModel.Warning.NONE -> R.string.bipe_app_group_leave_body
                GroupLeaveModel.Warning.ALONE -> R.string.bipe_app_group_leave_alone
                GroupLeaveModel.Warning.LAST_ADMIN -> R.string.bipe_app_group_leave_last_admin
            }
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
            when (phase) {
                GroupLeaveModel.Phase.LEAVING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.bipe_app_group_leaving), color = colors.inkSecondary)
                }
                GroupLeaveModel.Phase.FAILED -> Text(stringResource(R.string.bipe_app_group_leave_error), color = colors.trustDangerInk)
                GroupLeaveModel.Phase.CONFIRM -> Unit
            }
            if (warning == GroupLeaveModel.Warning.LAST_ADMIN) {
                Button(
                    onClick = onChooseAdmin,
                    enabled = !leaving,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentFill, contentColor = colors.inkOnAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bipe_app_group_leave_choose_admin))
                }
            }
            OutlinedButton(onClick = { model.leave(onLeft) }, enabled = !leaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_app_group_leave_confirm), color = colors.trustDangerInk)
            }
            TextButton(onClick = onDismiss, enabled = !leaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_app_cancel), color = colors.inkSecondary)
            }
        }
    }
}
