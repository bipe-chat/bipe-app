// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.os.Build
import chat.bipe.app.common.AppConfig
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.OAuthConfiguration
import org.matrix.rustcomponents.sdk.Session

/**
 * The MSC4108 login side of spec recovery-old-phone. The Matrix ID is unknown until the login is done, so the client
 * opens a fresh store folder; at done the folder moves to the account's folder and the session restores from it.
 */
object QrLogin {
    /** The fresh store of a QR login that has not finished. */
    const val FOLDER = "qr-login"

    /** The phone model for the OAuth client name and the user agent, never for anything else. */
    private val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** A client for the server in the code (else the Bipe homeserver), in an empty store. */
    suspend fun makeClient(context: Context, serverName: String?, sessionStore: SessionStore): Client {
        AccountSession.deleteStoreFolder(context, FOLDER)
        val paths = AccountSession.storePaths(context, FOLDER)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
        return AccountStep.run("build client") {
            AccountSession.builder(paths, sessionStore)
                .serverNameOrHomeserverUrl(serverName ?: AppConfig.HOMESERVER)
                // The MAS consent page on the old phone shows the user agent: it names the phone model (ADR 0059).
                .userAgent("Bipe/$version ($model; Android ${Build.VERSION.RELEASE})")
                .build()
        }
    }

    /**
     * The OAuth client of this phone (MAS dynamic client registration). The device code grant never opens the redirect
     * URI; MAS needs one, as the reverse domain of clientUri.
     */
    val oauthConfiguration: OAuthConfiguration
        get() = OAuthConfiguration(
            clientName = "Bipe ($model)",
            redirectUri = "chat.bipe:/callback",
            clientUri = AppConfig.PUBLIC_SITE,
            logoUri = null,
            tosUri = null,
            policyUri = null,
            staticRegistrations = emptyMap(),
        )

    /**
     * After LoginWithQrCodeHandler.scan finished: saves the session, moves the fresh store to the account's folder and
     * restores the session from it. The caller drops the login client first. Never signs in again: the crypto store
     * belongs to this device.
     */
    suspend fun adopt(context: Context, session: Session, sessionStore: SessionStore): AccountSession {
        sessionStore.save(session)
        val (freshData, freshCache) = AccountSession.storePaths(context, FOLDER)
        val (data, cache) = AccountSession.storePaths(context, AccountSession.folderName(session.userId))
        for ((from, to) in listOf(freshData to data, freshCache to cache)) {
            to.deleteRecursively()
            check(from.renameTo(to)) { "store move failed" }
        }
        return AccountSession.restore(context, session, sessionStore)
    }
}
