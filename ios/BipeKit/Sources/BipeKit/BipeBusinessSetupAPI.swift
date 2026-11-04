// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// The Trabalho tab step by step (ADR 0194). Contract: bipe-shared api/openapi.yaml, /v1/me/business/tools, sector,
// setup, suggestions and today, and the public /v1/businesses/{business}/tools. Same calls as BipeApiSetup.kt.

public enum BipeBusinessToolState: String, Decodable, Sendable {
    case off, setup, live
}

public struct BipeBusinessTool: Decodable, Sendable, Identifiable, Equatable {
    /// One id of `BipeRegistries.businessToolIds`.
    public let id: String
    public let state: BipeBusinessToolState
    /// `catalog_service` or `catalog_product`: what the tool needs before it can leave off.
    public let missing: String?
}

public struct BipeBusinessTools: Decodable, Sendable, Equatable {
    public let tools: [BipeBusinessTool]
    public let sector: String?
}

public struct BipeWorkSetupStep: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let done: Bool
}

public struct BipeWorkSetup: Decodable, Sendable, Equatable {
    public let steps: [BipeWorkSetupStep]
    public let doneCount: Int
    public let firstTool: String
}

public struct BipeWorkSuggestion: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let tool: String
}

public struct BipeBusinessToday: Decodable, Sendable, Equatable {
    public let bookings: [BipeBooking]
    public let orders: [BipeOrder]
    public let queueOpen: Bool
    public let queueWaiting: Int
    public let quoteRequests: [BipeQuoteRequest]
}

extension BipeBusinessAPI {
    /// `GET /v1/me/business/tools`.
    public func businessTools() async throws(BipeAPIError) -> BipeBusinessTools {
        try await request("GET", ["v1", "me", "business", "tools"])
    }

    /// `PUT /v1/me/business/tools/{toolId}`. `tool_needs_catalog` when a dependency is missing.
    public func setBusinessTool(id: String, state: BipeBusinessToolState) async throws(BipeAPIError) -> BipeBusinessTools {
        try await request("PUT", ["v1", "me", "business", "tools", id], json: ToolChange(state: state.rawValue))
    }

    /// `PUT /v1/me/business/sector`. nil clears the sector (Pular).
    public func setWorkSector(_ sector: String?) async throws(BipeAPIError) -> BipeBusinessTools {
        try await request("PUT", ["v1", "me", "business", "sector"], json: Sector(sector: sector))
    }

    /// `GET /v1/me/business/setup`.
    public func workSetup() async throws(BipeAPIError) -> BipeWorkSetup {
        try await request("GET", ["v1", "me", "business", "setup"])
    }

    /// `POST /v1/me/business/setup/link-opened`.
    public func workLinkOpened() async throws(BipeAPIError) -> BipeWorkSetup {
        try await request("POST", ["v1", "me", "business", "setup", "link-opened"], json: Empty())
    }

    /// `GET /v1/me/business/suggestions`.
    public func workSuggestions() async throws(BipeAPIError) -> [BipeWorkSuggestion] {
        let list: Suggestions = try await request("GET", ["v1", "me", "business", "suggestions"])
        return list.suggestions
    }

    /// `POST /v1/me/business/suggestions/{id}/dismiss`.
    public func dismissWorkSuggestion(id: String) async throws(BipeAPIError) -> [BipeWorkSuggestion] {
        let list: Suggestions = try await request("POST", ["v1", "me", "business", "suggestions", id, "dismiss"], json: Empty())
        return list.suggestions
    }

    /// `GET /v1/me/business/today`.
    public func businessToday() async throws(BipeAPIError) -> BipeBusinessToday {
        try await request("GET", ["v1", "me", "business", "today"])
    }

    /// `GET /v1/businesses/{business}/tools`. No auth. Live tool IDs only.
    public func liveTools(business: String) async throws(BipeAPIError) -> [String] {
        let live: LiveTools = try await request("GET", ["v1", "businesses", business, "tools"], auth: false)
        return live.tools
    }

    private struct ToolChange: Encodable { let state: String }
    private struct Sector: Encodable { let sector: String? }
    private struct Empty: Encodable {}
    private struct Suggestions: Decodable { let suggestions: [BipeWorkSuggestion] }
    private struct LiveTools: Decodable { let tools: [String] }
}
