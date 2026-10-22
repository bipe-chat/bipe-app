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
}
