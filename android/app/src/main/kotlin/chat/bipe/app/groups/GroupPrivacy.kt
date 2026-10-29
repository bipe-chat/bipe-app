// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.KnockRequest
import org.matrix.rustcomponents.sdk.KnockRequestsListener
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.ruma_events.StateEventType

/** Spec group-privacy (bipe-chat/bipe#772): who can join, older messages for new members, requests to join. */
class GroupPrivacyModel(private val session: AccountSession, private val roomId: String, private val scope: CoroutineScope) {
    enum class Join(val title: Int) {
        INVITE(R.string.bipe_group_privacy_join_invite),
        KNOCK(R.string.bipe_group_privacy_join_knock),
    }

    enum class History(val title: Int) {
        JOINED(R.string.bipe_group_privacy_history_joined),
        SHARED(R.string.bipe_group_privacy_history_shared),
    }

    data class State(
        val join: Join? = null,
        val history: History? = null,
        val requests: List<KnockRequest> = emptyList(),
        val canChangeJoin: Boolean = false,
        val canChangeHistory: Boolean = false,
        val canInvite: Boolean = false,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var handle: TaskHandle? = null

    private fun room(): Room? = runCatching { session.client.getRoom(roomId) }.getOrNull()

    /** Reads the settings and the rights, then listens to the requests to join. */
    fun start() {
        val room = room() ?: return
        scope.launch {
            runCatching { room.roomInfo() }.getOrNull()?.let { info ->
                val join = when (info.joinRule) {
                    is JoinRule.Invite -> Join.INVITE
                    is JoinRule.Knock -> Join.KNOCK
                    else -> null
                }
                val history = when (info.historyVisibility) {
                    RoomHistoryVisibility.Joined -> History.JOINED
                    RoomHistoryVisibility.Shared -> History.SHARED
                    else -> null
                }
                _state.update { it.copy(join = join, history = history) }
            }
            runCatching { room.getPowerLevels() }.getOrNull()?.let { levels ->
                _state.update {
                    it.copy(
                        canChangeJoin = levels.canOwnUserSendState(StateEventType.RoomJoinRules),
                        canChangeHistory = levels.canOwnUserSendState(StateEventType.RoomHistoryVisibility),
                        canInvite = levels.canOwnUserInvite(),
                    )
                }
            }
            if (handle == null) {
                handle = runCatching {
                    room.subscribeToKnockRequests(object : KnockRequestsListener {
                        override fun call(joinRequests: List<KnockRequest>) {
                            _state.update { it.copy(requests = joinRequests) }
                        }
                    })
                }.getOrNull()
            }
        }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    /** Action setJoin. */
    fun setJoin(value: Join) {
        val room = room() ?: return
        if (value == _state.value.join) return
        scope.launch {
            runCatching { room.updateJoinRules(if (value == Join.INVITE) JoinRule.Invite else JoinRule.Knock) }
                .onSuccess { _state.update { it.copy(join = value) } }
                .onFailure { _state.update { it.copy(failed = true) } }
        }
    }

    /** Action setHistory. */
    fun setHistory(value: History) {
        val room = room() ?: return
        if (value == _state.value.history) return
        scope.launch {
            runCatching { room.updateHistoryVisibility(if (value == History.JOINED) RoomHistoryVisibility.Joined else RoomHistoryVisibility.Shared) }
                .onSuccess { _state.update { it.copy(history = value) } }
                .onFailure { _state.update { it.copy(failed = true) } }
        }
    }

    /** Actions accept and decline. */
    fun answer(request: KnockRequest, accept: Boolean) {
        scope.launch {
            runCatching { if (accept) request.actions.accept() else request.actions.decline(null) }
                .onFailure { _state.update { it.copy(failed = true) } }
        }
    }

    fun dismissError() {
        _state.update { it.copy(failed = false) }
    }
}

/** Spec group-privacy. */
@Composable
fun GroupPrivacyScreen(session: AccountSession, media: MediaLoader, roomId: String, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { GroupPrivacyModel(session, roomId, scope) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_PRIVACY.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            GroupTopBar(stringResource(R.string.bipe_group_privacy_title), onBack = onBack)
            GroupSectionHeader(stringResource(R.string.bipe_group_privacy_join))
            GroupPrivacyModel.Join.entries.forEach { value ->
                ChoiceRow(stringResource(value.title), state.join == value, state.canChangeJoin) { model.setJoin(value) }
            }
            GroupSectionHeader(stringResource(R.string.bipe_group_privacy_history))
            GroupPrivacyModel.History.entries.forEach { value ->
                ChoiceRow(stringResource(value.title), state.history == value, state.canChangeHistory) { model.setHistory(value) }
            }
            GroupFooter(stringResource(R.string.bipe_group_privacy_history_footer))
            if (state.requests.isNotEmpty()) {
                GroupSectionHeader(stringResource(R.string.bipe_group_requests))
                state.requests.forEach { request ->
                    val name = request.displayName?.takeIf { it.isNotBlank() } ?: request.userId
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(name, request.avatarUrl, media, size = 40.dp, userId = request.userId)
                            Column {
                                Text(name, color = colors.inkPrimary)
                                request.reason?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                                }
                            }
                        }
                        if (state.canInvite) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BipeButton(onClick = { model.answer(request, true) }) { Text(stringResource(R.string.bipe_group_request_accept)) }
                                BipeOutlinedButton(onClick = { model.answer(request, false) }) { Text(stringResource(R.string.bipe_group_request_decline)) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.failed) {
        AlertDialog(
            onDismissRequest = model::dismissError,
            text = { Text(stringResource(R.string.bipe_error_generic)) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.bipe_app_close)) } },
        )
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (enabled) colors.inkPrimary else colors.inkDisabled, modifier = Modifier.weight(1f))
        // The chosen value: a check mark, not only a colour.
        if (selected) Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(18.dp))
    }
}
