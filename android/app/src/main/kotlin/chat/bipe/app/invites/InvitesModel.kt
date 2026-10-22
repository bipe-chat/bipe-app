// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.groups.Person
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeInviteStatus
import chat.bipe.shared.api.createInvite
import chat.bipe.shared.api.myInvites
import chat.bipe.shared.api.nameInvite
import chat.bipe.shared.api.revokeInvite
import chat.bipe.shared.invites.BipeInviteStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Type PersonalInvite of spec invites. */
data class PersonalInvite(
    val id: String,
    val status: BipeInviteStatus,
    val name: String?,
    val expiresAt: Instant,
    /** The link kept on this phone. Null when the invite was made on another device. */
    val link: String?,
    /** USED only: who joined. */
    val joined: Person?,
    /** PENDING only: a note waits on this phone (ADR 0136). */
    val hasNote: Boolean,
)

/** A bipe-api call with the account's access token, retried once after a token refresh. */
internal suspend fun <T> AccountSession.authorized(request: suspend (String) -> T): T =
    BipeApiClient.withTokenRetry({ accessToken() }, request)

internal fun Throwable.userMessage(context: Context): String =
    (this as? BipeApiException)?.localizedMessage(context) ?: context.getString(R.string.bipe_error_network)

internal fun Instant.dateText(): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(this)

/** Spec invites (bipe-shared product/screens/invites.json). */
class InvitesModel(
    private val context: Context,
    val session: AccountSession,
    val api: BipeApiClient,
    private val scope: CoroutineScope,
) {
    enum class Phase { LOADING, LOADED, FAILED }

    data class State(
        val available: Int? = null,
        val nextAvailableAt: Instant? = null,
        val pending: List<PersonalInvite> = emptyList(),
        val joined: List<PersonalInvite> = emptyList(),
        val phase: Phase = Phase.LOADING,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val store = BipeInviteStore(context, session.userId)

    /** The text under the create button: the explainer, the count when 5 or fewer are left, or when the next comes. */
    fun footer(state: State): String {
        val explainer = context.getString(R.string.bipe_invites_explainer)
        val available = state.available ?: return explainer
        if (available == 0) {
            val noneLeft = context.getString(R.string.bipe_invites_none_left)
            val next = state.nextAvailableAt ?: return noneLeft
            return noneLeft + " " + context.getString(R.string.bipe_invites_next, next.dateText())
        }
        if (available > LOW_INVITE_COUNT) return explainer
        return explainer + " " + context.getString(R.string.bipe_invites_available, available.toString())
    }

    /** Action appear: GET /v1/me/invites. Drops the kept links and notes of invites that are no longer pending. */
    fun appear() {
        scope.launch {
            runCatching { session.authorized { api.myInvites(it) } }
                .onSuccess { mine ->
                    val pendingIds = mine.invites.filter { it.status == BipeInviteStatus.PENDING }.map { it.id }.toSet()
                    store.keepOnly(pendingIds)
                    val links = store.links()
                    val notes = store.notes()
                    _state.value = State(
                        available = mine.available,
                        nextAvailableAt = mine.nextAvailableAt,
                        pending = mine.invites.filter { it.status == BipeInviteStatus.PENDING }.map {
                            PersonalInvite(it.id, it.status, it.name, it.expiresAt, links[it.id], null, it.id in notes)
                        },
                        joined = mine.invites.filter { it.status == BipeInviteStatus.USED }.map {
                            PersonalInvite(it.id, it.status, it.name, it.expiresAt, null, person(it.joinedUserId), false)
                        },
                        phase = Phase.LOADED,
                    )
                }
                .onFailure { error ->
                    if (_state.value.available == null) _state.value = _state.value.copy(phase = Phase.FAILED) else _error.value = error.userMessage(context)
                }
        }
    }

    /** Action setName: PUT /v1/me/invites/{inviteId}. Empty removes it. */
    fun setName(id: String, name: String) {
        scope.launch {
            runCatching { session.authorized { api.nameInvite(id, name.trim(), it) } }
                .onSuccess { appear() }
                .onFailure { _error.value = it.userMessage(context) }
        }
    }

    /** Action revoke: DELETE /v1/me/invites/{inviteId}, then the link and the note go from this phone. */
    fun revoke(id: String) {
        scope.launch {
            runCatching { session.authorized { api.revokeInvite(id, it) } }
                .onSuccess {
                    store.remove(id)
                    appear()
                }
                .onFailure { _error.value = it.userMessage(context) }
        }
    }

    /** Action openChat: the DM with the person who joined (Client.getDmRoom). */
    fun dmRoomId(userId: String): String? = runCatching { session.client.getDmRoom(userId)?.id() }.getOrNull()

    fun dismissError() {
        _error.value = null
    }

    private suspend fun person(userId: String?): Person? {
        userId ?: return null
        val profile = runCatching { session.client.getProfile(userId) }.getOrNull()
        return Person.of(userId, profile?.displayName, profile?.avatarUrl)
    }

    private companion object {
        const val LOW_INVITE_COUNT = 5
    }
}

/** Spec invite-note (bipe-shared product/screens/invite-note.json). */
class InviteNoteModel(
    private val context: Context,
    private val session: AccountSession,
    private val api: BipeApiClient,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Editing : Phase
        data object Creating : Phase
        data class Created(val link: String, val expiresAt: Instant) : Phase
        data class Failed(val message: String) : Phase
    }

    val name = MutableStateFlow("")
    val note = MutableStateFlow(context.getString(R.string.bipe_invite_note_default))
    private val _phase = MutableStateFlow<Phase>(Phase.Editing)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val store = BipeInviteStore(context, session.userId)

    /** Action create: POST /v1/me/invites, keeps the link and the note on this phone, then names the invite. */
    fun create() {
        if (_phase.value == Phase.Creating) return
        _phase.value = Phase.Creating
        scope.launch {
            runCatching {
                val created = session.authorized { api.createInvite(it) }
                store.saveLink(created.url, created.id)
                note.value.trim().takeIf { it.isNotEmpty() }?.let { store.saveNote(it, created.id) }
                // The invite exists even when the name fails; the user can name it again in invites.
                name.value.trim().takeIf { it.isNotEmpty() }?.let { trimmed ->
                    runCatching { session.authorized { api.nameInvite(created.id, trimmed, it) } }
                }
                created
            }.onSuccess {
                _phase.value = Phase.Created(it.url, it.expiresAt)
            }.onFailure {
                _phase.value = Phase.Failed(it.userMessage(context))
            }
        }
    }
}
