// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Same cases as bipe-shared tools/name-lookalikes.test.mjs. */
class NameLookalikesTest {
    @Test
    fun sealLookalikeWithOrWithoutVariationSelector() {
        assertThat(BipeNameLookalikes.sealLookalike("Ana Souza ✅")).isEqualTo("✅")
        assertThat(BipeNameLookalikes.sealLookalike("Ana ✔️")).isEqualTo("✔")
        assertThat(BipeNameLookalikes.sealLookalike("💜 Banco")).isEqualTo("💜")
        assertThat(BipeNameLookalikes.sealLookalike("Ana Souza")).isNull()
        assertThat(BipeNameLookalikes.sealLookalike("Ana 😀")).isNull()
    }

    @Test
    fun contactCollisionAfterFolding() {
        val contacts = listOf("Maria Souza", "Mãe")
        assertThat(BipeNameLookalikes.contactCollision("maria  souza", contacts)).isEqualTo("Maria Souza")
        assertThat(BipeNameLookalikes.contactCollision("Mаria Sоuza", contacts)).isEqualTo("Maria Souza")
        assertThat(BipeNameLookalikes.contactCollision("mae", contacts)).isEqualTo("Mãe")
        assertThat(BipeNameLookalikes.contactCollision("Maria Silva", contacts)).isNull()
        assertThat(BipeNameLookalikes.contactCollision("   ", contacts)).isNull()
    }
}
