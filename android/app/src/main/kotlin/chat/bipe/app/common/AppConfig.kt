// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

/** Build configuration of the tester app (ADR 0147). The user never picks a server (spec account-start). */
object AppConfig {
    /** Bipe's homeserver (bipe/docs/server-migration.md). */
    const val HOMESERVER = "https://matrix.bipe.net.br"

    /** The device name the homeserver shows in the session list. */
    const val DEVICE_NAME = "Bipe"

    /** Bipe's push gateway (Sygnal, bipe/docs/server-migration.md). Spec push-registration notes. */
    const val PUSH_GATEWAY_URL = "https://push.bipe.net.br/_matrix/push/v1/notify"

    /**
     * The Sygnal app ID of this build: the application ID plus `.android.dev` (debug) or `.android.prod`
     * (tester APK), as bipe/docs/platform-parity.md.
     */
    fun pusherAppId(isDebug: Boolean): String = if (isDebug) "chat.bipe.app.next.android.dev" else "chat.bipe.app.next.android.prod"
}
