// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.ProfileFactsLines
import chat.bipe.app.settings.SettingsKeys
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeServers
import chat.bipe.shared.R
import chat.bipe.shared.BipeNameLookalikes
import chat.bipe.shared.BipePhotoMatch
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.inviteConnections
import chat.bipe.shared.invites.BipeInviteTrust
import chat.bipe.shared.company.BipeCompanySealState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomInfo

/** Type ChatRequestSummary of spec chat-requests, also used for the group invites of chat-list-filters. */
data class ChatRequestSummary(
    val roomId: String,
    val inviterId: String,
    /** The inviter's name for a request, the group name for a group invite. */
    val name: String,
    val inviterName: String?,
    val avatarUrl: String?,
    val trust: Trust,
    val invitedAt: Long?,
    val isDm: Boolean = false,
) {
    enum class Trust { VERIFIED, UNVERIFIED, EXTERNAL }
}

/** At most this many contact avatars are hashed for one request (spec chat-request photoMatch). */
private const val PHOTO_MATCH_LIMIT = 200

/** Specs chat-requests and chat-request, and the group invites of chat-list-filters. */
class ChatRequestsModel(private val context: Context, val session: AccountSession, private val api: BipeApiClient,
                        private val scope: CoroutineScope, val people: PeopleService) {
    private val _requests = MutableStateFlow<List<ChatRequestSummary>>(emptyList())
    private val _groupInvites = MutableStateFlow<List<ChatRequestSummary>>(emptyList())
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    private val _error = MutableStateFlow(false)

    private val _dmPartnerNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** People with a joined DM, peer user ID → the name the chat list shows. Memory only, this account. */
    val dmPartnerNames: StateFlow<Map<String, String>> = _dmPartnerNames.asStateFlow()

    fun rememberDmPartners(chats: List<ChatSummary>) {
        val names = LinkedHashMap<String, String>()
        val avatars = LinkedHashMap<String, String>()
        for (chat in chats) if (chat.isDm) chat.peerUserId?.let { peer ->
            if (names.putIfAbsent(peer, chat.name) == null) chat.avatarUrl?.let { avatars[peer] = it }
        }
        _dmPartnerNames.value = names
        dmPartnerAvatars = avatars
    }

    /** Avatars of people with a joined DM, peer user ID → mxc URL (spec chat-request photoMatch). */
    @Volatile private var dmPartnerAvatars: Map<String, String> = emptyMap()
    /** dHash per avatar mxc URL, memory only (ADR 0049: no image or hash leaves the phone). */
    private val photoHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Spec chat-request photoMatch (ADR 0049): the contact whose avatar matches an unverified inviter's avatar. */
    suspend fun photoMatch(request: ChatRequestSummary, verified: Boolean): String? {
        if (verified || request.trust == ChatRequestSummary.Trust.EXTERNAL) return null
        val inviterAvatar = request.avatarUrl ?: return null
        val inviterHash = photoHash(inviterAvatar) ?: return null
        for ((peer, avatar) in dmPartnerAvatars.entries.take(PHOTO_MATCH_LIMIT)) {
            if (peer == request.inviterId || avatar == inviterAvatar) continue
            val hash = photoHash(avatar) ?: continue
            if (BipePhotoMatch.matches(inviterHash, hash)) return _dmPartnerNames.value[peer]
        }
        return null
    }

    private suspend fun photoHash(mxcUrl: String): String? {
        photoHashes[mxcUrl]?.let { return it }
        val image = people.media.thumbnail(mxcUrl, 64) ?: return null
        val hash = BipePhotoMatch.hash(BipePhotoMatch.grid(image.asAndroidBitmap())) ?: return null
        photoHashes[mxcUrl] = hash
        return hash
    }

    /**
     * Spec chat-request contactCollision (security rule 8): the contact name that the inviter's name copies.
     * A verified inviter shows the seal instead. Contacts: joined DMs and address-book matches, never the inviter.
     */
    fun contactCollision(request: ChatRequestSummary, verified: Boolean): String? {
        if (verified || request.trust == ChatRequestSummary.Trust.EXTERNAL) return null
        val names = _dmPartnerNames.value.filterKeys { it != request.inviterId }.values +
            people.contactNames.value.filterKeys { it != request.inviterId }.values
        return BipeNameLookalikes.contactCollision(request.name, names)
    }

    /** Requests: DM invites, newest first. They never show in chat-list. */
    val requests: StateFlow<List<ChatRequestSummary>> = _requests.asStateFlow()

    /** Group invites, newest first, for the top of the groups list. */
    val groupInvites: StateFlow<List<ChatRequestSummary>> = _groupInvites.asStateFlow()

    /** Rows that left the box at once while their action runs (offline-first). */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private var invitePairs = emptySet<String>()
    private var dmRooms = emptyList<Room>()
    private var lastPairAttempt = 0L
    private var checkingPairs = false
    private var trustJob: Job? = null
    private var pairJob: Job? = null
    private val dmInvites = FilteredRoomList(scope) { rooms -> dmRooms = rooms; _requests.value = dmSummaries(rooms) }
    private val groupInviteList = FilteredRoomList(scope) { rooms -> _groupInvites.value = summaries(rooms) }
    private var started = false

    /** The privacy switch bipe_privacy_requests_hide_photos (settings-group, kept on the phone). */
    val hidePhotos: Boolean
        get() = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, true)

    fun start() {
        if (started) return
        started = true
        trustJob = scope.launch {
            loadInvitePairs()
            // Both lists hold every invite; requests keep DM invites, group invites the rest (isDirectInvite).
            // Not the Category filter: it reads m.direct, which the invitee writes only on join, so a DM invite is a group.
            dmInvites.start(session, inviteFilter())
            groupInviteList.start(session, inviteFilter())
            while (isActive) {
                delay(30_000)
                if (_requests.value.isNotEmpty()) refreshTrust()
            }
        }
    }

    fun close() {
        trustJob?.cancel()
        pairJob?.cancel()
        dmInvites.close()
        groupInviteList.close()
    }

    /**
     * ADR 0034: use only this account's cached/API-confirmed pairs; an API outage keeps the cache.
     * minimumIntervalMs: time since the last API call. A DM invite from someone not in the cache asks again
     * after 5 seconds, so the invite from a person who just joined with this account's invite joins at once.
     */
    private suspend fun loadInvitePairs(minimumIntervalMs: Long = 60_000) {
        if (checkingPairs) return
        checkingPairs = true
        try {
            invitePairs = withContext(Dispatchers.IO) {
                context.getSharedPreferences("bipe_native_invite_joins", Context.MODE_PRIVATE)
                    .getStringSet("pairs_${session.userId}", emptySet()).orEmpty().toSet()
            }
            val now = System.currentTimeMillis()
            if (now - lastPairAttempt < minimumIntervalMs) return
            lastPairAttempt = now
            pairJob = scope.launch {
                try {
                    val confirmed = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.inviteConnections(it) }.toSet()
                    invitePairs = confirmed
                    withContext(Dispatchers.IO) {
                        context.getSharedPreferences("bipe_native_invite_joins", Context.MODE_PRIVATE).edit()
                            .putStringSet("pairs_${session.userId}", confirmed).apply()
                    }
                    _requests.value = dmSummaries(dmRooms)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* A failed refresh never trusts an unconfirmed account. */ }
            }
        } finally { checkingPairs = false }
    }

    suspend fun refreshTrust() { _requests.value = dmSummaries(dmRooms) }

    private suspend fun dmSummaries(rooms: List<Room>): List<ChatRequestSummary> {
        loadInvitePairs()
        val visible = mutableListOf<ChatRequestSummary>()
        for (room in rooms) {
            currentCoroutineContext().ensureActive()
            val request = summary(room) ?: continue // Ignored inviters are excluded before pair trust.
            if (!request.isDm) continue
            if (BipeInviteTrust.acceptsAutomatically(request.isDm, request.inviterId, false, invitePairs)) {
                if (request.roomId in _pending.value) continue
                if (run(request.roomId) { it.join() }) continue
            }
            visible += request
        }
        if (visible.any { it.isDm && it.inviterId.isNotEmpty() && it.inviterId !in invitePairs }) {
            // The API answer runs dmSummaries again, which joins the invite of a new pair.
            loadInvitePairs(minimumIntervalMs = 5_000)
        }
        return visible.sortedByDescending { it.invitedAt ?: 0 }
    }

    private fun inviteFilter() = RoomListEntriesDynamicFilterKind.All(
        listOf(
            RoomListEntriesDynamicFilterKind.Invite,
            RoomListEntriesDynamicFilterKind.NonSpace,
            RoomListEntriesDynamicFilterKind.DeduplicateVersions,
        ),
    )

    private suspend fun summaries(rooms: List<Room>): List<ChatRequestSummary> =
        rooms.mapNotNull { summary(it) }.filter { !it.isDm }.sortedByDescending { it.invitedAt ?: 0 }

    private suspend fun summary(room: Room): ChatRequestSummary? {
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return null
        val inviter = info.inviter
        // A request from a blocked person never shows (Matrix ignore list).
        if (inviter?.isIgnored == true) return null
        val invitedAt = (room.latestEvent() as? LatestEventValue.RemoteInvite)?.timestamp?.toLong()
        val inviterId = inviter?.userId.orEmpty()
        val inviterName = inviter?.displayName?.takeIf { it.isNotBlank() }
            ?: inviterId.takeIf { it.isNotEmpty() }?.let { chat.bipe.app.people.Person.localpart(it) }
        val isDm = isDirectInvite(info)
        return ChatRequestSummary(
            roomId = info.id,
            inviterId = inviterId,
            name = if (isDm) inviterName ?: context.getString(R.string.bipe_dm_no_name)
                else info.displayName ?: context.getString(R.string.bipe_tab_groups),
            inviterName = inviterName,
            avatarUrl = if (isDm) inviter?.avatarUrl else info.avatarUrl,
            // The verified seal needs the badge check (GET /v1/badges), which comes with the trust work of people and
            // profiles (bipe-chat/bipe#568). Until then: external for a server outside bipe-servers.json (ADR 0168), else
            // unverified.
            trust = if (inviterId.isEmpty() || BipeServers.isBipeUserId(inviterId)) {
                ChatRequestSummary.Trust.UNVERIFIED
            } else {
                ChatRequestSummary.Trust.EXTERNAL
            },
            invitedAt = invitedAt,
            isDm = isDm,
        )
    }

    /**
     * Spec chat-requests: a DM invite. RoomInfo.isDm reads m.direct, which the invitee writes only when joining, so
     * it is false for every invite. The invite's own is_direct (RoomInfo.isDirect) and at most two people decide.
     * After the join the SDK writes m.direct, and RoomInfo.isDm stays the one DM test (ADR 0141).
     */
    private fun isDirectInvite(info: RoomInfo): Boolean =
        info.isDirect && info.activeMembersCount - minOf(info.activeServiceMembersCount, info.activeMembersCount) <= 2uL

    /** Action accept: joins the room. Returns the room ID to open in chat-timeline, or null after a failure. */
    suspend fun accept(roomId: String): String? = if (run(roomId) { it.join() }) roomId else null

    /** Action decline: a plain leave, so the inviter can ask again later. */
    suspend fun decline(roomId: String) = run(roomId) { it.leave() }

    /** Action declineAndBlock: leave, then add the inviter to the ignore list. */
    suspend fun declineAndBlock(request: ChatRequestSummary) = run(request.roomId) {
        it.leave()
        if (request.inviterId.isNotEmpty()) session.client.ignoreUser(request.inviterId)
    }

    /** Action report: Room.reportRoom with no reason text, then leave. */
    suspend fun report(roomId: String) = run(roomId) {
        it.reportRoom("")
        it.leave()
    }

    /** Offline-first: the row leaves at once; a failure brings it back with bipe_error_generic. */
    private suspend fun run(roomId: String, action: suspend (Room) -> Unit): Boolean {
        _pending.update { it + roomId }
        _error.value = false
        return try {
            val room = session.client.getRoom(roomId) ?: throw IllegalStateException("no room")
            action(room)
            true
        } catch (failure: Exception) {
            _pending.update { it - roomId }
            _error.value = true
            false
        }
    }
}

/** The requests row at the top of Conversas (spec chat-requests, rowCount). Hidden at 0. */
@Composable
fun ChatRequestsRow(count: Int, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(PhosphorIcon.USER_CIRCLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bipe_requests_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_requests_row_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.inkOnAccent,
            modifier = Modifier.background(colors.accentFill, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** The trust line of a request: verified seal, unverified or external. */
@Composable
fun TrustLine(trust: ChatRequestSummary.Trust) {
    val colors = BipeTheme.colors
    val (text, color) = when (trust) {
        ChatRequestSummary.Trust.VERIFIED -> R.string.bipe_badge_verified to colors.trustVerifiedInk
        ChatRequestSummary.Trust.UNVERIFIED -> R.string.bipe_banner_unverified_title to colors.trustCautionInk
        ChatRequestSummary.Trust.EXTERNAL -> R.string.bipe_banner_external to colors.trustNeutralInk
    }
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = color)
}

/** Spec chat-requests: the box of chat requests. */
@Composable
fun ChatRequestsScreen(
    model: ChatRequestsModel,
    media: MediaLoader,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenWhoCanMessage: () -> Unit,
) {
    val colors = BipeTheme.colors
    val requests by model.requests.collectAsState()
    val pending by model.pending.collectAsState()
    var open by remember { mutableStateOf<ChatRequestSummary?>(null) }
    val visible = requests.filter { it.roomId !in pending }
    val current = open
    if (current != null) {
        ChatRequestScreen(model, current, media, onBack = { open = null }, onOpenChat = onOpenChat)
        return
    }
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize().testTag(BipeScreen.CHAT_REQUESTS.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back), tint = colors.glyphPrimary)
                }
                Text(stringResource(R.string.bipe_requests_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.bipe_app_requests_empty), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
                    }
                } else {
                    items(visible, key = { it.roomId }) { request ->
                        // Action openRequest.
                        Row(
                            Modifier.fillMaxWidth().clickable { open = request }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // hidePhotos: the initials avatar, never the photo, until accepted.
                            Avatar(request.name, request.avatarUrl, media, userId = request.inviterId, showsPhoto = !model.hidePhotos)
                            Column {
                                Text(request.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1)
                                TrustLine(requestTrust(model, request).kind)
                            }
                        }
                    }
                }
                item(key = "who-can-message") {
                    // Action openWhoCanMessage.
                    TextButton(onClick = onOpenWhoCanMessage, modifier = Modifier.padding(8.dp)) {
                        Text(stringResource(R.string.bipe_messaging_title))
                    }
                }
            }
        }
    }
}

/** Spec chat-request: who wants to talk, with their trust facts, before the person answers. */
@Composable
fun ChatRequestScreen(
    model: ChatRequestsModel,
    request: ChatRequestSummary,
    media: MediaLoader,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val error by model.error.collectAsState()
    val handles by model.people.handles.handles.collectAsState()
    LaunchedEffect(request.inviterId) { model.people.handles.resolve(request.inviterId) }
    var isWorking by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    fun act(block: suspend () -> Unit) {
        isWorking = true
        scope.launch {
            block()
            isWorking = false
        }
    }
    Surface(Modifier.fillMaxSize().testTag(BipeScreen.CHAT_REQUEST.id), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            IconButton(onClick = onBack) {
                Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back), tint = colors.glyphPrimary)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(request.name, request.avatarUrl, media, size = 72.dp, userId = request.inviterId, showsPhoto = !model.hidePhotos)
                Text(request.name, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary, textAlign = TextAlign.Center)
                handles[request.inviterId]?.let { handle ->
                    Text("@$handle", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
                // Spec chat-request profileFacts (ADR 0049), under the header. Never blocks accept or decline.
                ProfileFactsLines(model.people, request.inviterId)
                val trust = requestTrust(model, request)
                TrustBlock(trust)
                RequestNameWarning(model.people, request.inviterId, trust.verifiedName, trust.identityKey)
                val dmNames by model.dmPartnerNames.collectAsState()
                val contactNames by model.people.contactNames.collectAsState()
                remember(request, trust.verifiedName, dmNames, contactNames) { model.contactCollision(request, trust.verifiedName != null) }
                    ?.let { ContactCollisionCard(stringResource(R.string.bipe_warning_not_your_contact, it)) }
                // ADR 0049 photo match: the avatar is the same as a contact's.
                val photoMatch by produceState<String?>(null, request, trust.verifiedName, dmNames) {
                    value = model.photoMatch(request, trust.verifiedName != null)
                }
                photoMatch?.let { ContactCollisionCard(stringResource(R.string.bipe_warning_photo_match, it)) }
                if (error) Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk)
                if (reported) Text(stringResource(R.string.bipe_dm_reported_and_left), color = colors.inkSecondary, textAlign = TextAlign.Center)
                val enabled = !isWorking && !reported
                // Action accept.
                BipeButton(onClick = { act { model.accept(request.roomId)?.let(onOpenChat) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_request_accept))
                }
                // Action decline.
                BipeOutlinedButton(onClick = { act { if (model.decline(request.roomId)) onBack() } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_request_decline))
                }
                // Action askDeclineAndBlock.
                TextButton(onClick = { confirmBlock = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_app_request_decline_block), color = colors.trustDangerInk)
                }
                Text(stringResource(R.string.bipe_dm_decline_block_description), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
                // Action report.
                TextButton(onClick = { act { reported = model.report(request.roomId) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_dm_report), color = colors.trustDangerInk)
                }
                Text(stringResource(R.string.bipe_dm_decline_report_description), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
        }
    }
    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(stringResource(R.string.bipe_app_request_decline_block)) },
            text = { Text(stringResource(R.string.bipe_dm_decline_block_alert, request.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    act { if (model.declineAndBlock(request)) onBack() }
                }) { Text(stringResource(R.string.bipe_app_request_decline_block), color = colors.trustDangerInk) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
}

private data class RequestTrust(val kind: ChatRequestSummary.Trust, val verifiedName: String? = null, val identityKey: String? = null)

/** Observe verified payload changes, and recheck expiry while this request stays visible. */
@Composable
private fun requestTrust(model: ChatRequestsModel, request: ChatRequestSummary): RequestTrust {
    val seals by model.people.seals.states.collectAsState()
    val tick by produceState(0L, model, request.inviterId) {
        while (isActive) { delay(30_000); value += 1 }
    }
    return remember(request.inviterId, request.trust, seals[request.inviterId], tick) {
        if (request.trust == ChatRequestSummary.Trust.EXTERNAL) RequestTrust(ChatRequestSummary.Trust.EXTERNAL)
        else {
            val seal = model.people.seals.seal(request.inviterId)
            if (seal is BipeCompanySealState.Verified && seal.payload.name.isNotBlank()) {
                RequestTrust(ChatRequestSummary.Trust.VERIFIED, seal.payload.name, seal.payload.msk?.let { "master:$it" })
            } else RequestTrust(ChatRequestSummary.Trust.UNVERIFIED)
        }
    }
}

@Composable
private fun TrustBlock(trust: RequestTrust) {
    val colors = BipeTheme.colors
    val verified = trust.kind == ChatRequestSummary.Trust.VERIFIED
    Column(
        Modifier.fillMaxWidth()
            .background(if (verified) colors.trustVerifiedWash else colors.trustCautionWash, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (trust.kind) {
            ChatRequestSummary.Trust.VERIFIED ->
                Text(stringResource(R.string.bipe_badge_verified_name, requireNotNull(trust.verifiedName)), color = colors.trustVerifiedInk, textAlign = TextAlign.Center)
            ChatRequestSummary.Trust.UNVERIFIED -> {
                Text(stringResource(R.string.bipe_banner_unverified_title), style = MaterialTheme.typography.titleSmall, color = colors.trustCautionInk)
                Text(stringResource(R.string.bipe_request_unverified_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            ChatRequestSummary.Trust.EXTERNAL ->
                Text(stringResource(R.string.bipe_banner_external), color = colors.trustNeutralInk, textAlign = TextAlign.Center)
        }
    }
}

/** A group invite row at the top of the groups list (spec chat-list-filters). */
@Composable
fun GroupInviteRow(model: ChatRequestsModel, invite: ChatRequestSummary, media: MediaLoader, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(invite.name, invite.avatarUrl, media)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(invite.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1)
            invite.inviterName?.let {
                Text(stringResource(R.string.bipe_group_notification_room_invite_body_with_sender, it), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Action acceptInvite.
                BipeButton(onClick = { scope.launch { model.accept(invite.roomId)?.let(onOpenChat) } }) {
                    Text(stringResource(R.string.bipe_app_request_accept))
                }
                // Action declineInvite.
                BipeOutlinedButton(onClick = { scope.launch { model.decline(invite.roomId) } }) {
                    Text(stringResource(R.string.bipe_app_request_decline))
                }
            }
        }
    }
}

/** Security rule 8: a stranger whose name copies a contact's. Caution, with an icon, not colour alone. */
@Composable
private fun ContactCollisionCard(text: String) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().background(colors.trustCautionWash, shape).border(1.dp, colors.trustCautionLine, shape)
            .padding(16.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(18.dp))
        Text(text, color = colors.trustCautionInk)
    }
}
