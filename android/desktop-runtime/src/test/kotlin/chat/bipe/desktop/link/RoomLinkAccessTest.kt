// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.link.MatrixRoomLinkResult.Preview.Access
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.Membership

class RoomLinkAccessTest {
    @Test fun knockRulesOfferARequest() {
        assertEquals(Access.KNOCK, roomLinkAccess(null, JoinRule.Knock))
        assertEquals(Access.KNOCK, roomLinkAccess(Membership.LEFT, JoinRule.KnockRestricted(emptyList())))
    }

    @Test fun pendingRequestsCannotBeSubmittedAgain() {
        assertEquals(Access.KNOCK_SENT, roomLinkAccess(Membership.KNOCKED, JoinRule.Knock))
        assertEquals(Access.KNOCK_SENT, roomLinkAccess(Membership.KNOCKED, null))
    }

    @Test fun inviteOnlyAndBannedRoomsHaveNoJoinAction() {
        assertEquals(Access.INVITE_ONLY, roomLinkAccess(null, JoinRule.Invite))
        assertEquals(Access.INVITE_ONLY, roomLinkAccess(null, JoinRule.Private))
        assertEquals(Access.INVITE_ONLY, roomLinkAccess(Membership.BANNED, JoinRule.Public))
        assertEquals(Access.JOIN, roomLinkAccess(Membership.LEFT, JoinRule.Public))
    }
}
