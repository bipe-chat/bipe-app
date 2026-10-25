// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.signup

import android.content.Context
import chat.bipe.shared.BipeInstallReferrer
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The install referrer of this build: Google's Install Referrer library (Play flavour, ADR 0154). */
object InstallReferrers {
    fun create(context: Context): BipeInstallReferrer = PlayInstallReferrer(context.applicationContext)
}

/** Reads the referrer from the Play Store app once per call, and closes the connection. */
private class PlayInstallReferrer(private val context: Context) : BipeInstallReferrer {
    override suspend fun read(): BipeInstallReferrer.Result = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()
        fun finish(result: BipeInstallReferrer.Result) {
            runCatching { client.endConnection() }
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation { runCatching { client.endConnection() } }
        val started = runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    finish(
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK ->
                                runCatching { BipeInstallReferrer.Result.Read(client.installReferrer.installReferrer) }
                                    .getOrDefault(BipeInstallReferrer.Result.TryLater)
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR ->
                                BipeInstallReferrer.Result.Unsupported
                            else -> BipeInstallReferrer.Result.TryLater
                        },
                    )
                }

                override fun onInstallReferrerServiceDisconnected() = finish(BipeInstallReferrer.Result.TryLater)
            })
        }
        if (started.isFailure) finish(BipeInstallReferrer.Result.TryLater)
    }
}
