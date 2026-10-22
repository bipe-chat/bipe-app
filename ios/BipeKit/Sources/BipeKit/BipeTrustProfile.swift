// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The "Perfil de confiança" ladder from bipe/docs/trust-levels.md. Only the account owner sees it.
/// A person proves who they are (email, Pix, gov.br). A company proves its CNPJ instead (ADR 0042, cnpj-verification.md).
/// Step 2 is computed here from the Matrix profile and the handle. Android mirrors the same step and item ids.
public nonisolated struct BipeTrustProfile: Sendable, Equatable {
    public enum StepID: String, Sendable, CaseIterable {
        case account
        case profile
        case email
        case pix
        case govbr
        case cnpj
        case proof
        case domain
    }
    
    public enum Status: String, Sendable {
        case done
        case todo
        case comingSoon = "coming_soon"
    }
    
    /// The parts of step 2, in the order the card shows them.
    public enum ProfileItem: String, Sendable, CaseIterable {
        case displayName = "display_name"
        case handle
        case avatar
    }
    
    public struct Step: Sendable, Equatable, Identifiable {
        public let id: StepID
        public let status: Status
    }
    
    public let isCompany: Bool
    public let steps: [Step]
    /// Empty when step 2 is done.
    public let missingProfileItems: [ProfileItem]
    
    public var completedCount: Int {
        steps.filter { $0.status == .done }.count
    }
    
    public var totalCount: Int {
        steps.count
    }
    
    public var progress: Double {
        totalCount == 0 ? 0 : Double(completedCount) / Double(totalCount)
    }
    
    /// `business` is `GET /v1/me/business`: `nil` when the account has no claim or the claim did not load.
    /// `personBadge` is the level of the account's own badge from `GET /v1/badges`: `nil` without one or until it loads.
    /// `govbrEnabled` makes step 5 live (bipe#544); else it shows "Em breve".
    public init(displayName: String?,
                hasAvatar: Bool,
                handle: String?,
                accountType: BipeAccountType = .personal,
                business: BipeBusinessClaim? = nil,
                personBadge: BadgeLevel? = nil,
                govbrEnabled: Bool = false) {
        var missing: [ProfileItem] = []
        if displayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true {
            missing.append(.displayName)
        }
        if handle?.isEmpty ?? true {
            missing.append(.handle)
        }
        if !hasAvatar {
            missing.append(.avatar)
        }
        missingProfileItems = missing
        isCompany = accountType == .company
        
        let start = [Step(id: .account, status: .done),
                     Step(id: .profile, status: missing.isEmpty ? .done : .todo)]
        if isCompany {
            // The claim screen is not in the app yet (bipe#249): a step that is not done has nothing to tap.
            let cnpjActive = business?.status == .awaitingProof || business?.status == .verified
            let domainVerified = business?.domains.contains { $0.verifiedAt != nil } ?? false
            steps = start + [Step(id: .cnpj, status: cnpjActive ? .done : .comingSoon),
                             Step(id: .proof, status: business?.status == .verified ? .done : .comingSoon),
                             Step(id: .domain, status: domainVerified ? .done : .comingSoon)]
        } else {
            steps = start + [Step(id: .email, status: .comingSoon),
                             Step(id: .pix, status: .comingSoon),
                             Step(id: .govbr, status: personBadge == .govbr ? .done : govbrEnabled ? .todo : .comingSoon)]
        }
    }
}
