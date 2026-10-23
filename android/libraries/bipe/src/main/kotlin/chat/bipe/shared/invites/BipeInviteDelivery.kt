// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.invites

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Spec invite-note: a cancelled sender must retain a claimed first message for the next check. */
suspend fun deliverInviteNote(
    claim: suspend () -> String?,
    restore: suspend (String) -> Unit,
    send: suspend (String) -> Unit,
): Boolean {
    // A disk write may complete just as its caller is cancelled. Always receive the claim result.
    val note = withContext(NonCancellable) { claim() } ?: return false
    try {
        currentCoroutineContext().ensureActive()
        send(note)
    } catch (error: Exception) {
        // Returning to cancellable IO here would immediately throw again and lose the note.
        withContext(NonCancellable) { restore(note) }
        throw error
    }
    return true
}
