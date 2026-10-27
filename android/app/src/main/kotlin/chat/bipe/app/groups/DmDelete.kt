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
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.serverAccountData
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import chat.bipe.shared.clearedchats.ClearedChats
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Room

/**
 * The cleared-DM markers of the account (spec dm-delete notes). chat-list hides a cleared DM and chat-timeline drops
 * the events at or below its marker. Read from Client.accountData at start and when the app comes to the front.
 */
object ClearedChatsStore {
    private val _markers = MutableStateFlow(ClearedChats.EMPTY)
    val markers: StateFlow<ClearedChats> = _markers.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val cached = mutableMapOf<String, ClearedChats>()
    private val pending = mutableMapOf<String, MutableMap<String, Long>>()
    private var displayedUserId: String? = null
    private var refreshVersion = 0L

    suspend fun refresh(client: Client) {
        val userId = client.userId()
        val version = ++refreshVersion
        if (displayedUserId != userId) {
            displayedUserId = userId
            _markers.value = cached[userId] ?: ClearedChats.EMPTY.mergedWith(pending[userId].orEmpty())
        }
        try {
            val loaded = overlay(ClearedChats.parse(client.accountData(EVENT_TYPE)), userId)
            val changed = cached[userId] != loaded
            cached[userId] = loaded
            if (version != refreshVersion) return
            _markers.value = loaded
            if (changed) _revision.update { it + 1 }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { /* Retain this account's cached markers. */ }
    }

    fun set(userId: String, roomId: String, marker: Long) {
        val account = pending.getOrPut(userId) { mutableMapOf() }
        account[roomId] = maxOf(account[roomId] ?: 0, marker)
        val loaded = (cached[userId] ?: ClearedChats.EMPTY).mergedWith(mapOf(roomId to marker))
        cached[userId] = loaded
        if (displayedUserId == userId) _markers.value = loaded
        _revision.update { it + 1 }
    }

    fun overlay(stored: ClearedChats, userId: String): ClearedChats = stored.mergedWith(pending[userId].orEmpty())

    const val EVENT_TYPE = BipeRegistries.CLEARED_CHATS_EVENT_TYPE
}

/** Spec dm-delete (bipe-shared product/screens/dm-delete.json). */
class DmDeleteModel(
    private val session: AccountSession,
    val roomId: String,
    private val scope: CoroutineScope,
) {
    enum class Phase { CONFIRM, DELETING, FAILED }

    private val _phase = MutableStateFlow(Phase.CONFIRM)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Action delete: read-merge-write of the server copy of chat.bipe.cleared_chats. */
    fun delete(onDeleted: () -> Unit) {
        if (_phase.value == Phase.DELETING) return
        val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return
        _phase.value = Phase.DELETING
        scope.launch {
            runCatching {
                val info = room.roomInfo()
                check(info.isDm && info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED)
                val ts = latestTs(room)
                val content = ClearedChats.contentClearing(serverContent(), roomId, ts)
                session.client.setAccountData(ClearedChatsStore.EVENT_TYPE, content)
                ts
            }.onSuccess { ts ->
                ClearedChatsStore.set(session.userId, roomId, ts)
                onDeleted()
            }.onFailure {
                _phase.value = Phase.FAILED
            }
        }
    }

    /** The origin_server_ts of the latest remote event (Room.latestEvent). */
    private suspend fun latestTs(room: Room): Long = when (val latest = room.latestEvent()) {
        is LatestEventValue.Remote -> latest.timestamp.toLong()
        is LatestEventValue.Local -> latest.timestamp.toLong()
        is LatestEventValue.RemoteInvite -> latest.timestamp.toLong()
        LatestEventValue.None -> System.currentTimeMillis()
    }

    /** The server copy of chat.bipe.cleared_chats (serverAccountData, bipe-chat/bipe#479). */
    private suspend fun serverContent(): String? = serverAccountData(session.client, ClearedChatsStore.EVENT_TYPE)
}

/** Spec dm-delete: a confirm sheet. DM only (RoomInfo.isDm true). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmDeleteSheet(session: AccountSession, roomId: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { DmDeleteModel(session, roomId, scope) }
    val colors = BipeTheme.colors
    val phase by model.phase.collectAsState()
    val deleting = phase == DmDeleteModel.Phase.DELETING
    ModalBottomSheet(
        onDismissRequest = { if (!deleting) onDismiss() },
        containerColor = colors.surfaceRaised,
        modifier = Modifier.testTag(BipeScreen.DM_DELETE.id),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bipe_dm_delete_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_dm_delete_body), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
            when (phase) {
                DmDeleteModel.Phase.DELETING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.bipe_app_dm_deleting), color = colors.inkSecondary)
                }
                DmDeleteModel.Phase.FAILED -> Text(stringResource(R.string.bipe_dm_delete_error), color = colors.trustDangerInk)
                DmDeleteModel.Phase.CONFIRM -> Unit
            }
            BipeButton(
                onClick = { model.delete(onDeleted) },
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(containerColor = colors.trustDangerInk, contentColor = colors.inkOnAccent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bipe_dm_delete_confirm))
            }
            TextButton(onClick = onDismiss, enabled = !deleting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_app_cancel), color = colors.inkSecondary)
            }
        }
    }
}
