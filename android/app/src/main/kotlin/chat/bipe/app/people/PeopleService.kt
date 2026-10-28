// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.groups.DmDeleteModel
import chat.bipe.app.groups.RoomLink
import chat.bipe.app.chatlist.NativeDmSelection
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import chat.bipe.shared.BipeTrustSources
import chat.bipe.shared.BipeHandleResolver
import chat.bipe.shared.api.lookupContacts
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeOpenIdToken
import chat.bipe.shared.api.BipeProfileFacts
import chat.bipe.shared.api.isCompany
import chat.bipe.shared.company.BipeBrandMarks
import chat.bipe.shared.company.BipeBrandMarksSeen
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.company.BipeCompanySealsSeen
import chat.bipe.shared.generated.BipeDmPowerLevels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.IgnoredUsersListener
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
    data object ProfileQr : PeopleRoute
    data object ProfileLink : PeopleRoute
    /** [queue]: the counter QR link `?fila` of a business opens queue-customer for [handle] (spec queue-customer notes). */
    data class ProfileLinkOpen(val handle: String, val queue: Boolean = false) : PeopleRoute
    data object Contacts : PeopleRoute
    data object WhoCanMessage : PeopleRoute
    data object BlockedList : PeopleRoute
    data class ReportChat(val roomId: String, val isDm: Boolean, val otherUserId: String?) : PeopleRoute
    /** A matrix.to or matrix: group link (spec room-link-open). */
    data class RoomLinkOpen(val link: RoomLink) : PeopleRoute
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
    /** The @ of Matrix IDs (libraries/bipe, cached; [BipeHandleResolver.handles] emits on change). */
    val handles = BipeHandleResolver(api, scope, openId = session.openId)

    /** Company seals, verified on the device against the account's master key (UserIdentity.masterKey). Fail closed. */
    val seals = BipeCompanySeals(
        apiClient = api,
        scope = scope,
        masterKey = { userId -> session.client.encryption().userIdentity(userId, true)?.masterKey() },
        seen = BipeCompanySealsSeen(
            lazy { context.applicationContext.getSharedPreferences("bipe_company_seals", Context.MODE_PRIVATE) },
            session.userId,
        ),
    )

    /** Brand marks for resellers (ADR 0162), verified against the same master key as the seal. Fail closed. */
    val brandMarks = BipeBrandMarks(
        apiClient = api,
        scope = scope,
        masterKey = { userId -> session.client.encryption().userIdentity(userId, true)?.masterKey() },
        seen = BipeBrandMarksSeen(
            lazy { context.applicationContext.getSharedPreferences("bipe_brand_marks", Context.MODE_PRIVATE) },
            session.userId,
        ),
    )

    private val _ignoredUsers = MutableStateFlow<Set<String>?>(null)

    /** Client.ignoredUsers, kept current by Client.subscribeToIgnoredUsers. `null` until the first list arrives. */
    val ignoredUsers: StateFlow<Set<String>?> = _ignoredUsers.asStateFlow()

    private val appContext = context.applicationContext
    private val _contactNames = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Names from a permitted contact lookup, in memory for this account only. Request checks send nothing. */
    val contactNames: StateFlow<Map<String, String>> = _contactNames.asStateFlow()

    fun rememberContactNames(names: Map<String, String>) {
        _contactNames.value = if (canReadContacts()) names.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() } else emptyMap()
    }

    fun contactName(userId: String): String? = if (canReadContacts()) _contactNames.value[userId] else null

    /**
     * User ID to the address-book entry ID that the contact lookup matched (ADR 0191, spec person-profile savedNumbers).
     * Memory only, this account. Never a number: person-profile reads the entry again.
     */
    @Volatile private var contactEntries: Map<String, String> = emptyMap()

    /** A lookup of the address book ran in this session, so person-profile does not spend the daily budget again. */
    @Volatile private var addressBookLookedUp = false

    fun rememberContactEntries(entries: Map<String, String>) {
        contactEntries = if (canReadContacts()) entries else emptyMap()
        if (canReadContacts()) addressBookLookedUp = true
    }

    /**
     * Spec person-profile savedNumbers (ADR 0191, bipe-chat/bipe#788): the numbers the user saved for this person, from the
     * user's own address book. Without contacts access or without a match: empty. Without a match in this session, one
     * lookup of the whole address book (as contacts check), once per session.
     */
    suspend fun savedNumbers(userId: String): List<String> {
        if (userId == session.userId) return emptyList()
        if (!canReadContacts()) {
            contactEntries = emptyMap()
            return emptyList()
        }
        if (userId !in contactEntries && !addressBookLookedUp) lookUpAddressBook()
        val entryId = contactEntries[userId] ?: return emptyList()
        return readSavedNumbers(appContext, entryId)
    }

    /**
     * POST /v1/contacts/lookup for the whole address book, in chunks of 500. Keeps the names and entry IDs of the matches.
     * Stops at the first failure and keeps what it has; never runs twice in a session.
     */
    private suspend fun lookUpAddressBook() {
        addressBookLookedUp = true
        val book = readAddressBook(appContext)
        val names = mutableMapOf<String, String>()
        val entries = mutableMapOf<String, String>()
        for (chunk in book.keys.toList().chunked(500)) {
            val found = try {
                withOpenId { api.lookupContacts(chunk, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                break
            }
            found.forEach { match ->
                val entry = book[match.phone] ?: return@forEach
                if (match.userId != session.userId && match.userId !in entries) {
                    names[match.userId] = entry.name
                    entries[match.userId] = entry.id
                }
            }
        }
        rememberContactNames(_contactNames.value + names)
        rememberContactEntries(entries)
    }

    private fun canReadContacts(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Facts read from POST /v1/profile-facts per person, with the read time in ms (ADR 0049). Memory only, this account. */
    private val profileFactsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, BipeProfileFacts?>>()

    /**
     * The profile change facts of a person (ADR 0049). A read younger than [maxAgeMs] comes from memory.
     * `null` when the call fails or the API knows nothing: the screens then show nothing.
     */
    suspend fun profileFacts(userId: String, maxAgeMs: Long = 0): BipeProfileFacts? {
        val now = System.currentTimeMillis()
        profileFactsCache[userId]?.let { (read, facts) -> if (now - read < maxAgeMs) return facts }
        val list = try {
            withOpenId { token -> api.profileFacts(listOf(userId), token) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val facts = list.firstOrNull { it.userId == userId }
        profileFactsCache[userId] = System.currentTimeMillis() to facts
        return facts
    }

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
        _contactNames.value = emptyMap()
        contactEntries = emptyMap()
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

    /** Runs [request] with the OpenID token, for the core routes (bipe-chat/bipe#646). */
    suspend fun <T> withOpenId(request: suspend (BipeOpenIdToken) -> T): T = BipeApiClient.withOpenIdRetry(session.openId, request)

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

    /** The person's cross-signing identity for the trust facts (ADR 0074). null: the SDK could not read it. */
    suspend fun trustIdentity(userId: String): BipeTrustSources.Identity? = try {
        session.client.encryption().userIdentity(userId, true)?.let {
            BipeTrustSources.Identity(it.masterKey(), it.isVerified(), it.hasVerificationViolation())
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Client.getDmRoom: the newest DM with the person. */
    suspend fun dmRoomId(userId: String): String? = try { NativeDmSelection.roomId(userId, session) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }

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
        NativeDmSelection.roomId(userId, session)?.let { return it }
        return createDm(userId)
    }

    /**
     * A new DM with the person (spec person-profile notes). The user is at BipeDmPowerLevels.CREATE_LEVEL and the
     * person at 0 while the server writes the first state and the invite; then the DM power levels of
     * registries/dm-power-levels.json put both at 0. A failed second write leaves a usable DM.
     */
    suspend fun createDm(userId: String): String {
        val me = session.userId
        val powerLevels = PowerLevels(
            usersDefault = null,
            eventsDefault = null,
            stateDefault = null,
            ban = null,
            kick = null,
            redact = null,
            invite = null,
            notifications = null,
            users = mapOf(me to BipeDmPowerLevels.CREATE_LEVEL.toInt(), userId to BipeDmPowerLevels.USERS_DEFAULT.toInt()),
            events = emptyMap(),
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
        val roomId = try {
            session.client.createRoom(parameters)
        } catch (error: ClientException.MatrixApi) {
            if (error.kind is ErrorKind.Forbidden) throw PeopleException(PeopleException.Kind.INVITE_REFUSED)
            throw error
        }
        lockDm(roomId, listOf(me, userId))
        return roomId
    }

    /** Room.sendStateEventRaw with the m.room.power_levels content of a new DM (ADR 0141, bipe-chat/bipe#532). */
    private suspend fun lockDm(roomId: String, userIds: List<String>) {
        repeat(5) { attempt ->
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull()
            if (room != null) {
                runCatching { room.sendStateEventRaw("m.room.power_levels", "", BipeDmPowerLevels.content(userIds)) }
                return
            }
            // The created room reaches the local store with the next sync.
            delay(300L * (attempt + 1))
        }
    }

    /**
     * The dm-delete write for report-chat's "delete the chat too": lane G's DmDeleteModel (spec dm-delete). The room is not
     * left, so the other person's next message brings it back.
     */
    fun clearDm(roomId: String) {
        DmDeleteModel(session, roomId, scope).delete(onDeleted = {})
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
