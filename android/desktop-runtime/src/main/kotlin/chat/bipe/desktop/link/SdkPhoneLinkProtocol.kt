// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.MatrixAccountRuntime
import org.matrix.rustcomponents.sdk.CheckCodeSender
import org.matrix.rustcomponents.sdk.GeneratedQrLoginProgress
import org.matrix.rustcomponents.sdk.GeneratedQrLoginProgressListener
import org.matrix.rustcomponents.sdk.HumanQrLoginException
import org.matrix.rustcomponents.sdk.OAuthConfiguration

/** Public OAuth client metadata, supplied by the Windows release configuration after MAS registration. */
class WindowsLoginRegistration(clientId: String) {
    private val clientId = clientId.trim().also {
        if (it.isEmpty() || it.any(Char::isWhitespace)) throw PhoneLinkException(PhoneLinkFailure.REGISTRATION_REQUIRED)
    }

    val homeserver: String get() = WindowsOAuthMetadata.HOMESERVER

    internal fun oauth() = OAuthConfiguration(
        clientName = WindowsOAuthMetadata.CLIENT_NAME,
        redirectUri = WindowsOAuthMetadata.REDIRECT_URI,
        clientUri = WindowsOAuthMetadata.CLIENT_URI,
        logoUri = null,
        tosUri = null,
        policyUri = null,
        staticRegistrations = mapOf(WindowsOAuthMetadata.ISSUER to clientId, WindowsOAuthMetadata.HOMESERVER to clientId),
    )
}

/** Same native SDK generated-code protocol as Mac. No password/phone scanner/SDK feature change is needed here. */
internal class SdkPhoneLinkProtocol(
    private val runtime: MatrixAccountRuntime,
    private val registration: WindowsLoginRegistration,
) : PhoneLinkProtocol {
    private val monitor = Any()
    private sealed interface Phase {
        data object Idle : Phase
        class Running(val challenges: MutableList<NativeChallenge>) : Phase
        data object Closed : Phase
    }
    private var phase: Phase = Phase.Idle

    override suspend fun generate(onUpdate: (PhoneLinkUpdate) -> Unit) {
        runtime.lifetime.requireCurrent()
        synchronized(monitor) {
            if (phase != Phase.Idle) throw PhoneLinkException(PhoneLinkFailure.PROTOCOL)
            phase = Phase.Running(mutableListOf())
        }
        val handler = runtime.client.newLoginWithQrCodeHandler(registration.oauth())
        try {
            handler.generate(object : GeneratedQrLoginProgressListener {
                override fun onUpdate(state: GeneratedQrLoginProgress) {
                    // Native payload objects are explicitly released, even for callbacks after retirement.
                    val update = when (state) {
                        GeneratedQrLoginProgress.Starting -> PhoneLinkUpdate.Starting
                        is GeneratedQrLoginProgress.QrReady -> {
                            try { PhoneLinkUpdate.QrReady(LinkQrPayload(state.qrCode.toBytes())) }
                            finally { state.qrCode.destroy() }
                        }
                        is GeneratedQrLoginProgress.QrScanned -> {
                            val challenge = NativeChallenge(state.checkCodeSender)
                            val accepted = synchronized(monitor) {
                                (phase as? Phase.Running)?.challenges?.add(challenge) == true
                            }
                            if (!accepted) { challenge.close(); return }
                            PhoneLinkUpdate.CheckRequested(challenge)
                        }
                        is GeneratedQrLoginProgress.WaitingForToken -> PhoneLinkUpdate.WaitingForApproval
                        GeneratedQrLoginProgress.SyncingSecrets, GeneratedQrLoginProgress.Done -> PhoneLinkUpdate.ImportingKeys
                    }
                    val running = synchronized(monitor) { phase is Phase.Running }
                    if (running && runtime.lifetime.isCurrent()) onUpdate(update)
                }
            })
        } catch (error: HumanQrLoginException) {
            throw PhoneLinkException(error.safeReason())
        } finally {
            close()
            handler.destroy()
        }
    }

    override suspend fun close() {
        val challenges = synchronized(monitor) {
            val current = phase
            phase = Phase.Closed
            (current as? Phase.Running)?.challenges?.toList() ?: emptyList()
        }
        challenges.forEach { it.close() }
    }

    private class NativeChallenge(private val sender: CheckCodeSender) : CheckCodeChallenge {
        private enum class Phase { READY, SUBMITTED, CLOSED }
        private var phase = Phase.READY

        override suspend fun submit(code: UByte) {
            synchronized(this) {
                if (phase != Phase.READY || code > 99u.toUByte()) throw PhoneLinkException(PhoneLinkFailure.INVALID_CODE)
                phase = Phase.SUBMITTED
            }
            try { sender.send(code) }
            catch (error: HumanQrLoginException) { throw PhoneLinkException(error.safeReason()) }
        }

        @Synchronized fun close() {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            sender.destroy()
        }
    }
}

private fun HumanQrLoginException.safeReason(): PhoneLinkFailure = when (this) {
    is HumanQrLoginException.Expired, is HumanQrLoginException.NotFound -> PhoneLinkFailure.EXPIRED
    is HumanQrLoginException.Cancelled, is HumanQrLoginException.Declined -> PhoneLinkFailure.CANCELLED
    is HumanQrLoginException.ConnectionInsecure -> PhoneLinkFailure.INSECURE
    is HumanQrLoginException.CheckCodeAlreadySent, is HumanQrLoginException.CheckCodeCannotBeSent -> PhoneLinkFailure.INVALID_CODE
    is HumanQrLoginException.OAuthMetadataInvalid -> PhoneLinkFailure.REGISTRATION_REQUIRED
    else -> PhoneLinkFailure.PROTOCOL
}
