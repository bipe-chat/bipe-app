// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.PeopleService
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeGroupLink
import chat.bipe.shared.api.BipeGroupLinkPreview
import chat.bipe.shared.api.createGroupLink
import chat.bipe.shared.api.groupLinkPreview
import chat.bipe.shared.api.groupLinks
import chat.bipe.shared.api.joinGroupLink
import chat.bipe.shared.api.revokeGroupLink
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.invites.BipeInviteStore
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate

/** Spec group-link (ADR 0184, bipe-chat/bipe#744): one link that brings people into the group. */
class GroupLinkModel(
    context: Context,
    private val session: AccountSession,
    private val roomId: String,
    private val scope: CoroutineScope,
    private val api: BipeApiClient = BipeApiClient(AppConfig.API_BASE_URL),
) {
    enum class Phase { LOADING, NONE, ACTIVE, WORKING, FAILED }

    data class State(
        val phase: Phase = Phase.LOADING,
        val link: BipeGroupLink? = null,
        /** The link URL, when this phone made the link. */
        val url: String? = null,
        val accessError: Boolean = false,
        val noCode: Boolean = false,
        val share: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val store = BipeInviteStore(context, session.userId)
    private val noRoomName = context.getString(R.string.bipe_group_common_no_room_name)
    private val messageFormat = context.getString(R.string.bipe_group_link_message)

    private fun room(): Room? = runCatching { session.client.getRoom(roomId) }.getOrNull()

    /** Action appear. */
    fun appear() {
        _state.update { it.copy(phase = Phase.LOADING) }
        scope.launch {
            try {
                val link = withToken { api.groupLinks(roomId, it) }.firstOrNull()
                val kept = store.groupLink(roomId)
                val url = if (kept != null && kept.first == link?.id) kept.second else null
                if (url == null) store.removeGroupLink(roomId)
                _state.update { it.copy(phase = if (link == null) Phase.NONE else Phase.ACTIVE, link = link, url = url) }
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    /** Action create: the service account comes in with invite power, then the server makes the link. */
    fun create() {
        val room = room() ?: return
        _state.update { it.copy(phase = Phase.WORKING, noCode = false) }
        scope.launch {
            val ready = runCatching {
                val service = BipeRegistries.groupsAccountId
                val membership = runCatching { room.member(service).membership }.getOrNull()
                if (membership != MembershipState.Join && membership != MembershipState.Invite) room.inviteUserById(service)
                val levels = room.getPowerLevels()
                val needed = levels.values().invite
                val current = levels.userPowerLevels()[service] ?: levels.values().usersDefault
                if (current < needed) room.updatePowerLevelsForUsers(listOf(UserPowerLevelUpdate(service, needed)))
            }.isSuccess
            if (!ready) {
                _state.update { it.copy(phase = if (it.link == null) Phase.NONE else Phase.ACTIVE, accessError = true) }
                return@launch
            }
            try {
                val created = withToken { api.createGroupLink(roomId, it) }
                store.saveGroupLink(created.id, created.url, roomId)
                val link = withToken { api.groupLinks(roomId, it) }.firstOrNull()
                _state.update { it.copy(phase = Phase.ACTIVE, link = link, url = created.url, share = message(created.url)) }
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    /** Action share. */
    fun share() {
        val url = _state.value.url
        _state.update { if (url == null) it.copy(noCode = true) else it.copy(share = message(url)) }
    }

    fun shared() = _state.update { it.copy(share = null) }

    /** Action revoke. */
    fun revoke() {
        val link = _state.value.link ?: return
        _state.update { it.copy(phase = Phase.WORKING) }
        scope.launch {
            try {
                withToken { api.revokeGroupLink(link.id, it) }
                store.removeGroupLink(roomId)
                _state.update { it.copy(phase = Phase.NONE, link = null, url = null) }
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    fun dismiss() = _state.update { it.copy(accessError = false, noCode = false) }

    /** The text a person sends (bipe_group_link_message, people voice). */
    private fun message(url: String): String {
        val name = room()?.displayName()?.takeIf { it.isNotBlank() } ?: noRoomName
        return String.format(messageFormat, name, url)
    }

    private fun fail(error: BipeApiException) {
        val code = (error as? BipeApiException.Server)?.code
        if (code == BipeApiErrorCode.NOT_FOUND || code == BipeApiErrorCode.GROUP_LINK_NO_ACCESS) {
            _state.update { it.copy(phase = if (it.link == null) Phase.NONE else Phase.ACTIVE, accessError = true) }
        } else {
            _state.update { it.copy(phase = Phase.FAILED) }
        }
    }

    private suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)
}

private fun shortDate(iso: String): String =
    runCatching { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(iso))) }.getOrDefault(iso)

/** Spec group-link. */
@Composable
fun GroupLinkScreen(session: AccountSession, roomId: String, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { GroupLinkModel(context, session, roomId, scope) }
    LaunchedEffect(model) { model.appear() }
    val state by model.state.collectAsState()
    var confirmsRevoke by remember { mutableStateOf(false) }
    LaunchedEffect(state.share) {
        val text = state.share ?: return@LaunchedEffect
        model.shared()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_LINK.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            GroupTopBar(stringResource(R.string.bipe_group_link_title), onBack = onBack)
            val link = state.link
            when {
                state.phase == GroupLinkModel.Phase.LOADING ->
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.padding(24.dp))
                state.phase == GroupLinkModel.Phase.FAILED -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.bipe_error_network), color = colors.inkPrimary)
                    BipeButton(onClick = model::appear) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                link != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (link.signsUp) stringResource(R.string.bipe_group_link_signs_up, link.signupsLeft.toString())
                        else stringResource(R.string.bipe_group_link_existing_only),
                        color = colors.inkPrimary,
                    )
                    Text(stringResource(R.string.bipe_group_link_expires, shortDate(link.expiresAt)), color = colors.inkSecondary)
                    Text(stringResource(R.string.bipe_group_link_joined, link.joined.toString()), color = colors.inkSecondary)
                    val enabled = state.phase != GroupLinkModel.Phase.WORKING
                    BipeButton(onClick = model::share, enabled = enabled) { Text(stringResource(R.string.bipe_group_link_share)) }
                    BipeOutlinedButton(onClick = { confirmsRevoke = true }, enabled = enabled) { Text(stringResource(R.string.bipe_group_link_revoke)) }
                }
                else -> {
                    GroupRow(PhosphorIcon.LINK, stringResource(R.string.bipe_group_link_create)) {
                        if (state.phase != GroupLinkModel.Phase.WORKING) model.create()
                    }
                    GroupFooter(stringResource(R.string.bipe_group_link_body))
                    GroupFooter(stringResource(R.string.bipe_group_link_service_note))
                }
            }
        }
    }
    if (confirmsRevoke) {
        AlertDialog(
            onDismissRequest = { confirmsRevoke = false },
            title = { Text(stringResource(R.string.bipe_group_link_revoke)) },
            text = { Text(stringResource(R.string.bipe_group_link_revoke_body)) },
            confirmButton = {
                TextButton(onClick = { confirmsRevoke = false; model.revoke() }) { Text(stringResource(R.string.bipe_group_link_revoke)) }
            },
            dismissButton = { TextButton(onClick = { confirmsRevoke = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (state.noCode) {
        AlertDialog(
            onDismissRequest = model::dismiss,
            text = { Text(stringResource(R.string.bipe_group_link_no_code)) },
            confirmButton = { TextButton(onClick = model::create) { Text(stringResource(R.string.bipe_group_link_create)) } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    } else if (state.accessError) {
        AlertDialog(
            onDismissRequest = model::dismiss,
            text = { Text(stringResource(R.string.bipe_group_link_error_access)) },
            confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.bipe_app_close)) } },
        )
    }
}

/** Phase of spec group-link-open. */
private enum class GroupLinkOpenPhase { LOADING, PREVIEW, JOINING, INVALID, REFUSED, FAILED }

/** Spec group-link-open: the group of a bipe.chat/g/<code> link, joined with one tap. */
@Composable
fun GroupLinkOpenScreen(people: PeopleService, code: String, openChat: (String) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(GroupLinkOpenPhase.LOADING) }
    var preview by remember { mutableStateOf<BipeGroupLinkPreview?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        phase = GroupLinkOpenPhase.LOADING
        phase = try {
            val found = people.api.groupLinkPreview(code)
            preview = found
            if (found.valid) GroupLinkOpenPhase.PREVIEW else GroupLinkOpenPhase.INVALID
        } catch (error: BipeApiException) {
            GroupLinkOpenPhase.FAILED
        }
    }

    // Action join: the service account invites, then the app joins the room.
    fun join() {
        if (phase == GroupLinkOpenPhase.JOINING) return
        phase = GroupLinkOpenPhase.JOINING
        scope.launch {
            try {
                val roomId = BipeApiClient.withTokenRetry({ people.session.accessToken() }) { people.api.joinGroupLink(code, it) }
                people.session.client.joinRoomById(roomId)
                openChat(roomId)
            } catch (error: BipeApiException) {
                phase = when ((error as? BipeApiException.Server)?.code) {
                    BipeApiErrorCode.NOT_FOUND -> GroupLinkOpenPhase.INVALID
                    BipeApiErrorCode.GROUP_LINK_NO_ACCESS -> GroupLinkOpenPhase.REFUSED
                    else -> GroupLinkOpenPhase.FAILED
                }
            } catch (error: ClientException) {
                phase = GroupLinkOpenPhase.FAILED
            }
        }
    }

    PeopleScaffold(stringResource(R.string.bipe_app_room_link_opening), BipeScreen.GROUP_LINK_OPEN.id, onClose) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val shown = preview
            when (phase) {
                GroupLinkOpenPhase.LOADING -> {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_app_room_link_opening), color = colors.inkSecondary)
                }
                GroupLinkOpenPhase.INVALID ->
                    Text(stringResource(R.string.bipe_group_link_open_invalid), color = colors.inkPrimary, textAlign = TextAlign.Center)
                GroupLinkOpenPhase.REFUSED ->
                    Text(stringResource(R.string.bipe_app_room_link_invite_only), color = colors.inkPrimary, textAlign = TextAlign.Center)
                GroupLinkOpenPhase.FAILED -> {
                    Text(stringResource(R.string.bipe_error_network), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    BipeButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                GroupLinkOpenPhase.PREVIEW, GroupLinkOpenPhase.JOINING -> {
                    val name = shown?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bipe_group_common_no_room_name)
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary, textAlign = TextAlign.Center)
                    shown?.members?.let { Text(stringResource(R.string.bipe_app_room_link_members, it.toString()), color = colors.inkSecondary) }
                    shown?.inviterName?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.bipe_group_link_open_invited_by, it), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    }
                    BipeButton(onClick = ::join, enabled = phase != GroupLinkOpenPhase.JOINING) { Text(stringResource(R.string.bipe_app_room_link_join)) }
                }
            }
            if (phase != GroupLinkOpenPhase.LOADING) TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
        }
    }
}
