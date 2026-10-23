// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Testing

struct InviteTrustTests {
    @Test func trustAppliesOnlyToTheNamedPairAndNeverOverridesBlocking() {
        let pair = "@inviter:example.invalid"
        let pairs: Set<String> = [pair]
        #expect(BipeInviteTrust.acceptsAutomatically(isDm: true, inviterID: pair, isIgnored: false, pairs: pairs))
        #expect(!BipeInviteTrust.acceptsAutomatically(isDm: false, inviterID: pair, isIgnored: false, pairs: pairs))
        #expect(!BipeInviteTrust.acceptsAutomatically(isDm: true, inviterID: pair, isIgnored: true, pairs: pairs))
        #expect(!BipeInviteTrust.acceptsAutomatically(isDm: true, inviterID: "@other:example.invalid", isIgnored: false, pairs: pairs))
        #expect(!BipeInviteTrust.acceptsAutomatically(isDm: true, inviterID: pair, isIgnored: false, pairs: []))
        #expect(!BipeInviteTrust.acceptsAutomatically(isDm: true, inviterID: "", isIgnored: false, pairs: [""]))
    }
}
