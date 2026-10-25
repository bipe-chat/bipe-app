// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.net.URLDecoder

/**
 * The Play install referrer (spec onboarding-welcome, bipeinstallreferrer; ADR 0154). The Play build reads it with
 * Google's Install Referrer library; a build without Google Play services uses [None], and the invitee taps "Colar
 * convite".
 */
interface BipeInstallReferrer {
    /** The outcome of one read. */
    sealed interface Result {
        /** The store answered. [referrer] is `null` when the install came without one. */
        data class Read(val referrer: String?) : Result {
            override fun toString(): String = "Read(${if (referrer == null) "none" else "set"})"
        }

        /** This store or phone has no install referrer. The app does not ask again. */
        data object Unsupported : Result

        /** The store did not answer now. The app asks again on the next start. */
        data object TryLater : Result
    }

    suspend fun read(): Result

    /** No Google Play services in this build. */
    object None : BipeInstallReferrer {
        override suspend fun read(): Result = Result.Unsupported
    }

    companion object {
        /**
         * The invite code in a referrer: `referrer=convite/<code>` from `bipe.chat/convite/<code>`, as Play gives it
         * (decoded) or still URL-encoded. Other referrers (a store search, an ad) hold no code.
         */
        fun inviteCode(referrer: String): String? {
            val text = referrer.take(BipeRegistries.INVITE_CODE_MAX_INPUT_LENGTH)
            val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
            val marker = decoded.indexOf("convite/", ignoreCase = true)
            if (marker < 0) return null
            return BipeInviteLink.normalizedCode(decoded.substring(marker + "convite/".length))
        }
    }
}
