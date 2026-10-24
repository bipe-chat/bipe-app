// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

/** Build configuration of the tester app (ADR 0147). The user never picks a server (spec account-start). */
object AppConfig {
    /** Bipe's homeserver (bipe/docs/server-migration.md). */
    const val HOMESERVER = "https://matrix.bipe.net.br"

    /** The Bipe API (bipe-api, bipe/docs/server-migration.md). Contract: bipe-shared/api/openapi.yaml. */
    const val API_BASE_URL = "https://api.bipe.net.br"

    /** The public site with the legal pages (PUBLIC_SITE_URL, registries/legal.json). */
    const val PUBLIC_SITE = "https://bipe.chat"

    /** The device name the homeserver shows in the session list. */
    const val DEVICE_NAME = "Bipe"

    /** Bipe's push gateway (Sygnal, bipe/docs/server-migration.md). Spec push-registration notes. */
    const val PUSH_GATEWAY_URL = "https://push.bipe.net.br/_matrix/push/v1/notify"

    /**
     * The Sygnal app ID of this build: the application ID without the debug suffix plus `.android.dev` (debug) or
     * `.android.prod` (tester APK), as bipe/docs/platform-parity.md. The ID set (bipe-chat/bipe#602) picks the
     * application ID: chat.bipe.app.next for tester builds.
     */
    fun pusherAppId(packageName: String, isDebug: Boolean): String =
        packageName.removeSuffix(".debug") + if (isDebug) ".android.dev" else ".android.prod"
}
