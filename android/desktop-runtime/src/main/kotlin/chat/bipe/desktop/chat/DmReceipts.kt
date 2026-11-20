// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.matrix.rustcomponents.sdk.ReceiptType

data class DmReceipt(val tick: Tick, val showsLabel: Boolean, val privateTick: Tick = tick) {
    enum class Tick { SENDING, SENT, DELIVERED, READ, FAILED }
}
internal data class DmReceiptPolicy(val ownId: String, val isDm: Boolean, val showsRead: Boolean, val acked: Set<String> = emptySet())

/** Validate once with the server; a stale SDK cache must not undo a more private server choice. */
internal class ReceiptPreference(private val fresh: suspend () -> String?, private val cached: suspend () -> String?) {
    private sealed interface Phase {
        data object Unconfirmed : Phase
        data class Validated(val observed: String?, val showsRead: Boolean) : Phase
    }
    private var phase: Phase = Phase.Unconfirmed
    suspend fun refresh(): Boolean {
        val current = phase
        val cache = cached()
        if (current is Phase.Validated && cache == current.observed) return current.showsRead
        val shows = if (current == Phase.Unconfirmed || cache == null) {
            val server = permits(fresh())
            server && (cache == null || permits(cache)) // Off wins while first-sync and server evidence differ.
        } else permits(cache)
        phase = Phase.Validated(cache, shows)
        return shows
    }
    private fun permits(raw: String?) = AccountReadActions.receipt(true, raw) == ReceiptType.READ
}

/** SDK receipts cover older events, including when the newest receipt lives on an unrendered state event. */
internal fun List<TimelineSnapshot>.dmReceipts(policy: DmReceiptPolicy): Map<String, DmReceipt> {
    if (!policy.isDm) return emptyMap()
    val readers = mutableSetOf<String>()
    var newestOwn = true
    var delivered = false
    return buildMap {
        for (item in this@dmReceipts.asReversed()) {
            val row = when (item) {
                is TimelineSnapshot.Message -> { readers.addAll(item.row.readersAtEvent); item.row }
                is TimelineSnapshot.ReadPoint -> { readers.addAll(item.readers); null }
                is TimelineSnapshot.Day -> null
            } ?: continue
            readers.remove(policy.ownId)
            if (!row.own || row.kind == MessageRow.Kind.DELETED || row.kind == MessageRow.Kind.UNDECRYPTABLE) continue
            if (row.eventId in policy.acked) delivered = true
            val tick = when (row.delivery) {
                MessageRow.Delivery.SENDING -> DmReceipt.Tick.SENDING
                MessageRow.Delivery.FAILED -> DmReceipt.Tick.FAILED
                MessageRow.Delivery.SENT -> when {
                    readers.isNotEmpty() && policy.showsRead -> DmReceipt.Tick.READ
                    delivered -> DmReceipt.Tick.DELIVERED
                    else -> DmReceipt.Tick.SENT
                }
            }
            val privateTick = if (tick != DmReceipt.Tick.READ) tick
                else if (delivered) DmReceipt.Tick.DELIVERED else DmReceipt.Tick.SENT
            put(row.id, DmReceipt(tick, newestOwn, privateTick))
            newestOwn = false
        }
    }
}
