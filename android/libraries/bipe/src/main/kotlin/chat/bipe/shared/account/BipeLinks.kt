// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.net.Uri
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeRecoveryStarted
import chat.bipe.shared.api.InstantSerializer
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A linked Apple or Google account as a recovery factor (ADR 0096; specs linked-accounts and recovery-linked).
// Android runs the web flow for both providers in both flavours: Credential Manager needs the Google ID library,
// which ADR 0150 does not list. Nothing here logs or prints an ID token, a nonce, a flow ID or a handoff.

@Serializable
enum class BipeLinkProvider(val id: String) {
    @SerialName("apple") APPLE("apple"),
    @SerialName("google") GOOGLE("google"),
}

/** `LinkProvider`: the paths the server offers for one provider. */
@Serializable
data class BipeLinkProviderPaths(
    @SerialName("provider") val provider: BipeLinkProvider,
    @SerialName("native") val native: Boolean = false,
    @SerialName("web") val web: Boolean = false,
)

/** `LinkProviders`. */
@Serializable
data class BipeLinkProviders(@SerialName("providers") val providers: List<BipeLinkProviderPaths> = emptyList()) {
    /** Android has no native path: a provider is usable when the server runs its web flow. */
    val usableOnAndroid: List<BipeLinkProvider>
        get() = providers.filter { it.web }.map { it.provider }
}

/** `LinkedAccount`. */
@Serializable
data class BipeLinkedAccount(
    @SerialName("provider") val provider: BipeLinkProvider,
    @Serializable(with = InstantSerializer::class) @SerialName("linked_at") val linkedAt: Instant,
)

/** `LinkEvent`. The other devices show each one once (recovery-alert). */
@Serializable
data class BipeLinkEvent(
    @SerialName("id") val id: String,
    @SerialName("provider") val provider: BipeLinkProvider,
    @SerialName("change") val change: Change,
    @Serializable(with = InstantSerializer::class) @SerialName("at") val at: Instant,
) {
    @Serializable
    enum class Change {
        @SerialName("linked") LINKED,
        @SerialName("unlinked") UNLINKED,
    }
}

/** `LinkWebStart`. */
@Serializable
data class BipeLinkWebStart(
    @SerialName("flow_id") val flowId: String,
    @SerialName("url") val url: String,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
) {
    override fun toString(): String = "BipeLinkWebStart(redacted)"
}

/** `LinkProof`: the flow ID with the handoff of the link-done redirect. Single use. */
@Serializable
data class BipeLinkProof(
    @SerialName("flow_id") val flowId: String,
    @SerialName("handoff") val handoff: String,
) {
    override fun toString(): String = "BipeLinkProof(redacted)"
}

@Serializable
private data class LinkWebRequest(@SerialName("app_id") val appId: String)

@Serializable
private data class RecoveryLinkRequest(
    @SerialName("provider") val provider: BipeLinkProvider,
    @SerialName("flow_id") val flowId: String,
    @SerialName("handoff") val handoff: String,
    @SerialName("pin") val pin: String,
) {
    override fun toString(): String = "RecoveryLinkRequest"
}

/** `GET /v1/links/providers`. No auth. */
suspend fun BipeApiClient.linkProviders(): BipeLinkProviders =
    send("GET", listOf("v1", "links", "providers"), response = BipeLinkProviders.serializer())

/** `POST /v1/me/links/{provider}/web` (signed in) or `POST /v1/recovery/link/{provider}/web` (no auth). */
suspend fun BipeApiClient.startLinkWebFlow(provider: BipeLinkProvider, appId: String, accessToken: String?): BipeLinkWebStart = send(
    "POST",
    if (accessToken != null) listOf("v1", "me", "links", provider.id, "web") else listOf("v1", "recovery", "link", provider.id, "web"),
    body = encode(LinkWebRequest.serializer(), LinkWebRequest(appId)),
    accessToken = accessToken,
    response = BipeLinkWebStart.serializer(),
)

/** `PUT /v1/me/links/{provider}`. The app checks the device owner first. */
suspend fun BipeApiClient.link(provider: BipeLinkProvider, proof: BipeLinkProof, accessToken: String) {
    send<Unit>("PUT", listOf("v1", "me", "links", provider.id), body = encode(BipeLinkProof.serializer(), proof), accessToken = accessToken, response = null)
}

/** `DELETE /v1/me/links/{provider}`. The app checks the device owner first. */
suspend fun BipeApiClient.unlink(provider: BipeLinkProvider, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "links", provider.id), accessToken = accessToken, response = null)
}

/** `POST /v1/recovery/link`. No auth. The provider proof, then the PIN; the 24-hour delay starts. */
suspend fun BipeApiClient.recoverWithLink(provider: BipeLinkProvider, proof: BipeLinkProof, pin: String): BipeRecoveryStarted = send(
    "POST",
    listOf("v1", "recovery", "link"),
    body = encode(RecoveryLinkRequest.serializer(), RecoveryLinkRequest(provider, proof.flowId, proof.handoff, pin)),
    response = BipeRecoveryStarted.serializer(),
)

/**
 * The end of a web flow: `<application id>:/link-done?flow=<id>#<handoff>`, or `?flow=<id>&error=<reason>`.
 * The redirect comes from the Bipe API to the app's own URL scheme.
 */
sealed class BipeLinkDone {
    abstract val flowId: String

    data class Done(override val flowId: String, val handoff: String) : BipeLinkDone() {
        override fun toString(): String = "BipeLinkDone.Done(redacted)"
    }

    data class Cancelled(override val flowId: String) : BipeLinkDone()

    data class Failed(override val flowId: String) : BipeLinkDone()

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** `null` when [uri] is not a link-done URL for [scheme]. */
        fun parse(uri: Uri, scheme: String): BipeLinkDone? {
            if (uri.scheme != scheme || uri.path != "/link-done") return null
            val flow = uri.getQueryParameter("flow")?.takeIf { SAFE.matches(it) } ?: return null
            uri.getQueryParameter("error")?.let { return if (it == "cancelled") Cancelled(flow) else Failed(flow) }
            val handoff = uri.fragment?.takeIf { SAFE.matches(it) } ?: return Failed(flow)
            return Done(flow, handoff)
        }
    }
}
