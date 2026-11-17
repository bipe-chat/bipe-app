// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeleteIntent internal constructor(val rowId: String, val eventId: String, internal val owner: Any)
sealed interface DeletePermission {
    data object Loading : DeletePermission
    data object Unavailable : DeletePermission
    data class Ready(val own: Boolean) : DeletePermission
}
sealed interface DeletePhase {
    data object Idle : DeletePhase
    data class Deleting(val intent: DeleteIntent) : DeletePhase
    data class Failed(val intent: DeleteIntent) : DeletePhase
}
internal interface DeleteBackend {
    suspend fun canDeleteOwn(): Boolean
    /** Must recheck native ownership, membership and current power levels before the redaction. */
    suspend fun deleteOwn(eventId: String)
}

/** Confirmed sent-message deletion only. Unsent cancellation and group moderation remain separate slices. */
class DeleteActions internal constructor(private val group: AccountTaskGroup, private val row: (String) -> MessageRow?,
                                        private val backend: DeleteBackend) {
    private val mutablePermission = MutableStateFlow<DeletePermission>(DeletePermission.Loading)
    private val token = Any()
    val permission = mutablePermission.asStateFlow()
    private val mutablePhase = MutableStateFlow<DeletePhase>(DeletePhase.Idle)
    val phase = mutablePhase.asStateFlow()

    fun refresh() {
        var accepted = false
        group.publish {
            // Initial load is launched once by the room; later refreshes retain an explicit in-flight state.
            if (mutablePermission.value != DeletePermission.Loading) { mutablePermission.value = DeletePermission.Loading; accepted = true }
        }
        if (accepted) loadPermission()
    }
    internal fun start() { loadPermission() }
    private fun loadPermission() = group.launch {
        val result = try { DeletePermission.Ready(backend.canDeleteOwn()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DeletePermission.Unavailable }
        group.publish { mutablePermission.value = result }
    }

    /** A menu/dialog keeps this immutable identity, not a later selected row or room. */
    fun candidate(rowId: String): DeleteIntent? {
        var result: DeleteIntent? = null
        group.publish {
            if ((mutablePermission.value as? DeletePermission.Ready)?.own != true || mutablePhase.value is DeletePhase.Deleting) return@publish
            val current = row(rowId) ?: return@publish
            if (current.id != rowId || !current.own || current.delivery != MessageRow.Delivery.SENT ||
                current.kind == MessageRow.Kind.DELETED || current.kind == MessageRow.Kind.UNDECRYPTABLE) return@publish
            current.eventId?.let { result = DeleteIntent(rowId, it, token) }
        }
        return result
    }

    /** Called only by the explicit Delete confirmation. Stale identities and repeat clicks cannot dispatch. */
    fun confirm(intent: DeleteIntent) {
        var accepted = false
        group.publish {
            if (candidate(intent.rowId) == intent) { mutablePhase.value = DeletePhase.Deleting(intent); accepted = true }
        }
        if (!accepted) return
        group.launch {
            try {
                // The controller rechecks again after scheduling, before any backend I/O.
                val current = row(intent.rowId)
                check(current != null && current.eventId == intent.eventId && current.own && current.delivery == MessageRow.Delivery.SENT &&
                    current.kind != MessageRow.Kind.DELETED && current.kind != MessageRow.Kind.UNDECRYPTABLE)
                backend.deleteOwn(intent.eventId)
                group.publish { mutablePhase.value = DeletePhase.Idle }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { mutablePhase.value = DeletePhase.Failed(intent) } }
        }
    }
    fun dismissFailure() = group.publish { if (mutablePhase.value is DeletePhase.Failed) mutablePhase.value = DeletePhase.Idle }
}
