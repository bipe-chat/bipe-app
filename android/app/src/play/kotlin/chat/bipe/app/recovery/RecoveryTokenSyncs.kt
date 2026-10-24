// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import chat.bipe.shared.account.BipeRecoveryTokenSync
import chat.bipe.shared.account.BipeSyncedToken
import chat.bipe.shared.account.BipeSyncedTokenCodec
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The synced store of this build: Google Block Store (Play flavour, ADR 0150). */
object RecoveryTokenSyncs {
    fun create(context: Context): BipeRecoveryTokenSync = BlockStoreRecoveryTokenSync(context.applicationContext)
}

/**
 * Recovery tokens in Google Block Store, one entry per account, in the fork's format ([BipeSyncedTokenCodec]). Cloud
 * backup is on, so a new phone signed in to the same Google account finds the token (ADR 0059); Google encrypts the
 * backup end to end only when the phone has a screen lock, else Block Store keeps it on the device. The token alone
 * opens nothing: recovery-pin also needs the PIN.
 */
private class BlockStoreRecoveryTokenSync(context: Context) : BipeRecoveryTokenSync {
    private val client = Blockstore.getClient(context)

    override val isAvailable = true

    override suspend fun store(userId: String, token: String) {
        val data = StoreBytesData.Builder()
            .setKey(BipeSyncedTokenCodec.key(userId))
            .setBytes(BipeSyncedTokenCodec.encode(userId, token))
            .setShouldBackupToCloud(true)
            .build()
        client.storeBytes(data).await()
    }

    override suspend fun all(): List<BipeSyncedToken> {
        val request = RetrieveBytesRequest.Builder().setRetrieveAll(true).build()
        return client.retrieveBytes(request).await().blockstoreDataMap.mapNotNull { (key, data) ->
            BipeSyncedTokenCodec.decode(key, data.bytes)
        }
    }

    override suspend fun delete(userId: String?) {
        val request = DeleteBytesRequest.Builder().setKeys(listOf(BipeSyncedTokenCodec.key(userId))).build()
        client.deleteBytes(request).await()
    }
}

/** Waits for a Play services task. Cancelling the caller stops waiting; the task itself cannot be stopped. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
