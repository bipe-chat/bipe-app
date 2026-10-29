// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.settings.openPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.banner.BipeRemoteBanner
import chat.bipe.shared.banner.remoteBanner
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind

/** Spec chat-search: finds a chat or group by name among the person's own chats. Bipe has no directory. */
class ChatSearchModel(private val session: AccountSession, private val list: ChatListModel, private val scope: CoroutineScope, context: Context) {
    data class State(val query: String = "", val results: List<ChatSummary> = emptyList(), val isSearching: Boolean = false) {
        /** findByHandle is offered when the query starts with @ and nothing matched. */
        val offersHandleLookup: Boolean get() = query.startsWith("@") && results.isEmpty() && !isSearching
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val rooms = FilteredRoomList(scope) { found ->
        if (_state.value.query.isNotBlank()) {
            val query = _state.value.query
            val summaries = found.filter { it.id() != list.notes.roomId }.mapNotNull { list.summary(it) }
            val visible = NativeDmSelection.visible(summaries, session)
            if (_state.value.query == query) _state.value = _state.value.copy(results = visible, isSearching = false)
        }
    }
    private var debounce: Job? = null
    /** State messages (bipe-chat/bipe#671). */
    val messages = MessageSearchModel(session, scope, context.getString(R.string.bipe_app_you), context.getString(R.string.bipe_dm_no_name))
    /** State recent. */
    val recent = RecentSearches(context, session.userId)
    private val clearChanges = scope.launch { ClearedChatsStore.revision.collect { rooms.refresh() } }

    /** Action editQuery: sets the name filter, at most every 150 ms. */
    fun edit(text: String) {
        debounce?.cancel()
        val pattern = text.trim()
        _state.value = State(query = text, results = if (pattern.isEmpty()) emptyList() else _state.value.results, isSearching = pattern.isNotEmpty())
        if (pattern.isEmpty()) {
            scope.launch { messages.search("") }
            return
        }
        debounce = scope.launch {
            delay(150)
            launch { messages.search(pattern) }
            // Both tabs together; requests (invites) are left out by Joined.
            rooms.start(
                session,
                RoomListEntriesDynamicFilterKind.All(
                    listOf(
                        RoomListEntriesDynamicFilterKind.Joined,
                        RoomListEntriesDynamicFilterKind.NonSpace,
                        RoomListEntriesDynamicFilterKind.DeduplicateVersions,
                        RoomListEntriesDynamicFilterKind.NormalizedMatchRoomName(pattern),
                    ),
                ),
            )
        }
    }

    /** Action close. */
    fun close() = edit("")

    fun dispose() { clearChanges.cancel(); rooms.close(); messages.close() }
}

/** The search field of chat-list: the top bar on Android (ADR 0053). */
@Composable
fun ChatSearchField(model: ChatSearchModel) {
    val state by model.state.collectAsState()
    OutlinedTextField(
        value = state.query,
        onValueChange = model::edit,
        placeholder = { Text(stringResource(R.string.bipe_app_search_placeholder)) },
        singleLine = true,
        trailingIcon = if (state.query.isEmpty()) null else ({
            IconButton(onClick = model::close) {
                Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_a11y_clear_search), tint = BipeTheme.colors.glyphSecondary)
            }
        }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The results under the search field. */
@Composable
fun ChatSearchResults(
    model: ChatSearchModel,
    media: MediaLoader,
    onOpenChat: (String) -> Unit,
    onFindHandle: (String) -> Unit = {},
    /** Action openMessage (bipe-chat/bipe#671): room ID, event ID. */
    onOpenMessage: (String, String) -> Unit = { _, _ -> },
) {
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    val hits by model.messages.hits.collectAsState()
    val recent by model.recent.queries.collectAsState()
    LazyColumn(Modifier.fillMaxSize().testTag(BipeScreen.CHAT_SEARCH.id)) {
        when {
            state.query.isBlank() -> if (recent.isNotEmpty()) {
                // State recent, while the query is blank.
                item(key = "recent-header") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.bipe_search_recent), style = MaterialTheme.typography.labelLarge, color = colors.accentInk, modifier = Modifier.weight(1f))
                        TextButton(onClick = model.recent::clear) { Text(stringResource(R.string.bipe_search_recent_clear)) }
                    }
                }
                items(recent, key = { "recent-$it" }) { query ->
                    Text(
                        query,
                        color = colors.inkPrimary,
                        modifier = Modifier.fillMaxWidth().clickable { model.edit(query) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            state.isSearching && state.results.isEmpty() && hits.isEmpty() -> item(key = "searching") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
            }
            state.results.isEmpty() && hits.isEmpty() -> item(key = "none") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bipe_app_search_no_results, state.query), color = colors.inkSecondary)
                    if (state.offersHandleLookup) {
                        Text(stringResource(R.string.bipe_app_search_find_handle), color = colors.accentInk,
                            modifier = Modifier.bipeTapTarget().clickable { onFindHandle(state.query) })
                        Text(stringResource(R.string.bipe_start_chat_search_by_handle), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
            }
            else -> {
            items(state.results, key = { it.roomId }) { chat ->
                // Action openChat.
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenChat(chat.roomId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(chat.name, chat.avatarUrl, media, size = 40.dp, userId = chat.peerUserId)
                    Column {
                        Text(chat.name, color = colors.inkPrimary, maxLines = 1)
                        // Each result shows whether it is a chat or a group.
                        Text(
                            stringResource(if (chat.isDm) R.string.bipe_tab_chats else R.string.bipe_tab_groups),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
            // State messages, under the chats.
            if (hits.isNotEmpty()) {
                item(key = "messages-header") {
                    Text(
                        stringResource(R.string.bipe_search_messages),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.accentInk,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                itemsIndexed(hits, key = { _, hit -> "message-${hit.roomId}-${hit.eventId}" }) { index, hit ->
                    if (index == hits.lastIndex) LaunchedEffect(hit.eventId) { model.messages.loadMore() }
                    MessageHitRow(hit) {
                        model.recent.add(state.query)
                        onOpenMessage(hit.roomId, hit.eventId)
                    }
                }
            }
            }
        }
    }
}

/** Spec remote-banner: one banner above the chat list for critical notices only (ADR 0117). */
class RemoteBannerModel(private val context: Context, private val api: BipeApiClient, private val scope: CoroutineScope) {
    data class State(val banner: BipeRemoteBanner? = null, val state: BipeRemoteBanner.State = BipeRemoteBanner.State.HIDDEN)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastFetch = 0L
    private val prefs get() = context.getSharedPreferences("bipe_remote_banner", Context.MODE_PRIVATE)

    /** Action refresh: when the list appears and on foreground, at most once a minute. A failure keeps the last banner. */
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastFetch < 60_000) {
            update(_state.value.banner)
            return
        }
        lastFetch = now
        scope.launch {
            // null: no banner, or past expires_at. A failed fetch keeps the last banner until it expires.
            val banner = runCatching { api.remoteBanner() }.getOrElse { _state.value.banner }
            update(banner)
        }
    }

    private fun update(banner: BipeRemoteBanner?) {
        var state = BipeRemoteBanner.state(banner, appVersion(), Instant.now())
        if (state == BipeRemoteBanner.State.NOTICE && banner?.id == prefs.getString(KEY_DISMISSED, null)) {
            state = BipeRemoteBanner.State.HIDDEN
        }
        _state.value = State(banner, state)
    }

    /** Action dismiss: notice only. That id does not show again on this phone. */
    fun dismiss() {
        val current = _state.value
        if (current.state != BipeRemoteBanner.State.NOTICE) return
        prefs.edit().putString(KEY_DISMISSED, current.banner?.id).apply()
        update(current.banner)
    }

    /** Action open: the store page for update, the in-app browser for notice. */
    fun open(context: Context) {
        val current = _state.value
        val banner = current.banner ?: return
        if (current.state == BipeRemoteBanner.State.UPDATE) {
            val target = Uri.parse(banner.updateLinks?.android ?: banner.link)
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (error: ActivityNotFoundException) {
                openPage(context, target.toString())
            }
        } else {
            openPage(context, banner.link)
        }
    }

    /** The version name (product/version.json), for min_app_version. */
    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private companion object {
        const val KEY_DISMISSED = "dismissed_id"
    }
}

@Composable
fun RemoteBannerCard(model: RemoteBannerModel, context: Context) {
    val colors = BipeTheme.colors
    val current by model.state.collectAsState()
    val banner = current.banner
    if (current.state == BipeRemoteBanner.State.HIDDEN || banner == null) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(colors.trustCautionWash, RoundedCornerShape(12.dp))
            .clickable { model.open(context) }
            .padding(12.dp)
            .testTag(BipeScreen.REMOTE_BANNER.id),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Title and text come from the server in pt-BR and show as they are.
            Text(banner.title, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            Text(banner.text, color = colors.inkPrimary)
            if (current.state == BipeRemoteBanner.State.UPDATE) {
                Text(stringResource(R.string.bipe_remote_banner_update), fontWeight = FontWeight.Bold, color = colors.accentInk)
            }
        }
        if (current.state == BipeRemoteBanner.State.NOTICE) {
            // Action dismiss.
            IconButton(onClick = model::dismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    painterResource(PhosphorIcon.X.drawable),
                    contentDescription = stringResource(R.string.bipe_remote_banner_dismiss),
                    tint = colors.glyphSecondary,
                )
            }
        }
    }
}
