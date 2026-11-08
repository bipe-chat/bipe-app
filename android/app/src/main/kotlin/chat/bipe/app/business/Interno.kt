// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.chatlist.ChatArchiveModel
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatSummary
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.groups.GroupPeople
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.app.voce.NotesRoom
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeChatArchiveStore
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeWorkMembers
import chat.bipe.shared.api.claimWorkGeral
import chat.bipe.shared.api.workMembers
import chat.bipe.shared.generated.BipeScreen
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility

/**
 * Spec interno (bipe-shared product/screens/interno.json, bipe ADR 0284 phase B): the chats between the people of a work
 * account, on their member identities. One model per open Interno screen of [work].
 */
class InternoModel(private val app: BipeApplication, val work: AccountSession) {
    /** State phase of spec interno. */
    enum class Phase { SETUP, READY, FAILED }

    /** One of the other people of the account (state people). */
    data class Person(val userId: String, val name: String)

    data class State(
        val phase: Phase = Phase.SETUP,
        val member: AccountSession? = null,
        val people: List<Person> = emptyList(),
        val geralRoomId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The room list of the member session, made once the session is open. */
    private val _chatList = MutableStateFlow<ChatListModel?>(null)
    val chatList: StateFlow<ChatListModel?> = _chatList.asStateFlow()
    var media: MediaLoader? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("bipe.interno", Context.MODE_PRIVATE)
    private var openJob: Job? = null
    private var inviteJob: Job? = null

    /** Action open: the member session (first open: sign-in), the people, Geral, then the invites while open. */
    fun open() {
        if (openJob?.isActive == true) return
        _state.update { it.copy(phase = if (it.member == null) Phase.SETUP else it.phase) }
        openJob = scope.launch {
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "interno: not opened (${e.javaClass.simpleName})")
                _state.update { it.copy(phase = Phase.FAILED) }
            }
        }
    }

    private suspend fun load() {
        val member = app.interno.open(work, app.accountStart.api)
        val list = members()
        if (list.me != null && list.me != member.userId) {
            // This phone holds an older member identity of the seat: sign it out, and the retry signs in to the new one.
            app.interno.reset(work.userId)
            error("member identity changed")
        }
        if (_chatList.value?.session !== member) startList(member)
        val people = list.members.filter { it.userId != member.userId }.distinctBy { it.userId }.map { Person(it.userId, it.name) }
        _state.update { it.copy(phase = Phase.READY, member = member, people = people, geralRoomId = list.geralRoomId) }
        InternoSessions.acceptInvites(member, people.map { it.userId }.toSet())
        try {
            ensureGeral(member, list)?.let { id -> _state.update { it.copy(geralRoomId = id) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Geral waits for the next open; the direct chats work without it.
            Log.w(TAG, "interno: Geral not ready (${e.javaClass.simpleName})")
        }
        watchInvites(member)
    }

    private fun startList(member: AccountSession) {
        _chatList.value?.close()
        media = MediaLoader(member.client)
        val archive = ChatArchiveModel(BipeChatArchiveStore(File(app.noBackupFilesDir, "ChatArchives"), member.userId), member, scope)
        _chatList.value = ChatListModel(
            member, scope, app.getString(R.string.bipe_app_timeline_undecryptable), app.getString(R.string.bipe_dm_no_name),
            app::getString, NotesRoom(app, member), archive,
        ).also { it.start() }
    }

    /** Default chosen: invites are checked every 15 s while Interno is open, as a new direct chat starts with one. */
    private fun watchInvites(member: AccountSession) {
        inviteJob?.cancel()
        inviteJob = scope.launch {
            while (isActive) {
                delay(INVITE_CHECK_MS)
                InternoSessions.acceptInvites(member, _state.value.people.map { it.userId }.toSet())
            }
        }
    }

    private suspend fun members(): BipeWorkMembers =
        BipeApiClient.withTokenRetry({ work.accessToken() }) { app.accountStart.api.workMembers(it) }

    /**
     * Action ensureGeral. No Geral and two or more members: a private, invite-only, encrypted room with every member,
     * claimed with PUT geral; on 409 geral_exists this device leaves its room and joins the claimed one. Geral exists:
     * join it when invited, and invite each member that is not in it yet. A room made before a failed claim is used
     * again on the next open.
     */
    private suspend fun ensureGeral(member: AccountSession, list: BipeWorkMembers): String? {
        if (list.members.size < 2) return list.geralRoomId
        val others = list.members.map { it.userId }.filter { it != member.userId }.distinct()
        list.geralRoomId?.let { claimed ->
            joinAndInvite(member, claimed, others)
            return claimed
        }
        val pendingKey = "geral.pending.${member.userId}"
        val roomId = prefs.getString(pendingKey, null) ?: member.client.createRoom(CreateRoomParameters(
            name = app.getString(R.string.bipe_interno_geral), isEncrypted = true, isDirect = false,
            visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = others,
        )).also { created ->
            withContext(Dispatchers.IO) { check(prefs.edit().putString(pendingKey, created).commit()) }
        }
        val claimed = try {
            BipeApiClient.withTokenRetry({ work.accessToken() }) { app.accountStart.api.claimWorkGeral(roomId, it) }.geralRoomId
        } catch (error: BipeApiException.Server) {
            if (error.code != BipeApiErrorCode.GERAL_EXISTS) throw error
            members().geralRoomId
        }
        prefs.edit().remove(pendingKey).apply()
        if (claimed != null && claimed != roomId) {
            // Another member's app claimed Geral first.
            runCatching { member.client.getRoom(roomId)?.leave() }
        }
        val geral = claimed ?: return null
        joinAndInvite(member, geral, others)
        return geral
    }

    private suspend fun joinAndInvite(member: AccountSession, roomId: String, others: List<String>) {
        val known = runCatching { member.client.getRoom(roomId) }.getOrNull()
        val room = if (known != null && runCatching { known.membership() }.getOrNull() == Membership.JOINED) known
            else runCatching { member.client.joinRoomById(roomId) }.getOrNull() ?: return
        val members = GroupPeople.members(room) ?: return
        val inRoom = members.filter { it.membership is MembershipState.Join || it.membership is MembershipState.Invite }
            .map { it.userId }.toSet()
        others.filterNot { it in inRoom }.forEach { userId -> runCatching { room.inviteUserById(userId) } }
    }

    /**
     * Action newChat: the direct chat with [userId], made when there is none: private, invite only, encrypted, is_direct.
     * Only a member from people. Null when it cannot open now.
     */
    suspend fun directChat(userId: String): String? {
        val member = _state.value.member ?: return null
        if (_state.value.people.none { it.userId == userId }) return null
        runCatching { member.client.getDmRoom(userId) }.getOrNull()?.let { return it.id() }
        val roomId = runCatching {
            member.client.createRoom(CreateRoomParameters(
                name = null, isEncrypted = true, isDirect = true,
                visibility = RoomVisibility.Private, preset = RoomPreset.TRUSTED_PRIVATE_CHAT, invite = listOf(userId),
            ))
        }.getOrNull() ?: return null
        runCatching { member.client.awaitRoomRemoteEcho(roomId) }
        return roomId
    }

    fun close() {
        _chatList.value?.close()
        _chatList.value = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "interno"
        const val INVITE_CHECK_MS = 15_000L
    }
}

/** The display name of [session], for bipe_interno_row_body and bipe_interno_geral_body. */
@Composable
private fun accountName(session: AccountSession): String {
    val name by produceState("", session) { value = runCatching { session.client.displayName() }.getOrNull().orEmpty() }
    return name
}

/** Spec interno: the row at the top of Conversas of a work account with two or more people. It opens Interno. */
@Composable
fun InternoRow(work: AccountSession, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InternoIcon(PhosphorIcon.USERS_THREE)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bipe_interno_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(
                stringResource(R.string.bipe_interno_row_body, accountName(work)),
                style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InternoIcon(icon: PhosphorIcon) {
    val colors = BipeTheme.colors
    Box(Modifier.size(48.dp).background(colors.accentWash, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(24.dp))
    }
}

/**
 * Spec interno: Geral first, then the direct chats newest first, and the picker of "Nova conversa". A room opens in the
 * normal chat timeline of the member session. [initialRoomId]: a notification of the member identity opened Interno.
 */
@Composable
fun InternoScreen(work: AccountSession, initialRoomId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val model = remember(work) { InternoModel(app, work) }
    DisposableEffect(model) {
        model.open()
        onDispose { model.close() }
    }
    val state by model.state.collectAsState()
    val chatList by model.chatList.collectAsState()
    var openRoomId by rememberSaveable(work.userId) { mutableStateOf(initialRoomId) }
    var picking by rememberSaveable(work.userId) { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val member = state.member
    val media = model.media
    val roomId = openRoomId
    if (member != null && media != null && roomId != null) {
        BackHandler { openRoomId = null }
        // An open chat has no notifications, and its pushes show none.
        DisposableEffect(member, roomId) {
            val target = BipeNotificationTarget.create(member.userId, roomId)
            app.notifications.visibleRoom = target
            target?.let { app.notifications.clear(it) }
            onDispose { if (app.notifications.visibleRoom == target) app.notifications.visibleRoom = null }
        }
        ChatTimelineScreen(
            member, media, roomId,
            onBack = { openRoomId = null },
            onOpenChat = { openRoomId = it },
            topNotice = if (roomId != state.geralRoomId) stringResource(R.string.bipe_interno_private_hint) else null,
        )
        return
    }
    BackHandler { if (picking) picking = false else onClose() }
    Surface(Modifier.fillMaxSize().testTag(BipeScreen.INTERNO.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (picking) picking = false else onClose() }) {
                    Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back), tint = colors.glyphPrimary)
                }
                Text(
                    stringResource(if (picking) R.string.bipe_interno_pick_title else R.string.bipe_interno_title),
                    style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f),
                )
                if (!picking && state.phase == InternoModel.Phase.READY) {
                    TextButton(onClick = { picking = true }) { Text(stringResource(R.string.bipe_interno_new)) }
                }
            }
            when {
                state.phase == InternoModel.Phase.SETUP && member == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_interno_setup), color = colors.inkSecondary)
                }
                state.phase == InternoModel.Phase.FAILED -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Text(stringResource(R.string.bipe_interno_error), color = colors.inkPrimary)
                    BipeButton(onClick = model::open) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                picking -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.people.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.bipe_interno_empty), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(state.people, key = { it.userId }) { person ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button, enabled = !creating) {
                                    creating = true
                                    scope.launch {
                                        val id = model.directChat(person.userId)
                                        creating = false
                                        if (id != null) {
                                            picking = false
                                            openRoomId = id
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(person.name, null, media, userId = person.userId)
                            Text(person.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (creating) CircularProgressIndicator(Modifier.size(20.dp), color = colors.accentFill, strokeWidth = 2.dp)
                        }
                    }
                }
                else -> {
                    val chats = chatList?.chats?.collectAsState()?.value.orEmpty()
                    InternoRooms(chats, state, media, accountName(work), onOpen = { openRoomId = it })
                }
            }
        }
    }
}

@Composable
private fun InternoRooms(chats: List<ChatSummary>, state: InternoModel.State, media: MediaLoader?, accountName: String, onOpen: (String) -> Unit) {
    val colors = BipeTheme.colors
    val geralId = state.geralRoomId
    val geral = chats.firstOrNull { it.roomId == geralId }
    // Interno has no channels (ADR 0284 point 13): every room but Geral is a direct chat.
    val direct = chats.filter { it.roomId != geralId }
    LazyColumn(Modifier.fillMaxSize()) {
        if (geralId != null && geral != null) {
            item(key = "geral") {
                InternoRoomRow(
                    title = stringResource(R.string.bipe_interno_geral),
                    body = geral.lastMessage ?: stringResource(R.string.bipe_interno_geral_body, accountName),
                    unread = geral.isUnread,
                    leading = { InternoIcon(PhosphorIcon.USERS_THREE) },
                    onClick = { onOpen(geralId) },
                )
            }
        }
        if (direct.isEmpty() && state.people.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.bipe_interno_empty), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
            }
        }
        items(direct, key = { it.roomId }) { chat ->
            InternoRoomRow(
                title = chat.name,
                body = chat.lastMessage,
                unread = chat.isUnread,
                leading = { Avatar(chat.name, chat.avatarUrl, media, userId = chat.peerUserId) },
                onClick = { onOpen(chat.roomId) },
            )
        }
        item(key = "end") { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun InternoRoomRow(title: String, body: String?, unread: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary,
                fontWeight = if (unread) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = if (unread) colors.inkPrimary else colors.inkSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
