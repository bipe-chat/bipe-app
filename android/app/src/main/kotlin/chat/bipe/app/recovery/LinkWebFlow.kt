// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabsIntent
import chat.bipe.app.BuildConfig
import chat.bipe.shared.account.BipeLinkDone
import chat.bipe.shared.account.BipeLinkProof
import chat.bipe.shared.account.BipeLinkWebStart
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The web flow of a linked Apple or Google account (ADR 0096; specs linked-accounts and recovery-linked) in a Custom
 * Tab. The Bipe API sends the tab back to `<application id>:/link-done`; MainActivity hands that URL to [offer].
 * One flow at a time. The flow ID stays in memory only, so a handoff without it is worth nothing.
 */
object LinkWebFlow {
    /** The result of [run]. */
    sealed interface Result {
        data class Proof(val proof: BipeLinkProof) : Result
        data object Cancelled : Result
        data object Failed : Result
    }

    private class Pending(val flowId: String, val result: CompletableDeferred<Result>)

    @Volatile private var pending: Pending? = null

    /** The app's own URL scheme: its application ID (the API knows the three Bipe IDs). */
    val appId: String
        get() = BuildConfig.APPLICATION_ID

    /** Opens the provider page and waits for the redirect, until the flow expires. */
    suspend fun run(context: Context, start: BipeLinkWebStart): Result {
        val flow = Pending(start.flowId, CompletableDeferred())
        pending?.result?.complete(Result.Cancelled)
        pending = flow
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(start.url))
        val wait = (start.expiresAt.toEpochMilli() - Instant.now().toEpochMilli()).coerceAtLeast(0)
        val result = withTimeoutOrNull(wait) { flow.result.await() } ?: Result.Cancelled
        if (pending === flow) pending = null
        return result
    }

    /** MainActivity: a link-done URL. True when it was one (it is never a link for another screen). */
    fun offer(uri: Uri): Boolean {
        val done = BipeLinkDone.parse(uri, appId) ?: return false
        val flow = pending ?: return true
        if (done.flowId != flow.flowId) return true
        flow.result.complete(
            when (done) {
                is BipeLinkDone.Done -> Result.Proof(BipeLinkProof(done.flowId, done.handoff))
                is BipeLinkDone.Cancelled -> Result.Cancelled
                is BipeLinkDone.Failed -> Result.Failed
            },
        )
        return true
    }

    /**
     * MainActivity resumed: the person closed the tab without the redirect. The redirect arrives through onNewIntent
     * before onResume, so a flow that still waits a moment later was closed.
     */
    fun appResumed() {
        val flow = pending ?: return
        Handler(Looper.getMainLooper()).postDelayed({ if (pending === flow) flow.result.complete(Result.Cancelled) }, 1_500)
    }
}
