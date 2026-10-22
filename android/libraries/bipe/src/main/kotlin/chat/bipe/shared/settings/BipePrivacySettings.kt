// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeDiscoverySettings
import chat.bipe.shared.api.BipeMessagingSetting
import chat.bipe.shared.api.BipePhoneStatus
import chat.bipe.shared.api.BipeWhoCanMessage
import chat.bipe.shared.runCatchingExceptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * The Bipe API settings of the Privacidade group (ADR 0112) for one session. The Você root and the Privacidade screen
 * read the same values, so a change on the screen shows at once in the root subtitle.
 */
class BipePrivacySettings(
    private val apiClient: BipeApiClient,
    private val accessToken: suspend () -> String?,
) {
    private val messagingFlow = MutableStateFlow<BipeWhoCanMessage?>(null)
    private val discoveryFlow = MutableStateFlow<Boolean?>(null)
    private val phoneFlow = MutableStateFlow<BipePhoneStatus?>(null)

    /** `null` until the first answer of `GET /v1/me/messaging`. */
    val messaging: StateFlow<BipeWhoCanMessage?> = messagingFlow.asStateFlow()

    suspend fun refreshMessaging(): Result<BipeWhoCanMessage> = runCatchingExceptions {
        val setting = BipeApiClient.withTokenRetry(accessToken) { apiClient.messaging(it) }.whoCanMessage
        messagingFlow.value = setting
        setting
    }.onFailure { Timber.w(it, "Bipe: messaging setting not loaded") }

    suspend fun setMessaging(value: BipeWhoCanMessage): Result<BipeWhoCanMessage> = runCatchingExceptions {
        val saved = BipeApiClient.withTokenRetry(accessToken) { apiClient.setMessaging(BipeMessagingSetting(value), it) }.whoCanMessage
        messagingFlow.value = saved
        saved
    }.onFailure { Timber.e(it, "Bipe: messaging setting not saved") }

    /** "Quem tem meu número pode me encontrar" (ADR 0063). `null` until the first answer of `GET /v1/me/discovery`. */
    val discoverableByPhone: StateFlow<Boolean?> = discoveryFlow.asStateFlow()

    /** `null` until the first answer of `GET /v1/me/phone`. */
    val phone: StateFlow<BipePhoneStatus?> = phoneFlow.asStateFlow()

    suspend fun refreshDiscovery(): Result<Unit> = runCatchingExceptions {
        discoveryFlow.value = BipeApiClient.withTokenRetry(accessToken) { apiClient.discovery(it) }.byPhone
        phoneFlow.value = BipeApiClient.withTokenRetry(accessToken) { apiClient.phone(it) }
    }.onFailure { Timber.w(it, "Bipe: discovery settings not loaded") }

    suspend fun setDiscoverableByPhone(value: Boolean): Result<Unit> = runCatchingExceptions {
        discoveryFlow.value = BipeApiClient.withTokenRetry(accessToken) { apiClient.setDiscovery(BipeDiscoverySettings(value), it) }.byPhone
    }.onFailure { Timber.e(it, "Bipe: discovery setting not saved") }
}
