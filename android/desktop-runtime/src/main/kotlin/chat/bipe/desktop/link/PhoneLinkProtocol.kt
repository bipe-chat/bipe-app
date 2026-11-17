// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.AccountResource
import java.util.Base64

/** The SDK's binary payload, never interpreted as a URL or passed through UTF-8. */
class LinkQrPayload(bytes: ByteArray) {
    private val bytes = bytes.copyOf()
    init { require(bytes.isNotEmpty() && bytes.size <= 2953) }
    fun copyBytes(): ByteArray = bytes.copyOf()
    /** The same RFC4648 fallback as the phone's paste-code path. Not the QR renderer's input. */
    fun copyText(): String = Base64.getEncoder().encodeToString(bytes)
    override fun toString() = "LinkQrPayload(redacted)"
}

interface CheckCodeChallenge {
    /** Exactly one submission to the native secure channel. Values 00 through 99 keep their meaning. */
    suspend fun submit(code: UByte)
}

sealed interface PhoneLinkUpdate {
    data object Starting : PhoneLinkUpdate
    class QrReady(val payload: LinkQrPayload) : PhoneLinkUpdate
    class CheckRequested(val challenge: CheckCodeChallenge) : PhoneLinkUpdate
    data object WaitingForApproval : PhoneLinkUpdate
    data object ImportingKeys : PhoneLinkUpdate
}

interface PhoneLinkProtocol : AccountResource {
    /** Return only after approval and secret transfer, not merely after a token/progress callback. */
    suspend fun generate(onUpdate: (PhoneLinkUpdate) -> Unit)
}

enum class PhoneLinkFailure {
    EXPIRED, CANCELLED, INVALID_CODE, INSECURE, REGISTRATION_REQUIRED, PROTOCOL, STORAGE, DUPLICATE_ACCOUNT,
}

class PhoneLinkException(val reason: PhoneLinkFailure) : Exception("Desktop phone link: ${reason.name}")
