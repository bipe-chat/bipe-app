// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.pix

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pix request marks (ADR 0046, bipe-chat/bipe#130; registries/pix-marks.json): the payer marks "enviei", the requester
 * marks "recebi" or "não recebi". Bipe moves no money (ADR 0012); a line is paid only after "recebi". Matches
 * pixMarkLines and pixTxid in bipe-shared tools/lib/pix.mjs and test-vectors/pix-marks.json.
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

    private val allowedFrom = mapOf(
        Mark.SENT to setOf(State.ISSUED, State.SENT, State.NOT_RECEIVED),
        Mark.RECEIVED to setOf(State.ISSUED, State.SENT, State.NOT_RECEIVED),
        Mark.NOT_RECEIVED to setOf(State.SENT, State.RECEIVED),
    )

    /** The lines of a request card from its mark events. Marks that break a rule are rejected and change nothing. */
    fun lines(card: Card, events: List<Event>): Result {
        val lines = card.payers.map { Line(it) }.toMutableList()
        val rejected = mutableListOf<String>()
        for (event in events.sortedWith(compareBy<Event> { it.ts }.thenBy { it.eventId })) {
            val hit = accepted(card, event, lines)
            if (hit == null) {
                rejected += event.eventId
                continue
            }
            val (index, mark, e2eId) = hit
            val line = lines[index]
            lines[index] = line.copy(
                state = mark.state,
                e2eId = if (mark == Mark.SENT) e2eId else line.e2eId,
                history = line.history + HistoryEntry(event.eventId, mark),
            )
        }
        return Result(lines, rejected)
    }

    /**
     * The content of a mark event, for Room.sendRaw with eventType BipeRegistries.PIX_MARK_EVENT_TYPE. Null when the
     * E2E ID is given and malformed, or given on another mark than sent.
     */
    fun content(cardEventId: String, mark: Mark, payer: String?, e2eId: String?): JsonObject? {
        if (e2eId != null && (mark != Mark.SENT || !isValidE2eId(e2eId))) return null
        return buildJsonObject {
            putJsonObject("m.relates_to") {
                put("rel_type", BipeRegistries.PIX_MARK_REL_TYPE)
                put("event_id", cardEventId)
            }
            put("mark", mark.raw)
            if (payer != null) put("payer", payer)
            if (e2eId != null) put("e2e_id", e2eId)
        }
    }

    /** The format of a Pix end-to-end ID: E, the 8-digit ISPB, 23 letters or digits (BipeRegistries.PIX_E2E_ID_PATTERN). */
    fun isValidE2eId(id: String): Boolean =
        id.length == 32 && id[0] == 'E' && id.substring(1, 9).all { it in '0'..'9' } && id.substring(9).all(::isAsciiAlphanumeric)

    /** The BR Code txid of a card: the request ID with ASCII letters and digits only, at most PIX_TXID_MAX_LENGTH. */
    fun txid(requestId: String): String = requestId.filter(::isAsciiAlphanumeric).take(BipeRegistries.PIX_TXID_MAX_LENGTH)

    private fun isAsciiAlphanumeric(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun accepted(card: Card, event: Event, lines: List<Line>): Triple<Int, Mark, String?>? {
        val content = event.content as? JsonObject ?: return null
        val relation = content["m.relates_to"] as? JsonObject ?: return null
        if (relation["rel_type"].stringOrNull() != BipeRegistries.PIX_MARK_REL_TYPE) return null
        if (relation["event_id"].stringOrNull() != card.eventId) return null
        val mark = Mark.of(content["mark"].stringOrNull()) ?: return null
        val namedPayer = if ("payer" in content) content["payer"].stringOrNull() ?: return null else null
        var e2eId: String? = null
        if ("e2e_id" in content) {
            val id = content["e2e_id"].stringOrNull()
            if (mark != Mark.SENT || id == null || !isValidE2eId(id)) return null
            e2eId = id
        }
        val payer = if (mark == Mark.SENT) {
            if (namedPayer != null && namedPayer != event.sender) return null
            event.sender
        } else {
            if (event.sender != card.requester) return null
            namedPayer ?: lines.singleOrNull()?.payer
        }
        val index = lines.indexOfFirst { it.payer == payer }
        if (index < 0 || lines[index].state !in allowedFrom.getValue(mark)) return null
        return Triple(index, mark, e2eId)
    }
}
