// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeTrustFacts
import chat.bipe.shared.generated.BipeTrustInput

/**
 * The source adapter of the separate trust facts (ADR 0074, bipe-shared docs/trust-facts.md) for a person's profile.
 * It fills BipeTrustInput only from authenticated sources: the SDK's cross-signing state of the person and the
 * company seal that BadgeVerifier checked. Names, handles and profile fields never reach it.
 */
object BipeTrustSources {
    /** The person's identity as the SDK reports it (Encryption.userIdentity). */
    data class Identity(
        /** UserIdentity.masterKey: the key every fact binds to. */
        val masterKey: String?,
        /** UserIdentity.isVerified: the user confirmed this identity (in-person QR or emoji check). */
        val isVerified: Boolean,
        /** UserIdentity.hasVerificationViolation: the identity changed after the user confirmed it. */
        val hasVerificationViolation: Boolean,
    )

    /** The company seal after BadgeVerifier (BipeCompanySealState), reduced to what the facts need. */
    sealed interface Seal {
        data object None : Seal

        /** A valid cnpj badge bound to the person's current master key, with its verified name. */
        data class Verified(val name: String) : Seal

        /** The person had a seal and it no longer verifies (for example after a master key reset). */
        data object Suspended : Seal
    }

    // The SDK gives states, not times. The evaluator needs a proof strictly after a change, so the adapter writes
    // the order the SDK reports: a change at 1, a current proof at 2. The SDK withdraws isVerified on an identity
    // change, so a confirmation that it still reports is current.
    internal const val CHANGE_TIME = 1.0
    internal const val PROOF_TIME = 2.0

    /**
     * Input for BipeTrustFacts.evaluate. identity null (not read, or no cross-signing identity): lifecycle unknown.
     * Device changes are not reported here: the SDK has no complete device history (integration gap, bipe-chat/bipe#229).
     */
    fun profileInput(userId: String, identity: Identity?, seal: Seal): BipeTrustInput {
        val key = identity?.masterKey
        if (identity == null || key.isNullOrEmpty()) return BipeTrustInput(accountId = userId, lifecycleKnown = false)
        val sealVerified = seal as? Seal.Verified
        val confirmed = identity.isVerified && !identity.hasVerificationViolation
        return BipeTrustInput(
            accountId = userId,
            deviceKey = key,
            lifecycleKnown = true,
            identityChangedAt = if (identity.hasVerificationViolation) CHANGE_TIME else null,
            badgeStatus = when (seal) {
                is Seal.Verified -> "valid"
                Seal.Suspended -> "device_mismatch"
                Seal.None -> null
            },
            badgeAccountId = sealVerified?.let { userId },
            badgeDeviceKey = sealVerified?.let { key },
            badgeName = sealVerified?.name,
            badgeIssuedAt = sealVerified?.let { PROOF_TIME },
            confirmationMethod = if (confirmed) "in_person" else null,
            confirmationAccountId = if (confirmed) userId else null,
            confirmationDeviceKey = if (confirmed) key else null,
            confirmedAt = if (confirmed) PROOF_TIME else null,
        )
    }

    /** The facts for a profile. */
    fun profileFacts(userId: String, identity: Identity?, seal: Seal): BipeTrustFacts =
        BipeTrustFacts.evaluate(profileInput(userId, identity, seal))
}
