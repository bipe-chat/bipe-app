// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.pix

import chat.bipe.core.PixMark
import chat.bipe.core.PixMarkCard
import chat.bipe.core.PixMarkEvent
import chat.bipe.core.PixMarkState
import chat.bipe.core.pixE2eIdValid
import chat.bipe.core.pixMarkContent
import chat.bipe.core.pixMarkLines
import chat.bipe.core.pixTxid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Pix request marks (ADR 0046, bipe-chat/bipe#130; registries/pix-marks.json): the payer marks "enviei", the requester
 * marks "recebi" or "não recebi". Bipe moves no money (ADR 0012); a line is paid only after "recebi". Matches
 * pixMarkLines and pixTxid in bipe-shared tools/lib/pix.mjs and test-vectors/pix-marks.json. The rules run in
 * bipe-core (`pixMarkLines`, ADR 0267).
 */
object PixMarks {
    enum class State(val raw: String) { ISSUED("issued"), SENT("sent"), RECEIVED("received"), NOT_RECEIVED("not_received") }

    /** The marks a person can send: every state but issued. */
    enum class Mark(val raw: String, val state: State) {
        SENT("sent", State.SENT),
        RECEIVED("received", State.RECEIVED),
        NOT_RECEIVED("not_received", State.NOT_RECEIVED),
        ;

        companion object {
            fun of(raw: String?): Mark? = entries.firstOrNull { it.raw == raw }
        }
    }

    data class Card(val eventId: String, val requester: String, val payers: List<String>)

    /** A mark event: its ID, sender, origin_server_ts and content. */
    data class Event(val eventId: String, val sender: String, val ts: Long, val content: JsonElement?)

    data class HistoryEntry(val eventId: String, val mark: Mark)

    /** One payer's line of the card. [e2eId] is the payer's claim from the bank receipt; Bipe cannot check it. */
    data class Line(
        val payer: String,
        val state: State = State.ISSUED,
        val e2eId: String? = null,
        val history: List<HistoryEntry> = emptyList(),
    ) {
        val isPaid: Boolean get() = state == State.RECEIVED
    }

    data class Result(val lines: List<Line>, val rejected: List<String>)

    /** The lines of a request card from its mark events. Marks that break a rule are rejected and change nothing. */
    fun lines(card: Card, events: List<Event>): Result {
        val result = pixMarkLines(
            PixMarkCard(card.eventId, card.requester, card.payers),
            events.map { PixMarkEvent(it.eventId, it.sender, it.ts, it.content?.toString() ?: "null") },
        )
        return Result(
            lines = result.lines.map { line ->
                Line(line.payer, state(line.state), line.e2eId, line.history.map { HistoryEntry(it.eventId, mark(it.mark)) })
            },
            rejected = result.rejected,
        )
    }

    /**
     * The content of a mark event, for Room.sendRaw with eventType BipeRegistries.PIX_MARK_EVENT_TYPE. Null when the
     * E2E ID is given and malformed, or given on another mark than sent.
     */
    fun content(cardEventId: String, mark: Mark, payer: String?, e2eId: String?): JsonObject? =
        pixMarkContent(cardEventId, core(mark), payer, e2eId)?.let { Json.parseToJsonElement(it).jsonObject }

    /** The format of a Pix end-to-end ID: E, the 8-digit ISPB, 23 letters or digits (BipeRegistries.PIX_E2E_ID_PATTERN). */
    fun isValidE2eId(id: String): Boolean = pixE2eIdValid(id)

    /** The BR Code txid of a card: the request ID with ASCII letters and digits only, at most PIX_TXID_MAX_LENGTH. */
    fun txid(requestId: String): String = pixTxid(requestId)

    private fun state(state: PixMarkState): State = when (state) {
        PixMarkState.ISSUED -> State.ISSUED
        PixMarkState.SENT -> State.SENT
        PixMarkState.RECEIVED -> State.RECEIVED
        PixMarkState.NOT_RECEIVED -> State.NOT_RECEIVED
    }

    private fun mark(mark: PixMark): Mark = when (mark) {
        PixMark.SENT -> Mark.SENT
        PixMark.RECEIVED -> Mark.RECEIVED
        PixMark.NOT_RECEIVED -> Mark.NOT_RECEIVED
    }

    private fun core(mark: Mark): PixMark = when (mark) {
        Mark.SENT -> PixMark.SENT
        Mark.RECEIVED -> PixMark.RECEIVED
        Mark.NOT_RECEIVED -> PixMark.NOT_RECEIVED
    }
}
