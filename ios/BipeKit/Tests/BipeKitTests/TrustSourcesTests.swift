// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// bipe-chat/bipe#227: the profile adapter keeps identity proof, personal confirmation and changes apart.
struct TrustSourcesTests {
    private let ana = "@ana:example.test"
    private func identity(_ verified: Bool, violation: Bool = false, key: String? = "MSK") -> BipeTrustSources.Identity {
        .init(masterKey: key, isVerified: verified, hasVerificationViolation: violation)
    }

    @Test
    func unknownIdentityVerifiesNothing() {
        for id in [nil, identity(true, key: nil), identity(true, key: "")] {
            let facts = BipeTrustSources.profileFacts(userId: ana, identity: id, seal: .verified(name: "Padaria Ana"))
            #expect(facts.identity == "unverified")
            #expect(facts.verifiedName == nil)
            #expect(facts.contact == "unconfirmed")
            #expect(facts.change == "unknown")
        }
    }

    @Test
    func sealAndConfirmationAreSeparateFacts() {
        let sealOnly = BipeTrustSources.profileFacts(userId: ana, identity: identity(false), seal: .verified(name: "Padaria Ana"))
        #expect(sealOnly.identity == "verified")
        #expect(sealOnly.verifiedName == "Padaria Ana")
        #expect(sealOnly.contact == "unconfirmed", "a seal never confirms the person")
        #expect(sealOnly.change == "none")

        let confirmedOnly = BipeTrustSources.profileFacts(userId: ana, identity: identity(true), seal: .none)
        #expect(confirmedOnly.identity == "unverified")
        #expect(confirmedOnly.contact == "confirmed")

        let both = BipeTrustSources.profileFacts(userId: ana, identity: identity(true), seal: .verified(name: "Padaria Ana"))
        #expect(both.identity == "verified")
        #expect(both.contact == "confirmed")
        #expect(both.warningKind == nil, "no warning is no safety assertion")
    }

    @Test
    func identityChangeClearsConfirmationAndShows() {
        // The SDK keeps isVerified false after a violation; a stale true must not survive either.
        for verified in [false, true] {
            let facts = BipeTrustSources.profileFacts(userId: ana, identity: identity(verified, violation: true), seal: .none)
            #expect(facts.contact == "unconfirmed")
            #expect(facts.change == "identity")
        }
    }

    @Test
    func suspendedSealIsNotVerified() {
        let facts = BipeTrustSources.profileFacts(userId: ana, identity: identity(false), seal: .suspended)
        #expect(facts.identity == "suspended")
        #expect(facts.verifiedName == nil)
    }
}
