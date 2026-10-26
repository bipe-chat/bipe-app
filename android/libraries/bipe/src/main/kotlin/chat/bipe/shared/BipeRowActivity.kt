// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

/**
 * The date and text of a chat-list row (spec chat-list, bipe-chat/bipe#592). The SDK picks a room's latest event once,
 * when the event arrives, and skips encrypted events that this phone cannot decrypt at that moment. A message that
 * arrives before its room key is never picked, and nothing picks it after the timeline decrypts it, so the row keeps an
 * older event (the own join, for example) or has none. Each row also reads its local timeline: a newer message-like
 * event there gives the row its time and its preview. The time of an event is never encrypted. Never the current time,
 * never a guess.
 */
object BipeRowActivity {
    /** A message-like event (text, media, sticker, poll, deleted or undecryptable) or any other timeline event. */
    enum class Kind { MESSAGE, OTHER }

    /**
     * One event of the room's local timeline: its origin_server_ts in milliseconds, its chat-list preview (null when the
     * row shows no text for it) and whether this account sent it.
     */
    data class LocalEvent(val timestampMs: Long, val kind: Kind, val previewText: String? = null, val isOwn: Boolean = false)

    /** The SDK latest event of a row: its time, its preview and whether someone else sent it. */
    data class SdkLatest(val timestampMs: Long, val previewText: String?, val isIncoming: Boolean)

    /** What a row shows: its time and text, and the time of its newest incoming message (for the archive). */
    data class Row(val timestampMs: Long?, val previewText: String?, val latestIncomingMs: Long?)

    /**
     * The row of a room.
     * 1. A message-like local event newer than the SDK latest event: its time and its preview.
     * 2. Else the SDK latest event.
     * 3. Without an SDK latest event: the newest message-like local event, undecryptable ones included, else the newest
     *    local event of any kind (time only). A time known earlier in this account session is kept when it is newer.
     */
    fun resolve(sdk: SdkLatest?, localEvents: List<LocalEvent>, knownMs: Long?): Row {
        val newestMessage = localEvents.filter { it.kind == Kind.MESSAGE }.maxByOrNull { it.timestampMs }
        if (sdk != null) {
            if (newestMessage != null && newestMessage.timestampMs > sdk.timestampMs) return row(newestMessage)
            return Row(sdk.timestampMs, sdk.previewText, if (sdk.isIncoming && sdk.previewText != null) sdk.timestampMs else null)
        }
        if (newestMessage != null) {
            if (knownMs != null && knownMs > newestMessage.timestampMs) return Row(knownMs, null, null)
            return row(newestMessage)
        }
        val time = listOfNotNull(localEvents.maxOfOrNull { it.timestampMs }, knownMs).maxOrNull()
        return Row(time, null, null)
    }

    private fun row(event: LocalEvent): Row =
        Row(event.timestampMs, event.previewText, if (!event.isOwn && event.previewText != null) event.timestampMs else null)
}
