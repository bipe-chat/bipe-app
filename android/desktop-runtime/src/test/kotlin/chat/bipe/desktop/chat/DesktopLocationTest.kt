// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.LiveLocationContent
import org.matrix.rustcomponents.sdk.MsgLikeKind

class DesktopLocationTest {
    @Test fun aReceivedShareUsesTheSenderTimeout() {
        assertEquals(172_800_500L, expiresAt(500uL, 172_800_000uL))
    }

    @Test fun malformedExpirySaturatesWithoutChangingTheUnitOrWrapping() {
        assertEquals(Long.MAX_VALUE, expiresAt(Long.MAX_VALUE.toULong() - 1uL, 2uL))
        assertEquals(Long.MAX_VALUE, expiresAt(ULong.MAX_VALUE, 0uL))
    }

    private fun expiresAt(start: ULong, timeout: ULong): Long {
        val content = LiveLocationContent(true, start, null, timeout, AssetType.SENDER, emptyList())
        return checkNotNull(DesktopLocation.of(MsgLikeKind.LiveLocation(content))?.live).endsAt
    }
}
