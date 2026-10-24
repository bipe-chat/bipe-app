// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeBusinessClaim
import chat.bipe.shared.api.BipeBusinessDomain
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** Specs trust-card and trust-profile: the step rules the card and the ladder read. */
class TrustProfileTest {
    @Test
    fun personWithMissingProfileHasTodoStep() {
        val profile = BipeTrustProfile.create(displayName = "  ", hasAvatar = false, handle = null)
        assertThat(profile.steps.map { it.id }).containsExactly(
            BipeTrustProfile.StepId.ACCOUNT, BipeTrustProfile.StepId.PROFILE, BipeTrustProfile.StepId.EMAIL,
            BipeTrustProfile.StepId.PIX, BipeTrustProfile.StepId.GOVBR,
        ).inOrder()
        assertThat(profile.missingProfileItems).containsExactly(
            BipeTrustProfile.ProfileItem.DISPLAY_NAME, BipeTrustProfile.ProfileItem.HANDLE, BipeTrustProfile.ProfileItem.AVATAR,
        ).inOrder()
        assertThat(profile.completedCount).isEqualTo(1)
        assertThat(profile.totalCount).isEqualTo(5)
        assertThat(profile.liveStepsDone).isFalse()
    }

    @Test
    fun completeProfileCollapsesTheCard() {
        val profile = BipeTrustProfile.create(displayName = "Ana", hasAvatar = true, handle = "ana")
        assertThat(profile.missingProfileItems).isEmpty()
        assertThat(profile.completedCount).isEqualTo(2)
        // Email, Pix and gov.br are "em breve": they do not keep the card open.
        assertThat(profile.steps.drop(2).all { it.status == BipeTrustProfile.Status.COMING_SOON }).isTrue()
        assertThat(profile.liveStepsDone).isTrue()
    }

    @Test
    fun govbrBadgeCountsAsDone() {
        val profile = BipeTrustProfile.create(displayName = "Ana", hasAvatar = true, handle = "ana", personBadge = BadgeLevel.GOVBR)
        assertThat(profile.steps.last()).isEqualTo(BipeTrustProfile.Step(BipeTrustProfile.StepId.GOVBR, BipeTrustProfile.Status.DONE))
        assertThat(profile.completedCount).isEqualTo(3)
    }

    @Test
    fun companyLadderFollowsTheClaim() {
        val none = BipeTrustProfile.create(displayName = "Padaria", hasAvatar = true, handle = "padaria", isCompany = true)
        assertThat(none.steps.map { it.id }).containsExactly(
            BipeTrustProfile.StepId.ACCOUNT, BipeTrustProfile.StepId.PROFILE, BipeTrustProfile.StepId.CNPJ,
            BipeTrustProfile.StepId.PROOF, BipeTrustProfile.StepId.DOMAIN,
        ).inOrder()
        assertThat(none.completedCount).isEqualTo(2)

        val claim = BipeBusinessClaim(status = "verified", domains = listOf(BipeBusinessDomain("padaria.com.br", Instant.ofEpochSecond(1))))
        val verified = BipeTrustProfile.create(displayName = "Padaria", hasAvatar = true, handle = "padaria", isCompany = true, business = claim)
        assertThat(verified.completedCount).isEqualTo(5)
        assertThat(verified.liveStepsDone).isTrue()
    }
}
