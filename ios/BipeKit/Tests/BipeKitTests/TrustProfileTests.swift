// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Specs trust-card and trust-profile: the step rules the card and the ladder read.
struct TrustProfileTests {
    @Test
    func personWithMissingProfileHasTodoStep() {
        let profile = BipeTrustProfile(displayName: "  ", hasAvatar: false, handle: nil)
        #expect(profile.steps.map(\.id) == [.account, .profile, .email, .pix, .govbr])
        #expect(profile.missingProfileItems == [.displayName, .handle, .avatar])
        #expect(profile.completedCount == 1)
        #expect(profile.totalCount == 5)
        #expect(!profile.liveStepsDone)
    }

    @Test
    func completeProfileCollapsesTheCard() {
        let profile = BipeTrustProfile(displayName: "Ana", hasAvatar: true, handle: "ana")
        #expect(profile.missingProfileItems.isEmpty)
        #expect(profile.completedCount == 2)
        // Email, Pix and gov.br are "em breve": they do not keep the card open.
        #expect(profile.steps.dropFirst(2).allSatisfy { $0.status == .comingSoon })
        #expect(profile.liveStepsDone)
    }

    @Test
    func govbrBadgeCountsAsDone() {
        let profile = BipeTrustProfile(displayName: "Ana", hasAvatar: true, handle: "ana", personBadge: .govbr)
        #expect(profile.steps.last == .init(id: .govbr, status: .done))
        #expect(profile.completedCount == 3)
    }

    @Test
    func companyLadderFollowsTheClaim() {
        let none = BipeTrustProfile(displayName: "Padaria", hasAvatar: true, handle: "padaria", accountType: .company)
        #expect(none.steps.map(\.id) == [.account, .profile, .cnpj, .proof, .domain])
        #expect(none.completedCount == 2)

        let claim = BipeBusinessClaim(status: .verified, domains: [.init(domain: "padaria.com.br", verifiedAt: Date(timeIntervalSince1970: 1))])
        let verified = BipeTrustProfile(displayName: "Padaria", hasAvatar: true, handle: "padaria", accountType: .company, business: claim)
        #expect(verified.completedCount == 5)
        #expect(verified.liveStepsDone)
    }
}
