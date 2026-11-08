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

/// Contract `AgendaFeed`: a secret URL that shows once.
public struct BipeAgendaFeed: Decodable, Sendable, Equatable {
    public let url: String
    public let includeCustomerFirstName: Bool
}

/// Contract `BusyImport`. The secret address never comes back.
public struct BipeBusyImport: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let agendaId: String
    public let failing: Bool?
}

/// Contract `WebSettings`: the owner's web switches (ADR 0195).
public struct BipeWebSettings: Codable, Sendable, Equatable {
    public var webBooking: Bool
    public var webOrders: Bool
    public var webQueue: Bool

    public init(webBooking: Bool, webOrders: Bool, webQueue: Bool) {
        self.webBooking = webBooking
        self.webOrders = webOrders
        self.webQueue = webQueue
    }
}

/// Contract `WebRecords`: the records a claim moved to the account.
public struct BipeWebRecords: Decodable, Sendable, Equatable {
    public let bookings: [BipeBooking]
    public let orders: [BipeOrder]
}

extension BipeBusinessAPI {
    /// `GET /v1/me/business/web-settings`.
    public func webSettings() async throws(BipeAPIError) -> BipeWebSettings {
        try await request("GET", ["v1", "me", "business", "web-settings"])
    }

    /// `PUT /v1/me/business/web-settings`.
    public func setWebSettings(_ settings: BipeWebSettings) async throws(BipeAPIError) -> BipeWebSettings {
        try await request("PUT", ["v1", "me", "business", "web-settings"], json: settings)
    }

    /// `POST /v1/businesses/{business}/web/claim`. The token's records move to this account; the token stops working.
    public func claimWebRecords(business: String, token: String) async throws(BipeAPIError) -> BipeWebRecords {
        try await request("POST", ["v1", "businesses", business, "web", "claim"], json: WebClaim(token: token))
    }

    private struct WebClaim: Encodable { let token: String }

    /// `POST /v1/me/business/agenda/agendas/{id}/feed`. The URL shows once; a new one revokes the old (ADR 0101).
    public func issueAgendaFeed(agendaId: String, includeFirstName: Bool) async throws(BipeAPIError) -> BipeAgendaFeed {
        try await request("POST", ["v1", "me", "business", "agenda", "agendas", agendaId, "feed"], json: FeedOptions(includeCustomerFirstName: includeFirstName))
    }

    /// `GET .../busy-imports`.
    public func busyImports(agendaId: String) async throws(BipeAPIError) -> [BipeBusyImport] {
        let list: BusyImports = try await request("GET", ["v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports"])
        return list.imports
    }

    /// `POST .../busy-imports`. `bad_request` for an address the server refuses or cannot read.
    public func addBusyImport(agendaId: String, url: String) async throws(BipeAPIError) -> BipeBusyImport {
        try await request("POST", ["v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports"], json: BusyImportInput(url: url))
    }

    /// `DELETE .../busy-imports/{id}`, with its busy blocks.
    public func removeBusyImport(agendaId: String, importId: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "business", "agenda", "agendas", agendaId, "busy-imports", importId])
    }

    private struct FeedOptions: Encodable { let includeCustomerFirstName: Bool }
    private struct BusyImports: Decodable { let imports: [BipeBusyImport] }
    private struct BusyImportInput: Encodable { let url: String }

    /// `GET /v1/business-stops/{workAccount}`: whether this account stopped the work account (ADR 0237).
    public func businessStopped(_ workAccount: String) async throws(BipeAPIError) -> Bool {
        let stop: BusinessStop = try await request("GET", ["v1", "business-stops", workAccount])
        return stop.stopped
    }

    /// `PUT` or `DELETE /v1/business-stops/{workAccount}`.
    public func setBusinessStopped(_ workAccount: String, stopped: Bool) async throws(BipeAPIError) -> Bool {
        let stop: BusinessStop = try await request(stopped ? "PUT" : "DELETE", ["v1", "business-stops", workAccount])
        return stop.stopped
    }

    private struct BusinessStop: Decodable { let stopped: Bool }

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

// MARK: - People on a work account (ADR 0251, ADR 0252; contract /v1/me/business/seats)

public struct BipeSeatDevice: Decodable, Sendable, Identifiable, Equatable {
    public let deviceId: String
    public let kind: String
    public let displayName: String?
    public let addedAt: String
    public var id: String { deviceId }
}

public struct BipeSeat: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let name: String
    public let role: String
    public let status: String
    public let devices: [BipeSeatDevice]
    public let windowExpiresAt: String?
}

public struct BipeSeatMe: Decodable, Sendable, Equatable {
    public let seatId: String?
    public let name: String?
    /// owner, attendant or none. A device with none has no owner rights.
    public let role: String
}

public struct BipeSeatList: Decodable, Sendable, Equatable {
    public let me: BipeSeatMe
    public let seats: [BipeSeat]
    public let unassignedDevices: [BipeSeatDevice]
    public let maxPeople: Int
    public let backupRotationPending: Bool

    /// The people who answer now. A waiting seat is not one yet.
    public var activeSeats: [BipeSeat] { seats.filter { $0.status == "active" } }
}

private struct SeatDeviceAssign: Encodable { let seatId: String }
private struct SeatName: Encodable { let name: String }
private struct SeatClaim: Encodable { let attestation: BipeAttestation }

extension BipeBusinessAPI {
    /// `GET /v1/me/business/seats`. Also lets a phone that just joined take the open owner window.
    public func seats() async throws(BipeAPIError) -> BipeSeatList {
        try await request("GET", ["v1", "me", "business", "seats"])
    }

    /// `POST /v1/me/business/seats`. A waiting attendant, with the window open for their phone.
    public func createSeat(name: String) async throws(BipeAPIError) -> BipeSeat {
        try await request("POST", ["v1", "me", "business", "seats"], json: SeatName(name: name))
    }

    /// `PATCH /v1/me/business/seats/{seatId}`.
    public func renameSeat(id: String, name: String) async throws(BipeAPIError) -> BipeSeat {
        try await request("PATCH", ["v1", "me", "business", "seats", id], json: SeatName(name: name))
    }

    /// `DELETE /v1/me/business/seats/{seatId}`. Ends every session of the person.
    public func removeSeat(id: String) async throws(BipeAPIError) -> BipeSeatList {
        try await request("DELETE", ["v1", "me", "business", "seats", id])
    }

    /// `POST /v1/me/business/seats/claim`. The device of the token joins the seat with the open window.
    public func claimSeat(attestation: BipeAttestation) async throws(BipeAPIError) -> BipeSeatList {
        try await request("POST", ["v1", "me", "business", "seats", "claim"], json: SeatClaim(attestation: attestation))
    }

    /// `POST /v1/me/business/seats/leave`. An attendant only. Ends every session of the person.
    public func leaveSeat() async throws(BipeAPIError) -> BipeSeatList {
        try await request("POST", ["v1", "me", "business", "seats", "leave"], json: Empty())
    }

    /// `POST /v1/me/business/setup/second-seat-skip`. Only the owner answers.
    public func skipSecondSeat() async throws(BipeAPIError) -> BipeWorkSetup {
        try await request("POST", ["v1", "me", "business", "setup", "second-seat-skip"], json: Empty())
    }

    /// `POST /v1/me/business/seats/{seatId}/window`. Only the owner's own seat.
    public func openSeatWindow(seatId: String) async throws(BipeAPIError) -> BipeSeat {
        try await request("POST", ["v1", "me", "business", "seats", seatId, "window"], json: Empty())
    }

    /// `PUT /v1/me/business/seats/devices/{deviceId}`. A device with no person joins a seat.
    public func assignSeatDevice(deviceId: String, seatId: String) async throws(BipeAPIError) -> BipeSeatList {
        try await request("PUT", ["v1", "me", "business", "seats", "devices", deviceId], json: SeatDeviceAssign(seatId: seatId))
    }

    /// `DELETE /v1/me/business/seats/devices/{deviceId}`. Ends the session of that device.
    public func removeSeatDevice(deviceId: String) async throws(BipeAPIError) -> BipeSeatList {
        try await request("DELETE", ["v1", "me", "business", "seats", "devices", deviceId])
    }
}

// MARK: - Who has a customer chat (ADR 0284 phase A; contract /v1/me/business/chats)

/// Contract `ChatAssignmentStatus`.
public enum BipeChatAssignmentStatus: String, Codable, Sendable, CaseIterable {
    case open, waiting, solved
}

/// Contract `ChatAssignment`. A chat with no record is open and in the pool. assigneeSeatId nil: the pool.
public struct BipeChatAssignment: Decodable, Sendable, Equatable {
    public let roomId: String
    public let assigneeSeatId: String?
    public let status: BipeChatAssignmentStatus
    public let openedAt: String
    public let updatedAt: String
}

/// Contract `ChatAssignmentUpdate`. Both seat fields are always sent, null for the pool.
private struct ChatAssignmentUpdate: Encodable {
    let assigneeSeatId: String?
    let expectedAssigneeSeatId: String?
    let status: BipeChatAssignmentStatus?

    enum CodingKeys: String, CodingKey {
        case assigneeSeatId, expectedAssigneeSeatId, status
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(assigneeSeatId, forKey: .assigneeSeatId)
        try container.encode(expectedAssigneeSeatId, forKey: .expectedAssigneeSeatId)
        try container.encodeIfPresent(status, forKey: .status)
    }
}

private struct ChatAssignmentList: Decodable { let chats: [BipeChatAssignment] }

extension BipeBusinessAPI {
    /// `GET /v1/me/business/chats`. 403 `seat_required` on a device with no person.
    public func chatAssignments() async throws(BipeAPIError) -> [BipeChatAssignment] {
        let list: ChatAssignmentList = try await request("GET", ["v1", "me", "business", "chats"])
        return list.chats
    }

    /// `PUT /v1/me/business/chats/{roomId}`. Take (assignee = my seat), give, or put back (nil). 409 `chat_taken` when
    /// expected is not the current assignee; 404 `not_found` when assignee is not an active seat.
    public func updateChatAssignment(roomId: String, assignee: String?, expected: String?,
                                     status: BipeChatAssignmentStatus? = nil) async throws(BipeAPIError) -> BipeChatAssignment {
        try await request("PUT", ["v1", "me", "business", "chats", roomId],
                          json: ChatAssignmentUpdate(assigneeSeatId: assignee, expectedAssigneeSeatId: expected, status: status))
    }
}

// MARK: - Interno, member identities (ADR 0284 phase B; contract /v1/me/business/members)

/// Contract `WorkMember`: a person's member identity. name is the seat name.
public struct BipeWorkMember: Decodable, Sendable, Identifiable, Equatable, Hashable {
    public let seatId: String
    public let userId: String
    public let name: String
    public var id: String { userId }
}

/// Contract `WorkMembers`. me: the member identity of this device's seat, nil before it has one. geralRoomId: nil until
/// a member's app makes Geral and claims it.
public struct BipeWorkMembers: Decodable, Sendable, Equatable {
    public let members: [BipeWorkMember]
    public let me: String?
    public let geralRoomId: String?
}

/// Contract `WorkMemberSession`: a one-use password for m.login.password. The app never stores it.
public struct BipeWorkMemberSession: Decodable, Sendable {
    public let userId: String
    public let password: String
}

/// The member identity rule of registries/accounts.json `memberLocalpartPrefix` (ADR 0284 point 1). The Swift generator
/// does not emit that value yet, so this file holds it; keep it equal to the registry.
public enum BipeMemberIdentity {
    public static let localpartPrefix = "m_"

    /// true for a Matrix ID whose localpart starts with the prefix. No other localpart has `_` (ADR 0284 point 1).
    public static func isMember(_ userId: String) -> Bool {
        userId.hasPrefix("@" + localpartPrefix)
    }
}

private struct WorkGeral: Encodable { let roomId: String }

extension BipeBusinessAPI {
    /// `GET /v1/me/business/members`. A device of the work account with a person, else 403 `seat_required`.
    public func members() async throws(BipeAPIError) -> BipeWorkMembers {
        try await request("GET", ["v1", "me", "business", "members"])
    }

    /// `POST /v1/me/business/members/session`. Makes the seat's member identity when it has none and returns a new
    /// password once.
    public func memberSession() async throws(BipeAPIError) -> BipeWorkMemberSession {
        try await request("POST", ["v1", "me", "business", "members", "session"], json: Empty())
    }

    /// `PUT /v1/me/business/members/geral`. The first claim wins; 409 `geral_exists` after that.
    public func claimGeral(roomId: String) async throws(BipeAPIError) -> BipeWorkMembers {
        try await request("PUT", ["v1", "me", "business", "members", "geral"], json: WorkGeral(roomId: roomId))
    }
}
