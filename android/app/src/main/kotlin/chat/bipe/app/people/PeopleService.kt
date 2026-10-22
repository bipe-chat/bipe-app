// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import android.content.Context
import chat.bipe.shared.BipeHandleResolver
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.isCompany
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.company.BipeCompanySealsSeen
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.IgnoredUsersListener
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.TaskHandle

/** Type Person of the people specs: the name to show, the photo and the @. Never the Matrix ID (ADR 0013). */
data class Person(
    val userId: String,
    /** The saved contact name, else the profile name, else the @, else the ID's localpart. */
    val displayName: String,
    val avatarUrl: String? = null,
    val handle: String? = null,
) {
    companion object {
        /** The localpart of a Matrix ID, the last fallback of a name (blocked-list notes). */
        fun localpart(userId: String): String = userId.removePrefix("@").substringBefore(':')
    }
}

/** The people screens the home screen can show on top of the chat list. */
sealed interface PeopleRoute {
    data class PersonProfile(val userId: String, val fromDm: Boolean) : PeopleRoute
    data object ProfileEdit : PeopleRoute
    data object PublicProfile : PeopleRoute
    data object ProfileLink : PeopleRoute
    data object ProfileQr : PeopleRoute
    data class ProfileLinkOpen(val handle: String) : PeopleRoute
    data object Contacts : PeopleRoute
    data object WhoCanMessage : PeopleRoute
    data object BlockedList : PeopleRoute
    data class ReportChat(val roomId: String, val isDm: Boolean, val otherUserId: String?) : PeopleRoute
}

class PeopleException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { NOT_FOUND, INVITE_REFUSED }
}

/**
 * People and profiles for one account (bipe-chat/bipe#568): profiles with their @, the block list, company accounts and
 * seals, and the DM with a person. Screens share one instance per account.
 */
class PeopleService(
    context: Context,
    val session: AccountSession,
    val api: BipeApiClient,
    val media: MediaLoader,
    private val scope: CoroutineScope,
) {
    private val serverName = session.userId.substringAfter(':', "")

    /** The @ of Matrix IDs (libraries/bipe, cached; [BipeHandleResolver.handles] emits on change). */
    val handles = BipeHandleResolver(api, serverName, scope, accessToken = { session.accessToken() })

    /** Company seals, verified on the device against the account's master key (UserIdentity.masterKey). Fail closed. */
    val seals = BipeCompanySeals(
        apiClient = api,
        serverName = serverName,
        scope = scope,
        masterKey = { userId -> session.client.encryption().userIdentity(userId, true)?.masterKey() },
        seen = BipeCompanySealsSeen(
            lazy { context.applicationContext.getSharedPreferences("bipe_company_seals", Context.MODE_PRIVATE) },
            session.userId,
        ),
    )

    private val _ignoredUsers = MutableStateFlow<Set<String>?>(null)

    /** Client.ignoredUsers, kept current by Client.subscribeToIgnoredUsers. `null` until the first list arrives. */
    val ignoredUsers: StateFlow<Set<String>?> = _ignoredUsers.asStateFlow()

    private val companies = mutableMapOf<String, Boolean>()
    private var ignoredHandle: TaskHandle? = null

    /** Starts the ignored-users subscription once. */
    fun start() {
        if (ignoredHandle != null) return
        ignoredHandle = session.client.subscribeToIgnoredUsers(object : IgnoredUsersListener {
            override fun call(ignoredUserIds: List<String>) {
                _ignoredUsers.value = ignoredUserIds.toSet()
            }
        })
        scope.launch {
            runCatching { session.client.ignoredUsers() }.getOrNull()?.let { _ignoredUsers.value = it.toSet() }
            if (_ignoredUsers.value == null) _ignoredUsers.value = emptySet()
        }
    }

    fun close() {
        ignoredHandle?.cancel()
        ignoredHandle = null
    }

    fun isBlocked(userId: String): Boolean = _ignoredUsers.value?.contains(userId) == true

    /** Client.ignoreUser. The subscription updates every screen. */
    suspend fun block(userId: String) {
        session.client.ignoreUser(userId)
        _ignoredUsers.value = (_ignoredUsers.value ?: emptySet()) + userId
    }

    /** Client.unignoreUser. */
    suspend fun unblock(userId: String) {
        session.client.unignoreUser(userId)
        _ignoredUsers.value = (_ignoredUsers.value ?: emptySet()) - userId
    }

    /** Calls bipe-api with the account's token, retrying once on 401. */
    suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)

    /** A person: name and photo from Client.getProfile, the @ from bipe-api. [contactName] wins as the name. */
    suspend fun person(userId: String, contactName: String? = null): Person {
        val profile = runCatching { session.client.getProfile(userId) }.getOrNull()
        val handle = handles.resolve(userId)
        if (profile == null && handle == null && contactName == null) throw PeopleException(PeopleException.Kind.NOT_FOUND)
        return Person(userId, displayName(contactName, profile?.displayName, handle, userId), profile?.avatarUrl, handle)
    }

    /** Several people. A profile that does not load still gives a row (the @ or the localpart). */
    suspend fun people(userIds: List<String>): List<Person> {
        return userIds.map { userId ->
            val profile = runCatching { session.client.getProfile(userId) }.getOrNull()
            val handle = handles.resolve(userId)
            Person(userId, displayName(null, profile?.displayName, handle, userId), profile?.avatarUrl, handle)
        }
    }

    /** GET /v1/accounts/{userId}/type, cached: a company's avatar is a rounded square (ADR 0132). A failure is a person. */
    suspend fun isCompany(userId: String): Boolean =
        companies[userId] ?: api.isCompany(userId).also { companies[userId] = it }

    /** The person's identity is verified by the user (an in-person QR or emoji check). */
    suspend fun isConfirmedInPerson(userId: String): Boolean =
        runCatching { session.client.encryption().userIdentity(userId, false)?.isVerified() }.getOrNull() == true

    /** Client.getDmRoom: the newest DM with the person. */
    fun dmRoomId(userId: String): String? = runCatching { session.client.getDmRoom(userId)?.id() }.getOrNull()

    /** The other person of a DM (RoomInfo.isDm, ADR 0141), else `null`. */
    suspend fun dmPartner(roomId: String): String? {
        val info = runCatching { session.client.getRoom(roomId)?.roomInfo() }.getOrNull() ?: return null
        if (!info.isDm) return null
        return info.heroes.map { it.userId }.firstOrNull { it != session.userId }
    }

    /**
     * The DM with the person, created when missing: encrypted, trusted private chat, power levels that let neither person
     * invite or change the room (ADR 0141). Throws INVITE_REFUSED when the person's who-can-message setting refuses it.
     */
    suspend fun openOrCreateDm(userId: String): String {
        dmRoomId(userId)?.let { return it }
        val locked = 101
        val powerLevels = PowerLevels(
            usersDefault = null,
            eventsDefault = null,
            stateDefault = locked,
            ban = null,
            kick = null,
            redact = null,
            invite = locked,
            notifications = null,
            users = mapOf(session.userId to 100, userId to 100),
            events = mapOf("m.room.name" to locked, "m.room.avatar" to locked, "m.room.topic" to locked),
        )
        val parameters = CreateRoomParameters(
            name = null,
            isEncrypted = true,
            isDirect = true,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
            invite = listOf(userId),
            powerLevelContentOverride = powerLevels,
        )
        return try {
            session.client.createRoom(parameters)
        } catch (error: ClientException.MatrixApi) {
            if (error.kind is ErrorKind.Forbidden) throw PeopleException(PeopleException.Kind.INVITE_REFUSED)
            throw error
        }
    }

    /**
     * The dm-delete write (bipe-chat/bipe#464, registries/cleared-chats.json): merges this room's marker, the
     * origin_server_ts of its latest event, into chat.bipe.cleared_chats. Keeps every other entry; the marker never moves back.
     */
    suspend fun clearDm(roomId: String) {
        val room = session.client.getRoom(roomId) ?: throw PeopleException(PeopleException.Kind.NOT_FOUND)
        val latest = when (val event = room.latestEvent()) {
            is LatestEventValue.Remote -> event.timestamp.toLong()
            is LatestEventValue.RemoteInvite -> event.timestamp.toLong()
            is LatestEventValue.Local -> event.timestamp.toLong()
            LatestEventValue.None -> System.currentTimeMillis()
        }
        val eventType = BipeRegistries.CLEARED_CHATS_EVENT_TYPE
        val content = session.client.accountData(eventType)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val rooms = (content["rooms"] as? JsonObject) ?: JsonObject(emptyMap())
        val old = ((rooms[roomId] as? JsonObject)?.get("ts") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
        val marker = if (old != null && old > latest) old else latest
        val newRooms = JsonObject(rooms + (roomId to buildJsonObject { put("ts", marker) }))
        val newContent = JsonObject(content + ("rooms" to newRooms))
        session.client.setAccountData(eventType, newContent.toString())
    }

    companion object {
        fun displayName(contactName: String?, profileName: String?, handle: String?, userId: String): String {
            contactName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            // A profile name that is the raw Matrix ID is no name (ADR 0013).
            profileName?.trim()?.takeIf { it.isNotEmpty() && it != userId }?.let { return it }
            if (handle != null) return "@$handle"
            return Person.localpart(userId)
        }
    }
}
