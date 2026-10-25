// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Spec business-claim (bipe-chat/bipe#249): CNPJ input, website input, the claim answer, the live company steps.
struct BusinessClaimTests {
    @Test
    func cnpjSharedVectors() throws {
        let vectors = try SharedVectors.array("cnpj.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let input = try #require(vector["input"] as? String)
            #expect(BipeCNPJ.normalized(input) == vector["cnpj"] as? String, "normalized(\(input))")
        }
    }

    @Test
    func websiteInput() {
        #expect(BipeCNPJ.domain("Padaria.com.br") == "padaria.com.br")
        #expect(BipeCNPJ.domain("https://www.padaria.com.br/contato?x=1") == "padaria.com.br")
        #expect(BipeCNPJ.domain(" loja.padaria.com.br. ") == "loja.padaria.com.br")
        for bad in ["", "padaria", "http://", "padaria .com.br", "-padaria.com.br", "padaria.c"] {
            #expect(BipeCNPJ.domain(bad) == nil, "\(bad)")
        }
    }

    @Test
    func claimAnswerDecodes() throws {
        let json = """
        {"cnpj":"11222333000181","status":"awaiting_proof","challenge":"bipe-claim:abc","registry":{"active":true,"legal_name":"Padaria Ana Ltda",
        "checked_month":"2026-09"},"domains":[{"domain":"padaria.com.br","txt_name":"_bipe.padaria.com.br","txt_value":"bipe-verify=1"}],
        "reserved_names":[{"name":"padaria","approved":false}]}
        """
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let claim = try decoder.decode(BipeBusinessClaim.self, from: Data(json.utf8))
        #expect(claim.status == .awaitingProof)
        #expect(claim.challenge == "bipe-claim:abc")
        #expect(claim.registry?.legalName == "Padaria Ana Ltda")
        #expect(claim.registry?.tradeName == nil)
        #expect(claim.domains.first?.txtName == "_bipe.padaria.com.br")
        #expect(claim.reservedNames == [.init(name: "padaria", approved: false)])
    }

    @Test
    func companyStepsAreTodoWithTheClaimScreen() {
        let none = BipeTrustProfile(displayName: "Padaria", hasAvatar: true, handle: "padaria", accountType: .company, claimEnabled: true)
        #expect(none.steps.dropFirst(2).allSatisfy { $0.status == .todo })
        #expect(!none.liveStepsDone)
        let active = BipeTrustProfile(displayName: "Padaria", hasAvatar: true, handle: "padaria", accountType: .company,
                                      business: BipeBusinessClaim(status: .awaitingProof), claimEnabled: true)
        #expect(active.steps.map(\.status) == [.done, .done, .done, .todo, .todo])
    }
}
