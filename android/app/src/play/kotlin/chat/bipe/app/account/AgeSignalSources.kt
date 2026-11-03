// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import chat.bipe.shared.account.BipeAgeSignal
import chat.bipe.shared.account.BipeAgeSignalSource
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The age source of this build: Google Play Age Signals (Play flavour, ADR 0229). */
object AgeSignalSources {
    fun create(context: Context): BipeAgeSignalSource = PlayAgeSignalSource(context.applicationContext)
}

/** Reads the range Google Play has for this user. No range, or any error, gives [BipeAgeSignal.NONE]. */
private class PlayAgeSignalSource(context: Context) : BipeAgeSignalSource {
    private val manager = AgeSignalsManagerFactory.create(context)

    override suspend fun read(): BipeAgeSignal {
        val result = manager.checkAgeSignals(AgeSignalsRequest.builder().build()).awaitOrNull() ?: return BipeAgeSignal.NONE
        val lower = result.ageLower()
        val upper = result.ageUpper()
        if (lower == null && upper == null) return BipeAgeSignal.NONE
        return BipeAgeSignal("play", lower, upper, result.ageRangeSource()?.toString())
    }
}

/** Waits for a Play task; a failure or a cancel gives `null`. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        continuation.resume(if (task.isSuccessful) task.result else null)
    }
}
