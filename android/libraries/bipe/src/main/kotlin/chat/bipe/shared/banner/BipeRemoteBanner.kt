// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec remote-banner (bipe-shared product/screens/remote-banner.json), ADR 0117. Written from the contract
// RemoteBanner in api/openapi.yaml and the reference remoteBannerState() and compareAppVersions() in
// bipe-shared tools/lib/registries.mjs; cases in test-vectors/remote-banner.json.

package chat.bipe.shared.banner

import chat.bipe.core.RemoteBanner
import chat.bipe.core.RemoteBannerState
import chat.bipe.core.compareAppVersions
import chat.bipe.core.remoteBannerState
import chat.bipe.shared.api.BipeApiClient
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Contract `RemoteBanner`. Title and text are pt-BR only and show as they are. */
@Serializable
data class BipeRemoteBanner(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("text") val text: String,
    @SerialName("link") val link: String,
    @SerialName("min_app_version") val minAppVersion: String? = null,
    @SerialName("update_links") val updateLinks: UpdateLinks? = null,
    @SerialName("expires_at") val expiresAt: String,
) {
    @Serializable
    data class UpdateLinks(@SerialName("ios") val ios: String, @SerialName("android") val android: String)

    /** What the app shows for the banner. */
    enum class State { HIDDEN, NOTICE, UPDATE }

    companion object {
        /** Reference `remoteBannerState(banner, appVersion, now)`. */
        fun state(banner: BipeRemoteBanner?, appVersion: String, now: Instant): State =
            when (remoteBannerState(banner?.let { RemoteBanner(it.expiresAt, it.minAppVersion) }, appVersion, now.toString())) {
                RemoteBannerState.HIDDEN -> State.HIDDEN
                RemoteBannerState.UPDATE -> State.UPDATE
                RemoteBannerState.NOTICE -> State.NOTICE
            }

        /** Reference `compareAppVersions(a, b)`: -1, 0 or 1. Missing parts count as 0; each part reads its leading digits. */
        fun compareVersions(a: String, b: String): Int = compareAppVersions(a, b)
    }
}

@Serializable
private data class BannerResponse(@SerialName("banner") val banner: BipeRemoteBanner? = null)

/** `GET /v1/banner`. No auth; sends nothing about the person. */
suspend fun BipeApiClient.remoteBanner(): BipeRemoteBanner? =
    send("GET", listOf("v1", "banner"), response = BannerResponse.serializer()).banner
