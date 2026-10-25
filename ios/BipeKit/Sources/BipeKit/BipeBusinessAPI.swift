// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Business tools: Catálogo and Agenda (ADRs 0099 to 0104). Contract: bipe-shared api/openapi.yaml (tags Catálogo
// and Agenda) and api/business-schemas.json. Owner routes (/v1/me/business/...) need the company account's own token.
// Customer routes (/v1/businesses/{business}/...) take the business handle or, without one, its Matrix ID.

/// The business routes with an account's token. Each call reads the token again and retries once on 401,
/// like ``BipeAPIClient/withTokenRetry(accessToken:_:)``.
@MainActor
public struct BipeBusinessAPI {
    public let api: BipeAPIClient
    private let accessToken: @MainActor () -> String?

    public init(api: BipeAPIClient, accessToken: @escaping @MainActor () -> String?) {
        self.api = api
        self.accessToken = accessToken
    }

    // MARK: - Catálogo, owner

    /// `GET /v1/me/business/catalog`. Hidden items included, in the owner's order.
    public func ownerCatalog() async throws(BipeAPIError) -> [BipeCatalogItem] {
        let catalog: Catalog = try await request("GET", ["v1", "me", "business", "catalog"])
        return catalog.items
    }

    /// `POST /v1/me/business/catalog/items`.
    public func createCatalogItem(_ item: BipeCatalogItemInput) async throws(BipeAPIError) -> BipeCatalogItem {
        try await request("POST", ["v1", "me", "business", "catalog", "items"], json: item)
    }

    /// `PUT /v1/me/business/catalog/items/{itemId}`. A full replacement.
    public func replaceCatalogItem(id: String, _ item: BipeCatalogItemInput) async throws(BipeAPIError) -> BipeCatalogItem {
        try await request("PUT", ["v1", "me", "business", "catalog", "items", id], json: item)
    }

    /// `DELETE /v1/me/business/catalog/items/{itemId}`. The server archives the item.
    public func deleteCatalogItem(id: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "business", "catalog", "items", id])
    }

    /// `PUT /v1/me/business/catalog/order`. Every item ID in the new order.
    public func reorderCatalog(itemIds: [String]) async throws(BipeAPIError) -> [BipeCatalogItem] {
        let catalog: Catalog = try await request("PUT", ["v1", "me", "business", "catalog", "order"], json: CatalogOrder(itemIds: itemIds))
        return catalog.items
    }

    /// `PUT /v1/me/business/catalog/items/{itemId}/photo`. `jpeg` must fit the profile photo limits.
    public func setCatalogItemPhoto(id: String, jpeg: Data) async throws(BipeAPIError) -> BipeCatalogItem {
        try await request("PUT", ["v1", "me", "business", "catalog", "items", id, "photo"], raw: (data: jpeg, contentType: "image/jpeg"))
    }

    /// `DELETE /v1/me/business/catalog/items/{itemId}/photo`.
    public func deleteCatalogItemPhoto(id: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "business", "catalog", "items", id, "photo"])
    }

    // MARK: - Catálogo, public

    /// `GET /v1/businesses/{business}/catalog`. No auth. Active items only.
    public func catalog(business: String) async throws(BipeAPIError) -> [BipeCatalogItem] {
        let catalog: Catalog = try await request("GET", ["v1", "businesses", business, "catalog"], auth: false)
        return catalog.items
    }

    // MARK: - Agenda, owner

    /// `GET /v1/me/business/agenda`. Before activation the server answers `not_found` with reason `agenda_off`:
    /// see ``BipeAPIError/isAgendaOff``.
    public func agendaState() async throws(BipeAPIError) -> BipeAgendaState {
        try await request("GET", ["v1", "me", "business", "agenda"])
    }

    /// `PUT /v1/me/business/agenda`. Publishing fails with `bad_request` reason `incomplete`.
    public func saveAgendaSettings(published: Bool, timeZone: String) async throws(BipeAPIError) -> BipeAgendaState {
        try await request("PUT", ["v1", "me", "business", "agenda"], json: AgendaSettings(published: published, timeZone: timeZone))
    }

    /// `POST /v1/me/business/agenda/activate`. Makes the first agenda and the template's services.
    public func activateAgenda(templateId: String, timeZone: String) async throws(BipeAPIError) -> BipeAgendaState {
        try await request("POST", ["v1", "me", "business", "agenda", "activate"], json: AgendaActivation(templateId: templateId, timeZone: timeZone))
    }

    /// `POST /v1/me/business/agenda/agendas`. A new person starts with the first person's hours.
    public func createAgenda(_ agenda: BipeAgendaInput) async throws(BipeAPIError) -> BipeAgenda {
        try await request("POST", ["v1", "me", "business", "agenda", "agendas"], json: agenda)
    }

    /// `PUT /v1/me/business/agenda/agendas/{agendaId}`.
    public func replaceAgenda(id: String, _ agenda: BipeAgendaInput) async throws(BipeAPIError) -> BipeAgenda {
        try await request("PUT", ["v1", "me", "business", "agenda", "agendas", id], json: agenda)
    }

    /// `DELETE /v1/me/business/agenda/agendas/{agendaId}`. Bookings keep it.
    public func deleteAgenda(id: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("DELETE", ["v1", "me", "business", "agenda", "agendas", id])
    }

    /// `GET /v1/me/business/agenda/agendas/{agendaId}/availability`.
    public func availability(agendaId: String) async throws(BipeAPIError) -> [BipeAvailabilityRule] {
        let rules: AvailabilityRules = try await request("GET", ["v1", "me", "business", "agenda", "agendas", agendaId, "availability"])
        return rules.rules
    }

    /// `PUT /v1/me/business/agenda/agendas/{agendaId}/availability`. Ranges of one weekday must not overlap.
    public func setAvailability(agendaId: String, rules: [BipeAvailabilityRule]) async throws(BipeAPIError) -> [BipeAvailabilityRule] {
        let saved: AvailabilityRules = try await request("PUT", ["v1", "me", "business", "agenda", "agendas", agendaId, "availability"],
                                                          json: AvailabilityRules(rules: rules))
        return saved.rules
    }

    /// `POST /v1/me/business/agenda/blocks`. Removes [start, end) from the hours of these agendas.
    public func block(agendaIds: [String], start: Date, end: Date) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("POST", ["v1", "me", "business", "agenda", "blocks"],
                                                            json: AgendaBlock(agendaIds: agendaIds, start: BipeInstant.string(start), end: BipeInstant.string(end)))
    }

    /// `POST /v1/me/business/agenda/close-day`. `date` is a local date of the business, `yyyy-MM-dd`.
    public func closeDay(agendaIds: [String], date: String) async throws(BipeAPIError) {
        let _: BipeAPIClient.NoContent = try await request("POST", ["v1", "me", "business", "agenda", "close-day"],
                                                            json: AgendaCloseDay(agendaIds: agendaIds, date: date))
    }

    /// `PUT /v1/me/business/agenda/policy`. Every field.
    public func setAgendaPolicy(_ policy: BipeAgendaPolicy) async throws(BipeAPIError) -> BipeAgendaPolicy {
        try await request("PUT", ["v1", "me", "business", "agenda", "policy"], json: policy)
    }

    /// `GET /v1/me/business/agenda/bookings`. Bookings that start on these local dates, by start.
    public func ownerBookings(dateFrom: String, dateTo: String, agendaId: String? = nil) async throws(BipeAPIError) -> [BipeBooking] {
        var query = [URLQueryItem(name: "date_from", value: dateFrom), URLQueryItem(name: "date_to", value: dateTo)]
        if let agendaId { query.append(URLQueryItem(name: "agenda_id", value: agendaId)) }
        let bookings: Bookings = try await request("GET", ["v1", "me", "business", "agenda", "bookings"], query: query)
        return bookings.bookings
    }

    /// `POST /v1/me/business/agenda/bookings` (source owner, an encaixe). Only an overlap is refused (`slot_taken`).
    public func ownerBook(serviceId: String, agendaId: String, start: Date, customer: BipeBookingCustomer) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "me", "business", "agenda", "bookings"],
                          json: OwnerBooking(serviceId: serviceId, agendaId: agendaId, start: BipeInstant.string(start), customer: customer))
    }

    /// `POST /v1/me/business/agenda/bookings/{id}/move`.
    public func ownerMove(bookingId: String, agendaId: String, start: Date) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "me", "business", "agenda", "bookings", bookingId, "move"],
                          json: BookingMove(agendaId: agendaId, start: BipeInstant.string(start)))
    }

    /// `POST /v1/me/business/agenda/bookings/{id}/{cancel|no-show|accept|refuse}`.
    public func ownerAction(bookingId: String, _ action: BipeOwnerBookingAction) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "me", "business", "agenda", "bookings", bookingId, action.rawValue])
    }

    // MARK: - Agenda, customer

    /// `GET /v1/businesses/{business}/agenda/services`. No auth. `agenda_not_published` before publishing.
    public func services(business: String) async throws(BipeAPIError) -> [BipeCatalogItem] {
        let services: Services = try await request("GET", ["v1", "businesses", business, "agenda", "services"], auth: false)
        return services.services
    }

    /// `GET /v1/businesses/{business}/agenda/agendas`. No auth. With `serviceId`, only who does that service.
    public func agendas(business: String, serviceId: String? = nil) async throws(BipeAPIError) -> [BipeAgenda] {
        let query = serviceId.map { [URLQueryItem(name: "service_id", value: $0)] } ?? []
        let agendas: Agendas = try await request("GET", ["v1", "businesses", business, "agenda", "agendas"], query: query, auth: false)
        return agendas.agendas
    }

    /// `GET /v1/businesses/{business}/agenda/slots`. No auth. Dates are local dates of the business, at most 31 days.
    /// `agendaId` nil asks for any agenda.
    public func slots(business: String, serviceId: String, agendaId: String?, dateFrom: String, dateTo: String) async throws(BipeAPIError) -> BipeSlotDays {
        let query = [URLQueryItem(name: "service_id", value: serviceId),
                     URLQueryItem(name: "agenda_id", value: agendaId ?? BipeAgenda.any),
                     URLQueryItem(name: "date_from", value: dateFrom),
                     URLQueryItem(name: "date_to", value: dateTo)]
        return try await request("GET", ["v1", "businesses", business, "agenda", "slots"], query: query, auth: false)
    }

    /// `POST /v1/businesses/{business}/agenda/hold`. Holds the time for 5 minutes. `agendaId` nil is any agenda.
    public func hold(business: String, serviceId: String, agendaId: String?, start: Date) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "businesses", business, "agenda", "hold"],
                          json: BookingHold(serviceId: serviceId, agendaId: agendaId ?? BipeAgenda.any, start: BipeInstant.string(start)))
    }

    /// `POST /v1/businesses/{business}/agenda/bookings/{id}/confirm`. `hold_expired` after the 5 minutes.
    public func confirm(business: String, bookingId: String) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "businesses", business, "agenda", "bookings", bookingId, "confirm"])
    }

    /// `POST /v1/businesses/{business}/agenda/bookings/{id}/cancel`. `cancellation_window_closed` inside the window.
    public func cancel(business: String, bookingId: String) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "businesses", business, "agenda", "bookings", bookingId, "cancel"])
    }

    /// `POST /v1/businesses/{business}/agenda/bookings/{id}/move`. `agendaId` nil is any agenda.
    public func move(business: String, bookingId: String, agendaId: String?, start: Date) async throws(BipeAPIError) -> BipeBooking {
        try await request("POST", ["v1", "businesses", business, "agenda", "bookings", bookingId, "move"],
                          json: BookingMove(agendaId: agendaId ?? BipeAgenda.any, start: BipeInstant.string(start)))
    }

    /// `GET /v1/me/bookings`. Open bookings: a person's own, or a company's as the business (ADR 0102).
    public func myBookings() async throws(BipeAPIError) -> [BipeBooking] {
        let bookings: Bookings = try await request("GET", ["v1", "me", "bookings"])
        return bookings.bookings
    }

    // MARK: - Private

    private func request<Response: Decodable>(_ method: String,
                                              _ path: [String],
                                              query: [URLQueryItem] = [],
                                              json: (any Encodable)? = nil,
                                              raw: (data: Data, contentType: String)? = nil,
                                              auth: Bool = true) async throws(BipeAPIError) -> Response {
        var body = raw
        if let json {
            do {
                let data = try Self.encoder.encode(json)
                body = (data: data, contentType: "application/json")
            } catch {
                throw .unexpectedResponse(statusCode: nil)
            }
        }
        guard auth else {
            return try await api.send(method: method, path: path, query: query, rawBody: body, accessToken: nil)
        }
        guard let token = accessToken() else {
            throw .server(.unauthorized, reason: nil, statusCode: 401)
        }
        do throws(BipeAPIError) {
            return try await api.send(method: method, path: path, query: query, rawBody: body, accessToken: token)
        } catch {
            // Matrix access tokens are short-lived: the SDK may have refreshed it meanwhile.
            guard error.isUnauthorized, let fresh = accessToken() else { throw error }
            return try await api.send(method: method, path: path, query: query, rawBody: body, accessToken: fresh)
        }
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return encoder
    }()

    private struct Catalog: Decodable { let items: [BipeCatalogItem] }
    private struct CatalogOrder: Encodable { let itemIds: [String] }
    private struct Services: Decodable { let services: [BipeCatalogItem] }
    private struct Agendas: Decodable { let agendas: [BipeAgenda] }
    private struct Bookings: Decodable { let bookings: [BipeBooking] }
    private struct AvailabilityRules: Codable { let rules: [BipeAvailabilityRule] }
    private struct AgendaSettings: Encodable { let published: Bool; let timeZone: String }
    private struct AgendaActivation: Encodable { let templateId: String; let timeZone: String }
    private struct AgendaBlock: Encodable { let agendaIds: [String]; let start: String; let end: String }
    private struct AgendaCloseDay: Encodable { let agendaIds: [String]; let date: String }
    private struct BookingHold: Encodable { let serviceId: String; let agendaId: String; let start: String }
    private struct BookingMove: Encodable { let agendaId: String; let start: String }
    private struct OwnerBooking: Encodable {
        let serviceId: String
        let agendaId: String
        let start: String
        let customer: BipeBookingCustomer
    }
}

/// The contract's instants: UTC with a trailing Z.
public enum BipeInstant {
    public static func string(_ date: Date) -> String {
        date.formatted(Date.ISO8601FormatStyle())
    }
}

// MARK: - Catálogo models

public enum BipeCatalogKind: String, Codable, Sendable, CaseIterable {
    case service
    case product
}

/// Contract: `CatalogPrice`. Display text the business wrote; Bipe moves no money (ADR 0012).
public struct BipeCatalogPrice: Codable, Sendable, Equatable {
    public enum Mode: String, Codable, Sendable, CaseIterable {
        case fixed
        case from
        case onRequest = "on_request"
    }

    public var mode: Mode
    /// For fixed and from: the price as shown, for example `R$ 40,00`.
    public var text: String?
    /// For fixed and from: the BRL amount in centavos. A fixed price with an amount is what Pedidos can order (ADR 0105).
    public var amountCents: Int?

    public init(mode: Mode, text: String? = nil, amountCents: Int? = nil) {
        self.mode = mode
        self.text = mode == .onRequest ? nil : text
        self.amountCents = mode == .onRequest ? nil : amountCents
    }

    /// A fixed or from price written from centavos with ``BipeMoney/text(cents:)``.
    public static func amount(_ mode: Mode, cents: Int) -> BipeCatalogPrice {
        BipeCatalogPrice(mode: mode, text: BipeMoney.text(cents: cents), amountCents: cents)
    }

    private enum CodingKeys: String, CodingKey { case mode, text, amountCents }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // An unknown mode from a newer server shows as on request.
        mode = try Mode(rawValue: container.decode(String.self, forKey: .mode)) ?? .onRequest
        text = try container.decodeIfPresent(String.self, forKey: .text)
        amountCents = try container.decodeIfPresent(Int.self, forKey: .amountCents)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(mode.rawValue, forKey: .mode)
        // on_request carries the mode only (the contract is strict).
        guard mode != .onRequest else { return }
        try container.encode(text ?? "", forKey: .text)
        try container.encodeIfPresent(amountCents, forKey: .amountCents)
    }

    /// The price for the customer: the text, bipe_catalog_price_from with it, or bipe_catalog_price_on_request.
    public var displayText: String {
        switch mode {
        case .fixed: text ?? ""
        case .from: BipeStrings.catalogPriceFrom(text ?? "")
        case .onRequest: BipeStrings.catalogPriceOnRequest
        }
    }
}

/// Contract: `CatalogItem`. A service is also the Agenda's service (ADR 0104).
public struct BipeCatalogItem: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let kind: BipeCatalogKind
    public let name: String
    public let price: BipeCatalogPrice
    /// Plain text. Clients never make links clickable.
    public let description: String?
    public let category: String?
    public let active: Bool
    public let sort: Int
    public let soldOutToday: Bool
    public let durationMinutes: Int?
    public let bufferBeforeMinutes: Int?
    public let bufferAfterMinutes: Int?
    public let agendaIds: [String]?
    /// Public photo URL; a new photo gets a new URL.
    public let photo: URL?

    private enum CodingKeys: String, CodingKey {
        case id, kind, name, price, description, category, active, sort, soldOutToday, durationMinutes, bufferBeforeMinutes
        case bufferAfterMinutes, agendaIds, photo
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        kind = try BipeCatalogKind(rawValue: container.decode(String.self, forKey: .kind)) ?? .product
        name = try container.decode(String.self, forKey: .name)
        price = try container.decode(BipeCatalogPrice.self, forKey: .price)
        description = try container.decodeIfPresent(String.self, forKey: .description)
        category = try container.decodeIfPresent(String.self, forKey: .category)
        active = try container.decodeIfPresent(Bool.self, forKey: .active) ?? true
        sort = try container.decodeIfPresent(Int.self, forKey: .sort) ?? 0
        soldOutToday = try container.decodeIfPresent(Bool.self, forKey: .soldOutToday) ?? false
        durationMinutes = try container.decodeIfPresent(Int.self, forKey: .durationMinutes)
        bufferBeforeMinutes = try container.decodeIfPresent(Int.self, forKey: .bufferBeforeMinutes)
        bufferAfterMinutes = try container.decodeIfPresent(Int.self, forKey: .bufferAfterMinutes)
        agendaIds = try container.decodeIfPresent([String].self, forKey: .agendaIds)
        photo = try container.decodeIfPresent(URL.self, forKey: .photo)
    }

    /// The item as a full replacement body, for a change of one field (sold out today).
    public var input: BipeCatalogItemInput {
        BipeCatalogItemInput(kind: kind, name: name, price: price, description: description, category: category, active: active,
                             sort: sort, soldOutToday: soldOutToday, durationMinutes: durationMinutes,
                             bufferBeforeMinutes: bufferBeforeMinutes, bufferAfterMinutes: bufferAfterMinutes, agendaIds: agendaIds)
    }
}

/// Contract: `CatalogItemInput`. Products never carry scheduling fields; nil fields are left out.
public struct BipeCatalogItemInput: Encodable, Sendable, Equatable {
    public var kind: BipeCatalogKind
    public var name: String
    public var price: BipeCatalogPrice
    public var description: String?
    public var category: String?
    public var active: Bool
    public var sort: Int
    public var soldOutToday: Bool
    public var durationMinutes: Int?
    public var bufferBeforeMinutes: Int?
    public var bufferAfterMinutes: Int?
    public var agendaIds: [String]?

    public init(kind: BipeCatalogKind, name: String, price: BipeCatalogPrice, description: String?, category: String?, active: Bool,
                sort: Int, soldOutToday: Bool = false, durationMinutes: Int? = nil, bufferBeforeMinutes: Int? = nil,
                bufferAfterMinutes: Int? = nil, agendaIds: [String]? = nil) {
        self.kind = kind
        self.name = name
        self.price = price
        self.description = description.flatMap { $0.isEmpty ? nil : $0 }
        self.category = category.flatMap { $0.isEmpty ? nil : $0 }
        self.active = active
        self.sort = sort
        self.soldOutToday = soldOutToday
        let service = kind == .service
        self.durationMinutes = service ? durationMinutes : nil
        self.bufferBeforeMinutes = service ? bufferBeforeMinutes : nil
        self.bufferAfterMinutes = service ? bufferAfterMinutes : nil
        self.agendaIds = service ? agendaIds.flatMap { $0.isEmpty ? nil : $0 } : nil
    }
}

// MARK: - Agenda models

/// Contract: `Agenda`. A person or a station; it needs no Bipe account (ADR 0099).
public struct BipeAgenda: Decodable, Sendable, Identifiable, Equatable {
    /// The agenda ID that asks the server to pick any free agenda.
    public static let any = "any"

    public let id: String
    public let name: String
    public let photo: String?
    public let active: Bool
    public let sort: Int
}

/// Contract: `AgendaInput`.
public struct BipeAgendaInput: Encodable, Sendable {
    public var name: String
    public var active: Bool
    public var sort: Int

    public init(name: String, active: Bool = true, sort: Int) {
        self.name = name
        self.active = active
        self.sort = sort
    }
}

/// Contract: `Availability`. Local wall times `HH:MM` in the business time zone; end is exclusive.
public struct BipeAvailabilityRule: Codable, Sendable, Equatable, Hashable {
    /// ISO weekday: Monday 1 to Sunday 7.
    public var weekday: Int
    public var start: String
    public var end: String

    public init(weekday: Int, start: String, end: String) {
        self.weekday = weekday
        self.start = start
        self.end = end
    }
}

/// Contract: `Policy` (defaults in ADR 0103).
public struct BipeAgendaPolicy: Codable, Sendable, Equatable {
    public enum ConfirmMode: String, Codable, Sendable {
        case auto
        case request
    }

    public var minNoticeMinutes: Int
    public var horizonDays: Int
    public var slotStepMinutes: Int
    public var cancelWindowMinutes: Int
    public var confirmMode: ConfirmMode
    public var maxOpenBookingsPerCustomer: Int
    /// Kept as read: reminders wait for bipe-chat/bipe#320.
    public var reminderMinutes: [Int]

    /// The ADR 0103 defaults, for a screen that has not read the business's policy.
    public static let defaults = BipeAgendaPolicy(minNoticeMinutes: 60, horizonDays: 60, slotStepMinutes: 15, cancelWindowMinutes: 120,
                                                  confirmMode: .auto, maxOpenBookingsPerCustomer: 2, reminderMinutes: [1440, 120])

    public init(minNoticeMinutes: Int, horizonDays: Int, slotStepMinutes: Int, cancelWindowMinutes: Int, confirmMode: ConfirmMode,
                maxOpenBookingsPerCustomer: Int, reminderMinutes: [Int]) {
        self.minNoticeMinutes = minNoticeMinutes
        self.horizonDays = horizonDays
        self.slotStepMinutes = slotStepMinutes
        self.cancelWindowMinutes = cancelWindowMinutes
        self.confirmMode = confirmMode
        self.maxOpenBookingsPerCustomer = maxOpenBookingsPerCustomer
        self.reminderMinutes = reminderMinutes
    }
}

/// Contract: `AgendaState`. The owner's whole Agenda.
public struct BipeAgendaState: Decodable, Sendable, Equatable {
    public let published: Bool
    /// IANA time zone of the business. Its local dates are the days of the Agenda.
    public let timeZone: String
    public let agendas: [BipeAgenda]
    public let services: [BipeCatalogItem]
    public let policy: BipeAgendaPolicy
}

/// Contract: `BookingStatus`.
public enum BipeBookingStatus: String, Sendable, CaseIterable {
    case held
    case requested
    case confirmed
    case done
    case cancelled
    case noShow = "no_show"

    /// held, requested and confirmed: the booking still holds its time.
    public var isOpen: Bool {
        self == .held || self == .requested || self == .confirmed
    }
}

/// Contract: `BookingCustomer`: a signed-in customer, or a typed name (an encaixe; ADR 0100).
public struct BipeBookingCustomer: Codable, Sendable, Equatable {
    public let userId: String?
    public let name: String?

    public static func user(_ userId: String) -> BipeBookingCustomer { BipeBookingCustomer(userId: userId, name: nil) }
    public static func named(_ name: String) -> BipeBookingCustomer { BipeBookingCustomer(userId: nil, name: name) }

    private init(userId: String?, name: String?) {
        self.userId = userId
        self.name = name
    }

    private enum CodingKeys: String, CodingKey { case userId, name }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decodeIfPresent(String.self, forKey: .userId)
        name = try container.decodeIfPresent(String.self, forKey: .name)
    }

    public func encode(to encoder: Encoder) throws {
        // One of the two, never both (the contract is strict).
        var container = encoder.container(keyedBy: CodingKeys.self)
        if let userId {
            try container.encode(userId, forKey: .userId)
        } else {
            try container.encode(name ?? "", forKey: .name)
        }
    }
}

/// Contract: `Booking`. The minimal record of ADR 0102: never notes or other customer text.
public struct BipeBooking: Decodable, Sendable, Identifiable, Equatable {
    public enum Source: String, Sendable {
        case customer
        case owner
    }

    public let id: String
    /// Matrix ID of the company account.
    public let businessId: String
    public let serviceId: String
    public let agendaId: String
    public let start: Date
    public let end: Date
    public let status: BipeBookingStatus
    public let customer: BipeBookingCustomer
    public let source: Source
    /// Only while held: when the 5-minute hold ends.
    public let holdExpiresAt: Date?

    private enum CodingKeys: String, CodingKey {
        case id, businessId, serviceId, agendaId, start, end, status, customer, source, holdExpiresAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        businessId = try container.decode(String.self, forKey: .businessId)
        serviceId = try container.decode(String.self, forKey: .serviceId)
        agendaId = try container.decode(String.self, forKey: .agendaId)
        start = try container.decode(Date.self, forKey: .start)
        end = try container.decode(Date.self, forKey: .end)
        // An unknown status from a newer server must not fail the list: it shows with no action.
        status = try BipeBookingStatus(rawValue: container.decode(String.self, forKey: .status)) ?? .done
        customer = try container.decode(BipeBookingCustomer.self, forKey: .customer)
        source = try Source(rawValue: container.decode(String.self, forKey: .source)) ?? .customer
        holdExpiresAt = try container.decodeIfPresent(Date.self, forKey: .holdExpiresAt)
    }

    /// Held, requested and confirmed bookings before their end can still be cancelled.
    public func canCancel(now: Date = Date()) -> Bool {
        status.isOpen && end > now
    }
}

/// The owner's actions on a booking.
public enum BipeOwnerBookingAction: String, Sendable {
    case cancel
    case noShow = "no-show"
    case accept
    case refuse
}

/// Contract: `Slot`. A free start, with no booking or customer information.
public struct BipeSlot: Decodable, Sendable, Hashable {
    public let start: Date
    public let end: Date
}

/// Contract: `SlotDay`. `date` is a local date of the business, `yyyy-MM-dd`; a full day has an empty list.
public struct BipeSlotDay: Decodable, Sendable, Hashable {
    public let date: String
    public let slots: [BipeSlot]
}

/// Contract: `SlotDays`.
public struct BipeSlotDays: Decodable, Sendable {
    public let timeZone: String
    public let days: [BipeSlotDay]
}

// MARK: - Errors

extension BipeAPIError {
    /// `GET /v1/me/business/agenda` before activation: `not_found` with reason `agenda_off`.
    public var isAgendaOff: Bool {
        if case .server(.notFound, let reason, _) = self { return reason == "agenda_off" }
        return false
    }

    /// `bad_request` with reason `incomplete`: publishing needs an active service and a person with hours.
    public var isPublishIncomplete: Bool {
        if case .server(.badRequest, let reason, _) = self { return reason == "incomplete" }
        return false
    }

    /// The text of a failed Agenda call (agenda-book, agenda-booking-card, agenda-owner-booking).
    /// Other errors show bipe_agenda_error_save.
    public var agendaMessage: String {
        guard case .server(let code, _, _) = self else {
            return self == .network ? BipeStrings.errorNetwork : BipeStrings.agendaErrorSave
        }
        return switch code {
        case .slotTaken: BipeStrings.agendaErrorSlotTaken
        case .holdExpired: BipeStrings.agendaErrorHoldExpired
        case .tooManyOpenBookings: BipeStrings.agendaErrorTooManyOpen
        case .outsideNotice: BipeStrings.agendaErrorOutsideNotice
        case .outsideHorizon: BipeStrings.agendaErrorOutsideHorizon
        case .agendaNotPublished: BipeStrings.agendaErrorNotPublished
        case .cancellationWindowClosed: BipeStrings.agendaErrorCancelWindow
        default: BipeStrings.agendaErrorSave
        }
    }
}

// MARK: - Money and calendar

/// BRL amounts for the catalogue's display text. No payment code (ADR 0012).
public enum BipeMoney {
    /// `R$ 40,00`: pt-BR digits with a dot every three, a comma and two decimals, after `R$` and one space.
    public static func text(cents: Int) -> String {
        let negative = cents < 0
        let value = abs(cents)
        let reais = String(value / 100)
        var grouped = ""
        for (index, character) in reais.reversed().enumerated() {
            if index > 0 && index % 3 == 0 { grouped.append(".") }
            grouped.append(character)
        }
        let centavos = value % 100
        return (negative ? "-" : "") + "R$ " + String(grouped.reversed()) + "," + (centavos < 10 ? "0" : "") + String(centavos)
    }

    /// Centavos from a typed amount in reais: `40`, `40,5`, `1.234,56` or `40.50`. nil when it is not a number.
    public static func cents(from typed: String) -> Int? {
        var text = typed.replacingOccurrences(of: "R$", with: "").filter { !$0.isWhitespace }
        guard !text.isEmpty else { return nil }
        if text.contains(",") {
            text = text.replacingOccurrences(of: ".", with: "").replacingOccurrences(of: ",", with: ".")
        }
        guard let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")), value >= 0 else { return nil }
        var cents = value * 100
        var rounded = Decimal()
        NSDecimalRound(&rounded, &cents, 0, .plain)
        return NSDecimalNumber(decimal: rounded).intValue
    }
}

/// A one-event iCalendar file for "Adicionar ao calendário". Nothing goes to the server.
public enum BipeBookingCalendar {
    public static func ics(uid: String, start: Date, end: Date, title: String, stamp: Date = Date()) -> String {
        [
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Bipe//Agenda//PT",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "BEGIN:VEVENT",
            "UID:\(escape(uid))@bipe.chat",
            "DTSTAMP:\(utc(stamp))",
            "DTSTART:\(utc(start))",
            "DTEND:\(utc(end))",
            "SUMMARY:\(escape(title))",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
        ].joined(separator: "\r\n")
    }

    /// `20261025T130000Z`.
    static func utc(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        func two(_ value: Int?) -> String { String(format: "%02ld", value ?? 0) }
        return String(format: "%04ld", parts.year ?? 0) + two(parts.month) + two(parts.day) + "T" + two(parts.hour) + two(parts.minute) + two(parts.second) + "Z"
    }

    /// RFC 5545 text escaping.
    static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: ";", with: "\\;")
            .replacingOccurrences(of: ",", with: "\\,")
            .replacingOccurrences(of: "\r\n", with: "\\n")
            .replacingOccurrences(of: "\n", with: "\\n")
    }
}
