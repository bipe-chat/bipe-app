// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.business.WorkNotesRooms
import chat.bipe.app.common.serverStateContent
import chat.bipe.app.timeline.AssistantDraftsStore
import chat.bipe.shared.R
import chat.bipe.shared.drafts.BipeAssistantSend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Membership

/**
 * Spec device-detail state fullContentRooms (bipe-chat/bipe#1515, ADR 0130 point 3): the joined chats where this
 * account's assistant reads every member. bipe-mcp sets the room state chat.bipe.assistant, with the account's user ID
 * as state key, before it reads another member's message. The Matrix auth rules let only that user send a state event
 * whose key is its own ID, so the content alone is enough.
 */
class AssistantReadChats(private val session: AccountSession, private val scope: CoroutineScope, private val noName: String) {
    data class Chat(val roomId: String, val name: String)

    sealed interface Phase {
        data object Loading : Phase
        data class Loaded(val chats: List<Chat>) : Phase
        data object Failed : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private var job: Job? = null

    /** The first load, when the detail opens. Coming back keeps the list. */
    fun start() {
        if (job == null) load()
    }

    /** Reads the marker of each joined chat, eight at a time. A newer load cancels the old one. */
    fun load() {
        job?.cancel()
        _phase.value = Phase.Loading
        job = scope.launch {
            _phase.value = try {
                val client = session.client
                val userId = session.userId
                val rooms = client.rooms()
                    .filter { runCatching { it.membership() == Membership.JOINED && !it.isSpace() }.getOrDefault(false) }
                    // Specs drafts and work-notes: never the drafts room or the notes room.
                    .filter { !AssistantDraftsStore.isDraftsRoom(it.id(), userId) && !WorkNotesRooms.isNotesRoom(it.id(), userId) }
                    .map { it.id() to (it.displayName()?.takeIf { name -> name.isNotBlank() } ?: noName) }
                val chats = rooms.chunked(8).flatMap { batch ->
                    coroutineScope {
                        batch.map { (roomId, name) ->
                            async {
                                val content = serverStateContent(client, roomId, BipeAssistantSend.READER_EVENT_TYPE, userId)
                                if (isOn(content)) Chat(roomId, name) else null
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
                Phase.Loaded(chats.sortedBy { it.name.lowercase() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Phase.Failed
            }
        }
    }

    companion object {
        /** The marker is on when its content has the reader field set to true. */
        fun isOn(content: String?): Boolean =
            content != null && runCatching { JSONObject(content).opt(BipeAssistantSend.READER_FIELD) == true }.getOrDefault(false)
    }
}

/** The section of device-detail for kind assistant: the chats it reads in full; each opens the chat (action openChat). */
fun LazyListScope.assistantReadChatsItems(phase: AssistantReadChats.Phase, onRetry: () -> Unit, openChat: (String) -> Unit) {
    when (phase) {
        AssistantReadChats.Phase.Loading -> item(key = "device-reads-loading") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        AssistantReadChats.Phase.Failed -> item(key = "device-reads-failed") {
            Text(
                stringResource(R.string.bipe_device_detail_reads_failed),
                color = BipeTheme.colors.inkPrimary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(R.string.bipe_app_retry), color = BipeTheme.colors.accentInk)
            }
        }
        is AssistantReadChats.Phase.Loaded -> if (phase.chats.isEmpty()) {
            item(key = "device-reads-own-only") {
                Text(
                    stringResource(R.string.bipe_device_detail_reads_own_only),
                    color = BipeTheme.colors.inkPrimary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        } else {
            item(key = "device-reads-all") {
                Text(
                    stringResource(R.string.bipe_device_detail_reads_all),
                    style = MaterialTheme.typography.titleSmall,
                    color = BipeTheme.colors.inkSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(phase.chats, key = { "device-reads-${it.roomId}" }) { chat ->
                Text(
                    chat.name,
                    color = BipeTheme.colors.inkPrimary,
                    modifier = Modifier.fillMaxWidth().clickable { openChat(chat.roomId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item(key = "device-reads-all-footer") {
                Text(
                    stringResource(R.string.bipe_device_detail_reads_all_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = BipeTheme.colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
