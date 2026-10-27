// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.Context
import android.widget.Toast
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.Person
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeCreatedInvite
import chat.bipe.shared.api.BipeMyInvites
import chat.bipe.shared.api.BipeInvite
import chat.bipe.shared.api.myInvites
import chat.bipe.shared.api.createInvite
import chat.bipe.shared.api.nameInvite
import chat.bipe.shared.api.revokeInvite
import chat.bipe.shared.api.inviteConnections
import chat.bipe.shared.invites.BipeInviteMemory
import chat.bipe.shared.invites.deliverInviteNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.messageEventContentNew

/** Specs invites and invite-note. Kept per account, so joins and first messages work outside this screen too. */
class InvitesModel(private val context: Context, val session: AccountSession, private val api: BipeApiClient, private val scope: CoroutineScope) {
    enum class Phase { LOADING, LOADED, FAILED }
    data class State(
        val phase: Phase = Phase.LOADING,
        val quota: BipeMyInvites? = null,
        val links: Map<String, String> = emptyMap(),
        val notes: Set<String> = emptySet(),
        val created: BipeCreatedInvite? = null,
        val error: String? = null,
    ) {
        val pending: List<BipeInvite> get() = quota?.invites.orEmpty().filter { it.isPending }.sortedByDescending { it.createdAt }
        val joined: List<BipeInvite> get() = quota?.invites.orEmpty().filter { it.isUsed }.sortedByDescending { it.createdAt }
    }
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val memory = BipeInviteMemory(context, session.userId)
    private val prefs = context.getSharedPreferences("bipe_native_invite_joins", Context.MODE_PRIVATE)
    private var busy = false
    private var creating = false
    private var delivering = false
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            refresh()
            while (isActive) {
                delay(30_000)
                // Spec invites, join toast: a pending invite can be used at any moment, so check while one is open.
                val waiting = runCatching { memory.read().notes.isNotEmpty() }.getOrDefault(false)
                if (waiting || _state.value.pending.isNotEmpty()) refresh()
            }
        }
    }
    fun close() { job?.cancel(); job = null }
    fun beginCreate() {
        if (creating) return
        _state.value = _state.value.copy(created = null, error = null)
    }

    suspend fun refresh() {
        if (busy) return
        busy = true
        try {
            val response = withToken { api.myInvites(it) }
            memory.importEarlierNativeStore(response.invites.map { it.id }.toSet())
            val pending = response.invites.filter { it.isPending }.map { it.id }.toSet()
            val active = response.invites.filter { it.isPending || it.isUsed }.map { it.id }.toSet()
            memory.update { it.copy(links = it.links.filterKeys(pending::contains), notes = it.notes.filterKeys(active::contains)) }
            val saved = memory.read()
            _state.value = _state.value.copy(phase = Phase.LOADED, quota = response, links = saved.links, notes = saved.notes.keys)
            val joined = response.invites.filter { it.isUsed && it.joinedUserId != null }
            val key = "seen_${session.userId}"
            val seen = prefs.getStringSet(key, null)?.toSet()
            prefs.edit().putStringSet(key, seen.orEmpty() + joined.map { it.id }).apply()
            if (seen != null) for (invite in joined.filter { it.id !in seen }) {
                val userId = invite.joinedUserId ?: continue
                val name = runCatching { session.client.getProfile(userId).displayName }.getOrNull() ?: Person.localpart(userId)
                Toast.makeText(context, context.getString(R.string.bipe_invites_joined, name), Toast.LENGTH_LONG).show()
            }
            runCatching { withToken { api.inviteConnections(it) } }.getOrNull()?.let {
                prefs.edit().putStringSet("pairs_${session.userId}", it.toSet()).apply()
            }
            retryNotes()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { _state.value = _state.value.copy(phase = Phase.FAILED) }
        finally { busy = false }
    }

    /** Retries keep the result of POST, then repeat only local persistence and the idempotent naming request. */
    suspend fun create(name: String) {
        if (creating) return
        currentCoroutineContext().ensureActive()
        creating = true
        _state.value = _state.value.copy(error = null)
        try {
            val created = _state.value.created ?: withToken { api.createInvite(it) }.also {
                _state.value = _state.value.copy(created = it)
            }
            memory.saveCreated(created.id, created.url, note = "")
            if (name.isNotBlank()) withToken { api.nameInvite(created.id, name.trim(), it) }
            refresh()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error) }
        finally { creating = false }
    }

    suspend fun setName(id: String, name: String) {
        try { withToken { api.nameInvite(id, name.trim(), it) }; refresh() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error) }
    }

    suspend fun revoke(invite: BipeInvite) {
        try { withToken { api.revokeInvite(invite.id, it) }; memory.remove(invite.id); refresh() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error) }
    }

    /** Never creates a room: wait for the existing, joined, encrypted DM and claim the note once. */
    suspend fun retryNotes() {
        if (delivering) return
        delivering = true
        try {
            for (invite in _state.value.joined) {
                try {
                val userId = invite.joinedUserId?.takeUnless { it == session.userId } ?: continue
                if (memory.read().notes[invite.id] == null) continue
                val room = session.client.getDmRoom(userId) ?: continue
                val info = room.roomInfo()
                if (info.membership != Membership.JOINED || !info.isDm || !room.isEncrypted()) continue
                val timeline = room.timeline()
                deliverInviteNote(
                    claim = { memory.takeNote(invite.id) },
                    restore = { memory.restoreNote(invite.id, it) },
                    send = { timeline.send(messageEventContentNew(MessageType.Text(TextMessageContent(it, null)))); Unit },
                )
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* A store or room that is not ready keeps its note for the next check. */ }
            }
            _state.value = _state.value.copy(notes = memory.read().notes.keys)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Keep the last known pending-note state until storage is readable again. */ }
        finally { delivering = false }
    }

    private fun failure(error: Exception) {
        _state.value = _state.value.copy(error = (error as? BipeApiException)?.localizedMessage(context) ?: context.getString(R.string.bipe_error_network))
    }
    private suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)
}
