// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallReferrerTest {
    @Test
    fun inviteReferrerGivesTheCode() {
        assertThat(BipeInstallReferrer.inviteCode("convite/Q7RM-2XKP-9HTV")).isEqualTo("Q7RM-2XKP-9HTV")
        assertThat(BipeInstallReferrer.inviteCode("utm_source=bipe&referrer=convite/q7rm2xkp9htv")).isEqualTo("Q7RM-2XKP-9HTV")
    }

    @Test
    fun encodedReferrerGivesTheCode() {
        assertThat(BipeInstallReferrer.inviteCode("convite%2FQ7RM-2XKP-9HTV")).isEqualTo("Q7RM-2XKP-9HTV")
        assertThat(BipeInstallReferrer.inviteCode("referrer%3Dconvite%252FQ7RM-2XKP-9HTV")).isNull()
    }

    @Test
    fun otherReferrersHoldNoCode() {
        assertThat(BipeInstallReferrer.inviteCode("utm_source=google-play&utm_medium=organic")).isNull()
        assertThat(BipeInstallReferrer.inviteCode("utm_source=ABCD-EFGH-JKMN")).isNull()
        assertThat(BipeInstallReferrer.inviteCode("convite/hello")).isNull()
        assertThat(BipeInstallReferrer.inviteCode("")).isNull()
    }

    @Test
    fun descriptionHidesTheReferrer() {
        assertThat(BipeInstallReferrer.Result.Read("convite/Q7RM-2XKP-9HTV").toString()).isEqualTo("Read(set)")
    }
}
