// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Testing

struct InviteCodeTypingTests {
    @Test func typedCodeIsUppercaseWithDashesEveryFourCharacters() {
        #expect(BipeInviteLink.typedCode("") == "")
        #expect(BipeInviteLink.typedCode("abcd") == "ABCD")
        #expect(BipeInviteLink.typedCode("abcde") == "ABCD-E")
        #expect(BipeInviteLink.typedCode("ab cd.ef/gh_ij") == "ABCD-EFGH-IJ")
        #expect(BipeInviteLink.typedCode("ABCD-") == "ABCD")
        #expect(BipeInviteLink.typedCode("abcdefghijklmnop") == "ABCD-EFGH-IJKL")
        #expect(BipeInviteLink.typedCode("Entra aí: https://bipe.chat/convite/abcd-efgh-jkmn") == "ABCD-EFGH-JKMN")
        #expect(BipeInviteLink.typedCode(BipeInviteLink.typedCode("abcdefgh")) == "ABCD-EFGH")
    }
}
