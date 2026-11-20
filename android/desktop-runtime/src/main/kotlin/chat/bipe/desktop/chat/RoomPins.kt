// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.NativeResource
import chat.bipe.desktop.account.TaskResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

class RoomPins internal constructor(
    private val owner: AccountTaskGroup,
    private val labels: ChatLabels,
    private val row: (String) -> MessageRow?,
) {
    data class Pin(val eventId: String, val sender: String, val text: String)
    data class Access(val canPin: Boolean = false, val pinned: Set<String> = emptySet())
    enum class Change { IDLE, SENDING, FAILED }

    private val mutableAccess = MutableStateFlow(Access())
    val access = mutableAccess.asStateFlow()
    private val mutablePins = MutableStateFlow<List<Pin>>(emptyList())
    val pins = mutablePins.asStateFlow()
    private val mutableChange = MutableStateFlow(Change.IDLE)
    val change = mutableChange.asStateFlow()
    private lateinit var room: Room
    private lateinit var live: Timeline

    internal fun start(room: Room, timeline: Timeline, snapshot: (TimelineItem) -> TimelineSnapshot?) {
        this.room = room
        live = timeline
        owner.launch {
            try {
                val info = room.roomInfo()
                val group = try { !info.isDm && !info.isSpace && info.membership == Membership.JOINED }
                finally { info.destroy() }
                if (!group) return@launch
                val refresh = Channel<Unit>(Channel.CONFLATED)
                owner.own(NativeResource { refresh.cancel() })
                owner.own(TaskResource(room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
                    override fun call(roomInfo: RoomInfo) {
                        roomInfo.destroy()
                        refresh.trySend(Unit)
                    }
                })))
                refresh.trySend(Unit)
                owner.launch {
                    for (signal in refresh) {
                        val access = try { readAccess() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { Access() }
                        owner.publish { mutableAccess.value = access }
                    }
                }
                val pinned = room.timelineWithConfiguration(TimelineConfiguration(
                    focus = TimelineFocus.PinnedEvents, filter = TimelineFilter.All, internalIdPrefix = "pinned",
                    dateDividerMode = DateDividerMode.DAILY, trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
                    reportUtds = false,
                ))
                owner.own(NativeResource { pinned.destroy() })
                owner.observeTimeline(pinned, snapshot, { items ->
                    val visible = items.messageRows().asReversed().mapNotNull { message ->
                        val id = message.eventId ?: return@mapNotNull null
                        if (message.kind != MessageRow.Kind.TEXT && message.kind != MessageRow.Kind.MEDIA) return@mapNotNull null
                        Pin(id, if (message.own) labels.you else message.sender, message.body.lineSequence().joinToString(" "))
                    }
                    owner.publish { mutablePins.value = visible }
                }, { mutablePins.value = emptyList() })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { owner.publish { mutableChange.value = Change.FAILED } }
        }
    }

    private suspend fun readAccess(): Access {
        val info = room.roomInfo()
        return try {
            if (info.isDm || info.isSpace || info.membership != Membership.JOINED) return Access()
            val powers = room.getPowerLevels()
            try { Access(powers.canOwnUserPinUnpin(), info.pinnedEventIds.toSet()) } finally { powers.destroy() }
        } finally { info.destroy() }
    }

    fun change(eventId: String, pin: Boolean, rowId: String? = null) {
        var accepted = false
        owner.publish {
            val access = mutableAccess.value
            if (mutableChange.value == Change.SENDING || !access.canPin || (eventId in access.pinned) == pin) return@publish
            if (pin && (rowId == null || row(rowId)?.eventId != eventId)) return@publish
            mutableChange.value = Change.SENDING
            accepted = true
        }
        if (!accepted) return
        owner.launch {
            try {
                val access = readAccess()
                check(access.canPin)
                if (pin && (eventId !in access.pinned)) {
                    check(rowId != null && row(rowId)?.eventId == eventId)
                    owner.requireCurrent()
                    live.pinEvent(eventId)
                } else if (!pin && eventId in access.pinned) {
                    owner.requireCurrent()
                    live.unpinEvent(eventId)
                }
                owner.publish { mutableChange.value = Change.IDLE }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { owner.publish { mutableChange.value = Change.FAILED } }
        }
    }

    fun dismissFailure() = owner.publish {
        if (mutableChange.value == Change.FAILED) mutableChange.value = Change.IDLE
    }
}
