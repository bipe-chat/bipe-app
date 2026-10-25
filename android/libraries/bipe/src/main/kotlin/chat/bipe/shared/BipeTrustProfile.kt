// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeBusinessClaim

/**
 * The "Perfil de confiança" ladder from bipe/docs/trust-levels.md. Only the account owner sees it.
 * A person proves who they are (email, Pix, gov.br). A company proves its CNPJ instead (ADR 0042, cnpj-verification.md).
 * Step 2 is computed here from the Matrix profile and the handle.
 * Port of BipeTrustProfile on iOS: same step and item ids.
 */
data class BipeTrustProfile(
    val isCompany: Boolean,
    val steps: List<Step>,
    /** Empty when step 2 is done. */
    val missingProfileItems: List<ProfileItem>,
) {
    enum class StepId(val id: String) {
        ACCOUNT("account"),
        PROFILE("profile"),
        EMAIL("email"),
        PIX("pix"),
        GOVBR("govbr"),
        CNPJ("cnpj"),
        PROOF("proof"),
        DOMAIN("domain"),
    }

    enum class Status(val id: String) { DONE("done"), TODO("todo"), COMING_SOON("coming_soon") }

    /** The parts of step 2, in the order the card shows them. */
    enum class ProfileItem(val id: String) { DISPLAY_NAME("display_name"), HANDLE("handle"), AVATAR("avatar") }

    data class Step(val id: StepId, val status: Status)

    val completedCount: Int get() = steps.count { it.status == Status.DONE }
    val totalCount: Int get() = steps.size
    val progress: Float get() = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount

    /** `true` when no step that ships today is left to do; steps that are "em breve" do not count (ADR 0112). */
    val liveStepsDone: Boolean get() = steps.none { it.status == Status.TODO }

    companion object {
        /**
         * [business] is `GET /v1/me/business`: `null` when the account has no claim or the claim did not load.
         * [personBadge] is the level of the account's own badge from `GET /v1/badges`: `null` without one or until it loads.
         * [govbrEnabled] makes step 5 live (bipe#545); else it shows "Em breve".
         * [claimEnabled] makes the company steps live (business-claim, bipe#249): a step not done is todo, else "Em breve".
         */
        fun create(
            displayName: String?,
            hasAvatar: Boolean,
            handle: String?,
            isCompany: Boolean = false,
            business: BipeBusinessClaim? = null,
            personBadge: BadgeLevel? = null,
            govbrEnabled: Boolean = false,
            claimEnabled: Boolean = false,
        ): BipeTrustProfile {
            val missing = buildList {
                if (displayName.isNullOrBlank()) add(ProfileItem.DISPLAY_NAME)
                if (handle.isNullOrEmpty()) add(ProfileItem.HANDLE)
                if (!hasAvatar) add(ProfileItem.AVATAR)
            }
            val start = listOf(
                Step(StepId.ACCOUNT, Status.DONE),
                Step(StepId.PROFILE, if (missing.isEmpty()) Status.DONE else Status.TODO),
            )
            val rest = if (isCompany) {
                // Without the claim screen (bipe#249) a step that is not done has nothing to tap.
                val cnpjActive = business?.status == "awaiting_proof" || business?.status == "verified"
                val domainVerified = business?.domains.orEmpty().any { it.verifiedAt != null }
                val open = if (claimEnabled) Status.TODO else Status.COMING_SOON
                listOf(
                    Step(StepId.CNPJ, if (cnpjActive) Status.DONE else open),
                    Step(StepId.PROOF, if (business?.status == "verified") Status.DONE else open),
                    Step(StepId.DOMAIN, if (domainVerified) Status.DONE else open),
                )
            } else {
                listOf(
                    Step(StepId.EMAIL, Status.COMING_SOON),
                    Step(StepId.PIX, Status.COMING_SOON),
                    Step(
                        StepId.GOVBR,
                        when {
                            personBadge == BadgeLevel.GOVBR -> Status.DONE
                            govbrEnabled -> Status.TODO
                            else -> Status.COMING_SOON
                        },
                    ),
                )
            }
            return BipeTrustProfile(isCompany = isCompany, steps = start + rest, missingProfileItems = missing)
        }
    }
}
