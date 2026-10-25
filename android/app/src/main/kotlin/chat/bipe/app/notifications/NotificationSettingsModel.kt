// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import chat.bipe.app.account.AccountSession
import chat.bipe.shared.account.BipeWorkHours
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.isCompany
import chat.bipe.shared.settings.newsState
import chat.bipe.shared.settings.setNews
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.NotificationSettingsDelegate
import org.matrix.rustcomponents.sdk.RoomNotificationMode

/** Type NotificationMode of spec notification-settings. */
enum class NotificationMode(val label: Int) {
    ALL(R.string.bipe_app_notification_mode_all),
    MENTIONS(R.string.bipe_app_notification_mode_mentions),
    OFF(R.string.bipe_app_notification_mode_off),
    ;

    val sdkMode: RoomNotificationMode
        get() = when (this) {
            ALL -> RoomNotificationMode.ALL_MESSAGES
            MENTIONS -> RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
            OFF -> RoomNotificationMode.MUTE
        }

    companion object {
        fun of(mode: RoomNotificationMode): NotificationMode = when (mode) {
            RoomNotificationMode.ALL_MESSAGES -> ALL
            RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> MENTIONS
            RoomNotificationMode.MUTE -> OFF
        }
    }
}

/** Spec notification-settings (bipe-shared product/screens/notification-settings.json). */
class NotificationSettingsModel(
    private val session: AccountSession,
    private val api: BipeApiClient,
    private val push: PushRegistration,
    private val notifications: PhoneNotifications,
    private val scope: CoroutineScope,
) {
    data class State(
        val chatsMode: NotificationMode = NotificationMode.ALL,
        val groupsMode: NotificationMode = NotificationMode.MENTIONS,
        val wholeGroupMentions: Boolean = true,
        val invites: Boolean = true,
        val sound: NotificationSound = NotificationSound.PHONE,
        val mentionsMayMiss: Boolean = false,
        val hasOlderChoices: Boolean = false,
        val isLoaded: Boolean = false,
        /** The older-choices dialog, with the change that waits for it. */
        val pendingGroupsMode: NotificationMode? = null,
        val showsError: Boolean = false,
        val showsToneError: Boolean = false,
        /** Spec news: the switch for Novidades do Bipe (ADR 0117). UNKNOWN hides it. */
        val news: News = News.UNKNOWN,
        val newsRoomId: String? = null,
        val newsBusy: Boolean = false,
        val showsNewsError: Boolean = false,
        /** Spec notification-settings, state preview (per account on this phone). */
        val preview: Boolean = true,
        /** Spec notification-settings, state isCompany: the opening-hours row shows. */
        val isCompany: Boolean = false,
        /** The opening hours, for the row's value. Null until read or for a personal account. */
        val workHours: BipeWorkHours? = null,
    )

    enum class News { UNKNOWN, ON, OFF }

    private val _state = MutableStateFlow(State(sound = notifications.sound(session.userId), preview = notifications.preview(session.userId)))
    val state: StateFlow<State> = _state.asStateFlow()

    /** The system permission is allowed and push-registration has a pusher. */
    val phoneEnabled = combine(push.permission, push.registration) { permission, registration ->
        permission == PushRegistration.Permission.ALLOWED && registration != null
    }

    private var settings: NotificationSettings? = null

    /** Action appear: reads the permission and the push rules, and follows changes from other devices. */
    fun appear() {
        push.appBecameActive()
        scope.launch {
            val settings = settings ?: session.client.getNotificationSettings().also { settings ->
                settings.setDelegate(object : NotificationSettingsDelegate {
                    override fun settingsDidChange() {
                        scope.launch { load() }
                    }
                })
                this@NotificationSettingsModel.settings = settings
            }
            load(settings)
        }
        scope.launch { loadNews() }
        scope.launch { loadWorkSide() }
    }

    /** Reads whether the account is a company and, if so, its opening hours (spec work-hours). */
    private suspend fun loadWorkSide() {
        val isCompany = api.isCompany(session.userId)
        _state.update { it.copy(isCompany = isCompany) }
        if (isCompany) {
            val hours = WorkHoursSync.refresh(session, api, notifications)
            _state.update { it.copy(workHours = hours) }
        }
    }

    /** After a save on work-hours. */
    fun workHoursSaved(hours: BipeWorkHours) {
        _state.update { it.copy(workHours = hours) }
    }

    /** Action setPreview. */
    fun setPreview(isOn: Boolean) {
        notifications.setPreview(session.userId, isOn)
        _state.update { it.copy(preview = notifications.preview(session.userId)) }
    }

    /** news: on only when the API has the user subscribed and the user is joined to or invited in the room. */
    private suspend fun loadNews() {
        val state = try {
            BipeApiClient.withTokenRetry({ session.accessToken() }) { api.newsState(it) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(news = News.UNKNOWN) }
            return
        }
        val roomId = state.roomId
        val membership = if (state.subscribed && roomId != null) runCatching { session.client.getRoom(roomId)?.membership() }.getOrNull() else null
        val isIn = membership == Membership.JOINED || membership == Membership.INVITED
        _state.update { it.copy(news = if (isIn) News.ON else News.OFF, newsRoomId = roomId.takeIf { isIn }) }
    }

    /** Action setNews. on: PUT, then join the room. off: leave the room, then DELETE. A failure puts the switch back. */
    fun setNews(isOn: Boolean) {
        val old = _state.value
        if (old.newsBusy || old.news == News.UNKNOWN) return
        _state.update { it.copy(newsBusy = true, news = if (isOn) News.ON else News.OFF) }
        scope.launch {
            try {
                if (isOn) {
                    val state = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.setNews(true, it) }
                    val roomId = checkNotNull(state.roomId)
                    if (session.client.getRoom(roomId)?.membership() != Membership.JOINED) session.client.joinRoomById(roomId)
                    _state.update { it.copy(news = News.ON, newsRoomId = roomId, newsBusy = false) }
                } else {
                    old.newsRoomId?.let { session.client.getRoom(it)?.leave() }
                    BipeApiClient.withTokenRetry({ session.accessToken() }) { api.setNews(false, it) }
                    _state.update { it.copy(news = News.OFF, newsRoomId = null, newsBusy = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(news = old.news, newsRoomId = old.newsRoomId, newsBusy = false, showsNewsError = true) }
            }
        }
    }

    fun close() {
        settings?.setDelegate(null)
    }

    /** Action setChatsMode: all or off, for one-to-one rooms, encrypted and not. */
    fun setChatsMode(mode: NotificationMode) {
        val old = _state.value.chatsMode
        _state.update { it.copy(chatsMode = mode) }
        write(restore = { it.copy(chatsMode = old) }) { setDefault(it, isOneToOne = true, mode) }
    }

    /** Action setGroupsMode: with older choices on the account, the dialog comes first. */
    fun setGroupsMode(mode: NotificationMode) {
        if (_state.value.hasOlderChoices) {
            _state.update { it.copy(pendingGroupsMode = mode) }
        } else {
            applyGroupsMode(mode)
        }
    }

    fun confirmGroupsMode() {
        val mode = _state.value.pendingGroupsMode ?: return
        _state.update { it.copy(pendingGroupsMode = null, hasOlderChoices = false) }
        applyGroupsMode(mode)
    }

    fun cancelGroupsMode() {
        _state.update { it.copy(pendingGroupsMode = null) }
    }

    fun setWholeGroupMentions(isOn: Boolean) {
        val old = _state.value.wholeGroupMentions
        _state.update { it.copy(wholeGroupMentions = isOn) }
        write(restore = { it.copy(wholeGroupMentions = old) }) { it.setRoomMentionEnabled(isOn) }
    }

    fun setInvites(isOn: Boolean) {
        val old = _state.value.invites
        _state.update { it.copy(invites = isOn) }
        write(restore = { it.copy(invites = old) }) { it.setInviteForMeEnabled(isOn) }
    }

    /** Action setSound: moves the messages channel to that sound. A failure shows bipe_notifications_tone_error. */
    fun setSound(sound: NotificationSound) {
        val saved = notifications.setSound(session.userId, sound)
        _state.update { it.copy(sound = notifications.sound(session.userId), showsToneError = !saved) }
    }

    fun dismissErrors() {
        _state.update { it.copy(showsError = false, showsToneError = false, showsNewsError = false) }
    }

    private fun applyGroupsMode(mode: NotificationMode) {
        val old = _state.value.groupsMode
        _state.update { it.copy(groupsMode = mode) }
        write(restore = { it.copy(groupsMode = old) }) { setDefault(it, isOneToOne = false, mode) }
    }

    private suspend fun load(settings: NotificationSettings? = this.settings) {
        settings ?: return
        // The screen reads the encrypted value and writes both (spec notes).
        val chats = settings.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = true)
        val groups = settings.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = false)
        val chatsPlain = settings.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = true)
        val groupsPlain = settings.getDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = false)
        val current = _state.value
        val wholeGroup = runCatching { settings.isRoomMentionEnabled() }.getOrDefault(current.wholeGroupMentions)
        val invites = runCatching { settings.isInviteForMeEnabled() }.getOrDefault(current.invites)
        val mentionsMayMiss = !settings.canPushEncryptedEventToDevice()
        val hasKeywords = settings.containsKeywordsRules()
        _state.update {
            it.copy(
                chatsMode = NotificationMode.of(chats),
                groupsMode = NotificationMode.of(groups),
                wholeGroupMentions = wholeGroup,
                invites = invites,
                mentionsMayMiss = mentionsMayMiss,
                hasOlderChoices = chats != chatsPlain || groups != groupsPlain || hasKeywords,
                sound = notifications.sound(session.userId),
                preview = notifications.preview(session.userId),
                isLoaded = true,
            )
        }
    }

    /** A failed rule change puts the row back and shows bipe_error_generic. */
    private fun write(restore: (State) -> State, change: suspend (NotificationSettings) -> Unit) {
        val settings = settings ?: return
        scope.launch {
            runCatching { change(settings) }.onFailure {
                _state.update { restore(it).copy(showsError = true) }
            }
        }
    }

    private suspend fun setDefault(settings: NotificationSettings, isOneToOne: Boolean, mode: NotificationMode) {
        settings.setDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = isOneToOne, mode = mode.sdkMode)
        settings.setDefaultRoomNotificationMode(isEncrypted = false, isOneToOne = isOneToOne, mode = mode.sdkMode)
    }
}
