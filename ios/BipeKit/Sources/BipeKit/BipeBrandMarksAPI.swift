// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Brand marks for resellers (ADR 0162, bipe-chat/bipe#486). Contract: bipe-shared api/openapi.yaml, the
// /v1/me/brand-marks and /v1/brand-marks/invites routes. The public GET /v1/brand-marks/{userId} is in BipeAPIClient.

/** HeldBrandMark: a mark the caller's work account holds. */
public struct BipeHeldBrandMark: Decodable, Sendable, Equatable, Identifiable {
    public let id: String
    public let brand: String
    public let brandName: String
    public let label: String
    public let grantedAt: Date
}

/** BrandMarkInvite: an open invite of the caller's brand. */
public struct BipeBrandMarkInvite: Decodable, Sendable, Equatable, Identifiable {
    public let id: String
    public let label: String
    public let createdAt: Date
    public let expiresAt: Date
}

/** CreatedBrandMarkInvite: the code is returned only here. */
public struct BipeCreatedBrandMarkInvite: Decodable, Sendable, Equatable {
    public let invite: BipeBrandMarkInvite
    public let code: String
    public let link: String
}

/** GrantedBrandMark: an account that holds the caller's mark. */
public struct BipeGrantedBrandMark: Decodable, Sendable, Equatable, Identifiable {
    public let id: String
    public let reseller: String
    public let label: String
    public let grantedAt: Date
}

/** GrantedBrandMarks. */
public struct BipeGrantedBrandMarks: Decodable, Sendable, Equatable {
    public let invites: [BipeBrandMarkInvite]
    public let marks: [BipeGrantedBrandMark]
}

/** BrandMarkInvitePreview: what an invite grants, before the reseller answers. */
public struct BipeBrandMarkInvitePreview: Decodable, Sendable, Equatable {
    public let brand: String
    public let brandName: String
    public let cnpj: String
    public let label: String
    public let expiresAt: Date
}

public enum BipeBrandMarkCode {
    /// The 16-character code out of a bare code, a `/marca/#<code>` link or a pasted message, like the server's
    /// `brandMarkCode()`. `nil` when there is none.
    public static func extract(_ raw: String) -> String? {
        let pattern = /(?:^|[#\/\s])([A-Za-z0-9_\-]{16})(?=$|[\s.,;!?)])/
        return raw.trimmingCharacters(in: .whitespacesAndNewlines).firstMatch(of: pattern).map { String($0.1) }
    }
}

public extension BipeBusinessAPI {
    /// `GET /v1/me/brand-marks`.
    func heldBrandMarks() async throws(BipeAPIError) -> [BipeHeldBrandMark] {
        let held: Held = try await request("GET", ["v1", "me", "brand-marks"])
        return held.marks
    }

    /// `DELETE /v1/me/brand-marks/{markId}`: the brand or the reseller ends a mark.
    func endBrandMark(id: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "brand-marks", id])
    }

    /// `GET /v1/brand-marks/invites/{code}`. 404 for every code that does not work.
    func brandMarkInvite(code: String) async throws(BipeAPIError) -> BipeBrandMarkInvitePreview {
        try await request("GET", ["v1", "brand-marks", "invites", code])
    }

    /// `POST /v1/brand-marks/invites/{code}/accept`.
    func acceptBrandMark(code: String) async throws(BipeAPIError) -> BipeHeldBrandMark {
        try await request("POST", ["v1", "brand-marks", "invites", code, "accept"])
    }

    /// `POST /v1/brand-marks/invites/{code}/refuse`.
    func refuseBrandMark(code: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("POST", ["v1", "brand-marks", "invites", code, "refuse"])
    }

    /// `GET /v1/me/brand-marks/granted`.
    func grantedBrandMarks() async throws(BipeAPIError) -> BipeGrantedBrandMarks {
        try await request("GET", ["v1", "me", "brand-marks", "granted"])
    }

    /// `POST /v1/me/brand-marks/invites`. 403 brand_not_verified without a verified claim.
    func createBrandMarkInvite(label: String) async throws(BipeAPIError) -> BipeCreatedBrandMarkInvite {
        try await request("POST", ["v1", "me", "brand-marks", "invites"], json: InviteRequest(label: label))
    }

    /// The name a brand mark of this account would carry: the trade name, else the legal name, of a verified claim
    /// with an active registry record (`GET /v1/me/business`). `nil`: the account may not grant marks.
    func ownBrandName() async throws(BipeAPIError) -> String? {
        let claim: BipeBusinessClaim
        do {
            claim = try await request("GET", ["v1", "me", "business"])
        } catch .server(.notFound, _, _) {
            return nil
        }
        guard claim.status == .verified, let registry = claim.registry, registry.active else { return nil }
        return registry.tradeName ?? registry.legalName
    }

    /// `DELETE /v1/me/brand-marks/invites/{inviteId}`.
    func cancelBrandMarkInvite(id: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "brand-marks", "invites", id])
    }
}

private struct Held: Decodable { let marks: [BipeHeldBrandMark] }
private struct InviteRequest: Encodable { let label: String }
