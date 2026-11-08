// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import android.os.Build
import android.util.Log
import chat.bipe.app.BipeApplication
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.AccountStep
import chat.bipe.app.account.SessionStore
import chat.bipe.app.common.AppConfig
import chat.bipe.app.notifications.PushRegistration
import chat.bipe.shared.BipePushMessage
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.workMemberSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind

/**
 * The member identity sessions of Interno (spec interno, bipe ADR 0284 points 4 to 6 and 21): one second SDK session
 * under each work account row on this phone. They live apart from the accounts of account-start: their saved sessions
 * are in [SessionStore.MEMBER_FOLDER], so they never show in the account switcher and never count against
 * maxPersonalAccounts. The app keeps no password: the sign-in uses the one-use password of POST
 * /v1/me/business/members/session and forgets it.
 */
class InternoSessions(private val app: BipeApplication) {
    private val prefs = app.getSharedPreferences("bipe.interno", Context.MODE_PRIVATE)
    private val store = SessionStore(app, SessionStore.MEMBER_FOLDER)
    private val mutex = Mutex()
    private var watching = false

    /** Work account user ID to the open session of its member identity. */
    private val _sessions = MutableStateFlow<Map<String, AccountSession>>(emptyMap())
    val sessions: StateFlow<Map<String, AccountSession>> = _sessions.asStateFlow()

    /** Member user ID to the registration time of the pusher it got, so each registration sets its pusher once. */
    private val pushed = mutableMapOf<String, Long>()

    /** The member identity of [workUserId] on this phone, once it signed in here. */
    fun memberOf(workUserId: String): String? = prefs.getString(KEY_MEMBER + workUserId, null)

    /** The work account of a member identity on this phone; null for any other user. */
    fun workAccountOf(memberUserId: String): String? = prefs.all.entries
        .firstOrNull { it.key.startsWith(KEY_MEMBER) && it.value == memberUserId }
        ?.key?.removePrefix(KEY_MEMBER)

    fun session(workUserId: String): AccountSession? = _sessions.value[workUserId]

    fun isActive(session: AccountSession): Boolean = _sessions.value.values.any { it === session }

    /**
     * Follows the accounts of account-start: a work account that opens restores its member session, and a work account
     * that leaves this phone takes its member session with it. Also keeps the member pushers next to the pusher of the
     * current account. Runs once.
     */
    fun watch() {
        if (watching) return
        watching = true
        app.scope.launch { app.accountStart.sessions.collect { accountsChanged(it) } }
        app.scope.launch {
            app.push.registration.combine(_sessions) { registration, sessions -> registration to sessions }
                .collect { (registration, sessions) -> registerPushers(registration, sessions) }
        }
    }

    /** A push or a notification action for a member identity: its session once open. */
    suspend fun awaitMember(memberUserId: String, timeoutMs: Long): AccountSession? {
        watch()
        app.accountStart.start()
        return withTimeoutOrNull(timeoutMs) {
            _sessions.first { map -> map.values.any { it.userId == memberUserId } }.values.first { it.userId == memberUserId }
        }
    }

    /**
     * Action open of spec interno: the member session of [work]. A saved session restores. Else this device asks for a
     * one-use password, signs in with m.login.password on the work account's homeserver and forgets the password.
     * Throws when neither works now.
     */
    suspend fun open(work: AccountSession, api: BipeApiClient): AccountSession = mutex.withLock {
        watch()
        _sessions.value[work.userId]?.let { return@withLock it }
        restoreLocked(work)?.let { return@withLock it }
        val grant = BipeApiClient.withTokenRetry({ work.accessToken() }) { api.workMemberSession(it) }
        val old = memberOf(work.userId)
        if (old != null && old != grant.userId) {
            // The seat has a new member identity (the old one was deactivated): its store goes.
            store.delete(old)
            AccountSession.deleteStore(app, old)
        }
        val member = AccountSession.signIn(app, grant.userId, grant.password, store, homeserver(work))
        check(prefs.edit().putString(KEY_MEMBER + work.userId, member.userId).commit()) { "member identity not saved" }
        if (app.accountStart.sessions.value.none { it.userId == work.userId }) {
            // The work account left this phone during the sign-in.
            drop(work.userId, member, alreadyLoggedOut = false)
            error("work account gone")
        }
        attach(work.userId, member)
        member
    }

    /** Signs the member session of [workUserId] out of this phone: a member identity that no longer matches the seat. */
    suspend fun reset(workUserId: String) {
        mutex.withLock { _sessions.value[workUserId]?.let { drop(workUserId, it, alreadyLoggedOut = false) } }
    }

    private suspend fun accountsChanged(accounts: List<AccountSession>) {
        val ids = accounts.map { it.userId }.toSet()
        // A work account that left this phone (sign-out, removal from Quem atende) takes its member session.
        _sessions.value.filterKeys { it !in ids }.forEach { (work, member) ->
            mutex.withLock { if (_sessions.value[work] === member) drop(work, member, alreadyLoggedOut = false) }
        }
        accounts.filter { memberOf(it.userId) != null && it.userId !in _sessions.value }.forEach { work ->
            app.scope.launch { mutex.withLock { if (work.userId !in _sessions.value) restoreLocked(work) } }
        }
    }

    /** Restores the saved member session of [work]; null when there is none or it does not open now. */
    private suspend fun restoreLocked(work: AccountSession): AccountSession? {
        val memberId = memberOf(work.userId) ?: return null
        val saved = store.load(memberId) ?: return null
        return try {
            AccountSession.restore(app, saved, store).also { attach(work.userId, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountStep.NoStore) {
            store.delete(memberId)
            null
        } catch (e: Exception) {
            Log.w(TAG, "member session not restored now")
            null
        }
    }

    private fun attach(workUserId: String, member: AccountSession) {
        member.onAuthError = { isSoftLogout -> app.scope.launch { authError(workUserId, member, isSoftLogout) } }
        _sessions.update { it + (workUserId to member) }
    }

    /**
     * The server refused the member session's token. A soft logout signs in again on the same store and device with a
     * new one-use password. When the person left Quem atende, bipe-api deactivated the member identity and refuses the
     * password: the session goes from this phone (spec interno notes). Offline, the session waits for the next start.
     */
    private suspend fun authError(workUserId: String, member: AccountSession, isSoftLogout: Boolean) = mutex.withLock {
        if (_sessions.value[workUserId] !== member) return@withLock
        val work = app.accountStart.sessions.value.firstOrNull { it.userId == workUserId }
        if (!isSoftLogout || work == null) {
            drop(workUserId, member, alreadyLoggedOut = true)
            return@withLock
        }
        member.pause()
        try {
            val grant = BipeApiClient.withTokenRetry({ work.accessToken() }) { app.accountStart.api.workMemberSession(it) }
            if (grant.userId != member.userId) {
                drop(workUserId, member, alreadyLoggedOut = true)
                return@withLock
            }
            val fresh = AccountSession.signInAgain(app, member, grant.password, store, homeserver(work))
            app.notifications.detach(member.userId)
            pushed.remove(member.userId)
            attach(workUserId, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BipeApiException.Network) {
            Log.w(TAG, "member session: sign-in again failed offline, kept for the next start")
            _sessions.update { it - workUserId }
        } catch (e: Exception) {
            Log.i(TAG, "member session ended")
            drop(workUserId, member, alreadyLoggedOut = true)
        }
    }

    /** Removes the member session, its store and its saved session from this phone. Never throws. */
    private suspend fun drop(workUserId: String, member: AccountSession, alreadyLoggedOut: Boolean) {
        _sessions.update { map -> if (map[workUserId] === member) map - workUserId else map }
        pushed.remove(member.userId)
        app.notifications.detach(member.userId)
        if (app.roomToOpen.value?.accountUserId == member.userId) app.roomToOpen.value = null
        try {
            member.signOut(app, store, app.accountStart.vault, alreadyLoggedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "member session: local cleanup failed")
        }
        if (memberOf(workUserId) == member.userId) prefs.edit().remove(KEY_MEMBER + workUserId).apply()
    }

    /**
     * Spec interno notes, push: each member session has its own pusher, so Interno rings per person. The pusher follows
     * the work account's: it is set (append, so the account's pusher stays) while the work account holds this phone's
     * pusher, and set again after each registration of the account's pusher, which removes other users' pushers.
     */
    private fun registerPushers(registration: PushRegistration.Registration?, sessions: Map<String, AccountSession>) {
        if (registration == null) return
        sessions.forEach { (workUserId, member) ->
            if (registration.userId != workUserId || pushed[member.userId] == registration.registeredAt) return@forEach
            pushed[member.userId] = registration.registeredAt
            val payload = JSONObject().put(BipePushMessage.ACCOUNT_ROUTE, app.notifications.accountRoute(member.userId)).toString()
            app.scope.launch {
                runCatching {
                    member.client.setPusher(
                        identifiers = PusherIdentifiers(registration.pushkey, registration.appId),
                        kind = PusherKind.Http(HttpPusherData(AppConfig.PUSH_GATEWAY_URL, PushFormat.EVENT_ID_ONLY, payload)),
                        appDisplayName = app.getString(R.string.bipe_app_name),
                        deviceDisplayName = Build.MODEL,
                        profileTag = null,
                        lang = registration.lang,
                        append = true,
                    )
                }.onFailure { if (pushed[member.userId] == registration.registeredAt) pushed.remove(member.userId) }
            }
        }
    }

    /** The homeserver of the work account: the member identity lives on the same one. */
    private fun homeserver(work: AccountSession): String =
        runCatching { work.client.session().homeserverUrl }.getOrNull() ?: AppConfig.HOMESERVER

    companion object {
        private const val TAG = "interno"
        private const val KEY_MEMBER = "member."

        /** Joins the rooms the member identity is invited to. The homeserver lets only members of the account invite it. */
        suspend fun acceptInvites(member: AccountSession, from: Set<String>) {
            runCatching { member.client.rooms() }.getOrDefault(emptyList())
                .filter { runCatching { it.membership() == Membership.INVITED }.getOrDefault(false) }
                .forEach { room ->
                    // Fail closed on the client too: only invites from a member of this account.
                    val inviter = runCatching { room.inviter()?.userId }.getOrNull()
                    if (inviter != null && inviter in from) runCatching { room.join() }
                }
        }
    }
}
