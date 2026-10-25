// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Business tools: Fila virtual (ADR 0107), Orçamento (ADR 0106) and Pedidos (ADR 0105). Contract: bipe-shared
// api/openapi.yaml (tags Fila virtual, Orçamento, Pedidos) and api/business-schemas.json (Queue*, Quote*, Order*).
// Bipe moves no money (ADR 0012): the apps send payment in_person only; Pix charge cards (ADR 0080) are not built.

extension BipeBusinessAPI {
    // MARK: - Fila virtual, owner

    /// `GET /v1/me/business/queue`. Today's queue, made closed on first use.
    public func ownerQueue() async throws(BipeAPIError) -> BipeQueue {
        try await request("GET", ["v1", "me", "business", "queue"])
    }

    /// `POST /v1/me/business/queue/open` or `.../close`. Places already in the queue stay when it closes.
    public func setQueueOpen(_ open: Bool) async throws(BipeAPIError) -> BipeQueue {
        try await request("POST", ["v1", "me", "business", "queue", open ? "open" : "close"])
    }

    /// `PUT /v1/me/business/queue/policy`. `noShowAfterMinutes` 1 to 240.
    public func setQueuePolicy(noShowAfterMinutes: Int) async throws(BipeAPIError) -> BipeQueue {
        try await request("PUT", ["v1", "me", "business", "queue", "policy"], json: BipeQueuePolicy(noShowAfterMinutes: noShowAfterMinutes))
    }

    /// `GET /v1/me/business/queue/places`. Today's places, in order of arrival.
    public func ownerQueuePlaces() async throws(BipeAPIError) -> [BipeQueuePlace] {
        let places: QueuePlaces = try await request("GET", ["v1", "me", "business", "queue", "places"])
        return places.places
    }

    /// `POST /v1/me/business/queue/places`. A walk-in: a typed name or a signed-in customer.
    public func addQueuePlace(customer: BipeBookingCustomer, serviceId: String? = nil) async throws(BipeAPIError) -> BipeQueuePlace {
        try await request("POST", ["v1", "me", "business", "queue", "places"], json: OwnerQueuePlace(customer: customer, serviceId: serviceId))
    }

    /// `POST /v1/me/business/queue/call-next`. `not_found` with reason `queue_empty` when nobody waits.
    public func callNext() async throws(BipeAPIError) -> BipeQueuePlace {
        try await request("POST", ["v1", "me", "business", "queue", "call-next"])
    }

    /// `POST /v1/me/business/queue/places/{id}/{served|no-show|recall}`. `place_not_active` when the status does not allow it.
    public func queuePlaceAction(placeId: String, _ action: BipeQueuePlaceAction) async throws(BipeAPIError) -> BipeQueuePlace {
        try await request("POST", ["v1", "me", "business", "queue", "places", placeId, action.rawValue])
    }

    // MARK: - Fila virtual, customer

    /// `GET /v1/businesses/{business}/queue`. No auth.
    public func queue(business: String) async throws(BipeAPIError) -> BipeQueue {
        try await request("GET", ["v1", "businesses", business, "queue"], auth: false)
    }

    /// `GET /v1/businesses/{business}/queue/me`. nil when the caller has no active place (`not_found`).
    public func myQueuePlace(business: String) async throws(BipeAPIError) -> BipeQueuePosition? {
        do throws(BipeAPIError) {
            let position: BipeQueuePosition = try await request("GET", ["v1", "businesses", business, "queue", "me"])
            return position
        } catch {
            if case .server(.notFound, _, _) = error { return nil }
            throw error
        }
    }

    /// `POST /v1/businesses/{business}/queue/join`. `queue_closed`, `already_in_queue`.
    public func joinQueue(business: String, serviceId: String? = nil) async throws(BipeAPIError) -> BipeQueuePosition {
        try await request("POST", ["v1", "businesses", business, "queue", "join"], json: QueueJoin(serviceId: serviceId))
    }

    /// `POST /v1/businesses/{business}/queue/places/{id}/leave`. `place_not_active` when it already ended.
    public func leaveQueue(business: String, placeId: String) async throws(BipeAPIError) -> BipeQueuePosition {
        try await request("POST", ["v1", "businesses", business, "queue", "places", placeId, "leave"])
    }

    /// `GET /v1/me/queue-places`. A person's active places, or a company's.
    public func myQueuePlaces() async throws(BipeAPIError) -> [BipeQueuePosition] {
        let places: QueuePositions = try await request("GET", ["v1", "me", "queue-places"])
        return places.places
    }

    // MARK: - Orçamento, owner

    /// `GET /v1/me/business/quotes/requests`. Requests with no quote, newest first.
    public func quoteRequests() async throws(BipeAPIError) -> [BipeQuoteRequest] {
        let requests: QuoteRequests = try await request("GET", ["v1", "me", "business", "quotes", "requests"])
        return requests.requests
    }

    /// `POST /v1/me/business/quotes/requests/{id}/quote`. The first version.
    public func answerQuoteRequest(requestId: String, _ version: BipeQuoteVersionInput) async throws(BipeAPIError) -> BipeQuote {
        try await request("POST", ["v1", "me", "business", "quotes", "requests", requestId, "quote"], json: version)
    }

    /// `GET /v1/me/business/quotes`.
    public func ownerQuotes() async throws(BipeAPIError) -> [BipeQuote] {
        let quotes: Quotes = try await request("GET", ["v1", "me", "business", "quotes"])
        return quotes.quotes
    }

    /// `POST /v1/me/business/quotes/{id}/versions`. `quote_not_open` after an answer.
    public func reviseQuote(quoteId: String, _ version: BipeQuoteVersionInput) async throws(BipeAPIError) -> BipeQuote {
        try await request("POST", ["v1", "me", "business", "quotes", quoteId, "versions"], json: version)
    }

    // MARK: - Orçamento, customer

    /// `POST /v1/businesses/{business}/quotes/requests`. A second tap returns the open request.
    public func requestQuote(business: String) async throws(BipeAPIError) -> BipeQuoteRequest {
        try await request("POST", ["v1", "businesses", business, "quotes", "requests"])
    }

    /// `POST /v1/businesses/{business}/quotes/{id}/accept` with target order, paid in person.
    public func acceptQuoteAsOrder(business: String, quoteId: String, version: Int, fulfilment: BipeFulfilment) async throws(BipeAPIError) -> BipeQuote {
        try await request("POST", ["v1", "businesses", business, "quotes", quoteId, "accept"],
                          json: QuoteAcceptOrder(version: version, fulfilment: fulfilment))
    }

    /// `POST /v1/businesses/{business}/quotes/{id}/accept` with target booking. `agendaId` nil is any agenda.
    public func acceptQuoteAsBooking(business: String, quoteId: String, version: Int, serviceId: String, agendaId: String?,
                                     start: Date) async throws(BipeAPIError) -> BipeQuote {
        try await request("POST", ["v1", "businesses", business, "quotes", quoteId, "accept"],
                          json: QuoteAcceptBooking(version: version, booking: QuoteBooking(serviceId: serviceId, agendaId: agendaId ?? BipeAgenda.any,
                                                                                           start: BipeInstant.string(start))))
    }

    /// `POST /v1/businesses/{business}/quotes/{id}/refuse` with the version on screen.
    public func refuseQuote(business: String, quoteId: String, version: Int) async throws(BipeAPIError) -> BipeQuote {
        try await request("POST", ["v1", "businesses", business, "quotes", quoteId, "refuse"], json: QuoteDecline(version: version))
    }

    /// `GET /v1/me/quotes`. Open quotes and those changed in the last 30 days: a person's, or a company's.
    public func myQuotes() async throws(BipeAPIError) -> [BipeQuote] {
        let quotes: Quotes = try await request("GET", ["v1", "me", "quotes"])
        return quotes.quotes
    }

    // MARK: - Pedidos, owner

    /// `GET /v1/me/business/orders`. Newest first.
    public func ownerOrders() async throws(BipeAPIError) -> [BipeOrder] {
        let orders: Orders = try await request("GET", ["v1", "me", "business", "orders"])
        return orders.orders
    }

    /// `POST /v1/me/business/orders`. An order the owner types in, accepted at once; paid in person.
    public func ownerCreateOrder(lines: [BipeOrderLineInput], fulfilment: BipeFulfilment, customer: BipeBookingCustomer) async throws(BipeAPIError) -> BipeOrder {
        try await request("POST", ["v1", "me", "business", "orders"],
                          json: OwnerOrderInput(lines: lines, fulfilment: fulfilment.rawValue, payment: BipeOrder.payInPerson, customer: customer))
    }

    /// `GET /v1/me/business/orders/policy`. Default: every day until 23:59.
    public func orderPolicy() async throws(BipeAPIError) -> BipeOrderPolicy {
        try await request("GET", ["v1", "me", "business", "orders", "policy"])
    }

    /// `PUT /v1/me/business/orders/policy`.
    public func setOrderPolicy(_ policy: BipeOrderPolicy) async throws(BipeAPIError) -> BipeOrderPolicy {
        try await request("PUT", ["v1", "me", "business", "orders", "policy"], json: policy)
    }

    /// `POST /v1/me/business/orders/{id}/{accept|ready|done|cancel}`. `order_not_open` when the status does not allow it.
    public func ownerOrderAction(orderId: String, _ action: BipeOwnerOrderAction) async throws(BipeAPIError) -> BipeOrder {
        try await request("POST", ["v1", "me", "business", "orders", orderId, action.rawValue])
    }

    /// `POST /v1/me/business/orders/{id}/refuse` with a fixed reason. Free text goes in the chat.
    public func refuseOrder(orderId: String, reason: BipeOrderRefuseReason) async throws(BipeAPIError) -> BipeOrder {
        try await request("POST", ["v1", "me", "business", "orders", orderId, "refuse"], json: OrderRefusal(reason: reason.rawValue))
    }

    // MARK: - Pedidos, customer

    /// `POST /v1/businesses/{business}/orders`, paid in person. `order_already_open`, `orders_closed`,
    /// `item_unavailable`, `price_not_fixed`.
    public func placeOrder(business: String, lines: [BipeOrderLineInput], fulfilment: BipeFulfilment) async throws(BipeAPIError) -> BipeOrder {
        try await request("POST", ["v1", "businesses", business, "orders"],
                          json: OrderInput(lines: lines, fulfilment: fulfilment.rawValue, payment: BipeOrder.payInPerson))
    }

    /// `POST /v1/businesses/{business}/orders/{id}/cancel`.
    public func cancelOrder(business: String, orderId: String) async throws(BipeAPIError) -> BipeOrder {
        try await request("POST", ["v1", "businesses", business, "orders", orderId, "cancel"])
    }

    /// `GET /v1/me/orders`. Open orders and the last 30 days, newest first: a person's, or a company's.
    public func myOrders() async throws(BipeAPIError) -> [BipeOrder] {
        let orders: Orders = try await request("GET", ["v1", "me", "orders"])
        return orders.orders
    }
}

// Request and list bodies. Keys are camelCase here; the encoder and decoder convert them to snake_case.
private struct QueuePlaces: Decodable { let places: [BipeQueuePlace] }
private struct QueuePositions: Decodable { let places: [BipeQueuePosition] }
private struct QueueJoin: Encodable { let serviceId: String? }
private struct OwnerQueuePlace: Encodable {
    let customer: BipeBookingCustomer
    let serviceId: String?
}
private struct QuoteRequests: Decodable { let requests: [BipeQuoteRequest] }
private struct Quotes: Decodable { let quotes: [BipeQuote] }
private struct QuoteDecline: Encodable { let version: Int }
private struct QuoteBooking: Encodable {
    let serviceId: String
    let agendaId: String
    let start: String
}
private struct QuoteAcceptOrder: Encodable {
    let version: Int
    let target: String
    let fulfilment: String
    let payment: String

    init(version: Int, fulfilment: BipeFulfilment) {
        self.version = version
        target = "order"
        self.fulfilment = fulfilment.rawValue
        payment = BipeOrder.payInPerson
    }
}
private struct QuoteAcceptBooking: Encodable {
    let version: Int
    let target: String
    let booking: QuoteBooking

    init(version: Int, booking: QuoteBooking) {
        self.version = version
        target = "booking"
        self.booking = booking
    }
}
private struct Orders: Decodable { let orders: [BipeOrder] }
private struct OrderInput: Encodable {
    let lines: [BipeOrderLineInput]
    let fulfilment: String
    let payment: String
}
private struct OwnerOrderInput: Encodable {
    let lines: [BipeOrderLineInput]
    let fulfilment: String
    let payment: String
    let customer: BipeBookingCustomer
}
private struct OrderRefusal: Encodable { let reason: String }

// MARK: - Fila virtual models

/// Contract: `QueuePolicy`.
public struct BipeQueuePolicy: Codable, Sendable, Equatable {
    /// The ADR 0107 default.
    public static let defaultNoShowAfterMinutes = 10

    public var noShowAfterMinutes: Int

    public init(noShowAfterMinutes: Int) {
        self.noShowAfterMinutes = noShowAfterMinutes
    }
}

/// Contract: `Queue`. One queue per business per local day; it resets every day.
public struct BipeQueue: Decodable, Sendable, Equatable {
    public let id: String
    public let businessId: String
    /// The business's local date, `yyyy-MM-dd`.
    public let date: String
    public let timeZone: String
    public let open: Bool
    public let policy: BipeQueuePolicy
}

/// Contract: `QueuePlaceStatus`.
public enum BipeQueuePlaceStatus: String, Sendable, CaseIterable {
    case waiting
    case called
    case served
    case noShow = "no_show"
    case left

    /// waiting and called: the place still holds its turn.
    public var isActive: Bool { self == .waiting || self == .called }
}

/// Contract: `QueuePlace`. No location, notes or customer text (ADR 0107).
public struct BipeQueuePlace: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let queueId: String
    public let businessId: String
    public let customer: BipeBookingCustomer
    public let source: BipeBooking.Source
    public let serviceId: String?
    public let status: BipeQueuePlaceStatus
    public let joinedAt: Date
    public let calledAt: Date?
    public let servedAt: Date?

    private enum CodingKeys: String, CodingKey {
        case id, queueId, businessId, customer, source, serviceId, status, joinedAt, calledAt, servedAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        queueId = try container.decode(String.self, forKey: .queueId)
        businessId = try container.decode(String.self, forKey: .businessId)
        customer = try container.decode(BipeBookingCustomer.self, forKey: .customer)
        source = try BipeBooking.Source(rawValue: container.decode(String.self, forKey: .source)) ?? .customer
        serviceId = try container.decodeIfPresent(String.self, forKey: .serviceId)
        // An unknown status from a newer server must not fail the list: it shows as ended, with no action.
        status = try BipeQueuePlaceStatus(rawValue: container.decode(String.self, forKey: .status)) ?? .left
        joinedAt = try container.decode(Date.self, forKey: .joinedAt)
        calledAt = try container.decodeIfPresent(Date.self, forKey: .calledAt)
        servedAt = try container.decodeIfPresent(Date.self, forKey: .servedAt)
    }
}

/// Contract: `QueueWaitRange`. An estimate in minutes, never an appointment time.
public struct BipeQueueWait: Decodable, Sendable, Equatable {
    public let minMinutes: Int
    public let maxMinutes: Int
}

/// Contract: `QueuePosition`. Only the caller's place: 0 when called, from 1 while waiting. No wait without data.
public struct BipeQueuePosition: Decodable, Sendable, Identifiable, Equatable {
    public let place: BipeQueuePlace
    public let position: Int
    public let wait: BipeQueueWait?

    public var id: String { place.id }
}

/// The owner's actions on a place.
public enum BipeQueuePlaceAction: String, Sendable {
    case served
    case noShow = "no-show"
    case recall
}

// MARK: - Orçamento and Pedidos models

/// Contract: `OrderLine` and `QuoteLine`. An immutable snapshot of business text; the server adds the totals.
public struct BipeOrderLine: Decodable, Sendable, Equatable, Hashable {
    public let itemId: String?
    public let name: String
    /// A quote line can hold a fraction (2.5 m²); an order line from the cart is a whole number.
    public let quantity: Double
    public let unitAmountCents: Int
    public let totalAmountCents: Int
}

public typealias BipeQuoteLine = BipeOrderLine

/// Contract: `QuoteRequest`. Only the fact of a request: the description and photos stay in the chat.
public struct BipeQuoteRequest: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let businessId: String
    public let customerId: String
    public let createdAt: Date
}

/// Contract: `QuoteVersion`. Immutable; older versions stay readable.
public struct BipeQuoteVersion: Decodable, Sendable, Identifiable, Equatable {
    public let version: Int
    public let createdAt: Date
    public let validUntil: Date
    public let lines: [BipeQuoteLine]
    public let totalAmountCents: Int
    public let visitServiceId: String?

    public var id: Int { version }
}

/// Contract: `Quote` status. `expired` comes from the server once the current version's validity passed.
public enum BipeQuoteStatus: String, Sendable, CaseIterable {
    case offered
    case accepted
    case refused
    case expired
}

/// Contract: `Quote`. Never says paid (ADR 0106).
public struct BipeQuote: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let requestId: String
    public let businessId: String
    public let customerId: String
    public let status: BipeQuoteStatus
    public let currentVersion: Int
    public let versions: [BipeQuoteVersion]
    public let bookingId: String?
    public let orderId: String?

    private enum CodingKeys: String, CodingKey {
        case id, requestId, businessId, customerId, status, currentVersion, versions, bookingId, orderId
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        requestId = try container.decode(String.self, forKey: .requestId)
        businessId = try container.decode(String.self, forKey: .businessId)
        customerId = try container.decode(String.self, forKey: .customerId)
        // An unknown status from a newer server shows with no action.
        status = try BipeQuoteStatus(rawValue: container.decode(String.self, forKey: .status)) ?? .refused
        currentVersion = try container.decode(Int.self, forKey: .currentVersion)
        versions = try container.decode([BipeQuoteVersion].self, forKey: .versions)
        bookingId = try container.decodeIfPresent(String.self, forKey: .bookingId)
        orderId = try container.decodeIfPresent(String.self, forKey: .orderId)
    }

    /// The version the customer decides on.
    public var current: BipeQuoteVersion? {
        versions.first { $0.version == currentVersion } ?? versions.last
    }

    /// The versions before the current one, newest first.
    public var previous: [BipeQuoteVersion] {
        versions.filter { $0.version != currentVersion }.sorted { $0.version > $1.version }
    }
}

/// Contract: `QuoteLineInput`: a catalogue item or a typed name, with the quoted unit price.
public struct BipeQuoteLineInput: Encodable, Sendable, Equatable {
    public let itemId: String?
    public let name: String?
    public let quantity: Double
    public let unitAmountCents: Int

    public static func item(_ itemId: String, quantity: Double, unitAmountCents: Int) -> BipeQuoteLineInput {
        BipeQuoteLineInput(itemId: itemId, name: nil, quantity: quantity, unitAmountCents: unitAmountCents)
    }

    public static func named(_ name: String, quantity: Double, unitAmountCents: Int) -> BipeQuoteLineInput {
        BipeQuoteLineInput(itemId: nil, name: name, quantity: quantity, unitAmountCents: unitAmountCents)
    }

    private init(itemId: String?, name: String?, quantity: Double, unitAmountCents: Int) {
        self.itemId = itemId
        self.name = name
        self.quantity = quantity
        self.unitAmountCents = unitAmountCents
    }

    private enum CodingKeys: String, CodingKey { case itemId, name, quantity, unitAmountCents }

    public func encode(to encoder: Encoder) throws {
        // One of item_id and name, never both (the contract is strict).
        var container = encoder.container(keyedBy: CodingKeys.self)
        if let itemId {
            try container.encode(itemId, forKey: .itemId)
        } else {
            try container.encode(name ?? "", forKey: .name)
        }
        try container.encode(quantity, forKey: .quantity)
        try container.encode(unitAmountCents, forKey: .unitAmountCents)
    }
}

/// Contract: `QuoteVersionInput`. `validUntil` is UTC with a trailing Z: see ``endOfLocalDay(_:calendar:)``.
public struct BipeQuoteVersionInput: Encodable, Sendable, Equatable {
    public let lines: [BipeQuoteLineInput]
    public let validUntil: String
    public let visitServiceId: String?

    public init(lines: [BipeQuoteLineInput], validUntil: Date, visitServiceId: String?) {
        self.lines = lines
        self.validUntil = BipeInstant.string(validUntil)
        self.visitServiceId = visitServiceId.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// The last second of the local date of `date` (spec quote-builder validUntil: a local date, sent as the end
    /// of that day).
    public static func endOfLocalDay(_ date: Date, calendar: Calendar = .current) -> Date {
        let start = calendar.startOfDay(for: date)
        let next = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
        return next.addingTimeInterval(-1)
    }
}

/// Contract: `fulfilment`.
public enum BipeFulfilment: String, Sendable, CaseIterable, Hashable {
    case pickup
    case delivery
}

/// Contract: `OrderStatus`.
public enum BipeOrderStatus: String, Sendable, CaseIterable {
    case sent
    case accepted
    case refused
    case ready
    case done
    case cancelled

    /// sent, accepted and ready: the order is open.
    public var isOpen: Bool { self == .sent || self == .accepted || self == .ready }
}

/// Contract: `OrderRefuseReason`. Fixed reasons only: free text goes in the encrypted chat.
public enum BipeOrderRefuseReason: String, Sendable, CaseIterable {
    case unavailable
    case closed
    case cannotDeliver = "cannot_deliver"
    case cannotFulfil = "cannot_fulfil"
}

/// Contract: `Order`. The minimal record of ADR 0105: no address, notes or messages. Never says paid.
public struct BipeOrder: Decodable, Sendable, Identifiable, Equatable {
    /// The only payment the apps send (ADR 0012, ADR 0080).
    public static let payInPerson = "in_person"

    public let id: String
    public let businessId: String
    public let customer: BipeBookingCustomer
    public let source: BipeBooking.Source
    public let createdAt: Date
    public let lines: [BipeOrderLine]
    public let totalAmountCents: Int
    public let fulfilment: BipeFulfilment
    public let payment: String
    public let status: BipeOrderStatus
    public let refuseReason: BipeOrderRefuseReason?
    public let quoteId: String?

    private enum CodingKeys: String, CodingKey {
        case id, businessId, customer, source, createdAt, lines, totalAmountCents, fulfilment, payment, status, refuseReason, quoteId
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        businessId = try container.decode(String.self, forKey: .businessId)
        customer = try container.decode(BipeBookingCustomer.self, forKey: .customer)
        source = try BipeBooking.Source(rawValue: container.decode(String.self, forKey: .source)) ?? .customer
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        lines = try container.decode([BipeOrderLine].self, forKey: .lines)
        totalAmountCents = try container.decode(Int.self, forKey: .totalAmountCents)
        fulfilment = try BipeFulfilment(rawValue: container.decode(String.self, forKey: .fulfilment)) ?? .pickup
        payment = try container.decode(String.self, forKey: .payment)
        // An unknown status from a newer server must not fail the list: it shows with no action.
        status = try BipeOrderStatus(rawValue: container.decode(String.self, forKey: .status)) ?? .done
        refuseReason = try container.decodeIfPresent(String.self, forKey: .refuseReason).flatMap(BipeOrderRefuseReason.init(rawValue:))
        quoteId = try container.decodeIfPresent(String.self, forKey: .quoteId)
    }
}

/// Contract: `OrderLineInput`. Item and a whole quantity; the server takes the price from the catálogo.
public struct BipeOrderLineInput: Encodable, Sendable, Equatable {
    public let itemId: String
    public let quantity: Int

    public init(itemId: String, quantity: Int) {
        self.itemId = itemId
        self.quantity = quantity
    }
}

/// Contract: `OrderPolicy`. ISO weekdays and a local cut-off `HH:MM`; no weekdays closes orders.
public struct BipeOrderPolicy: Codable, Sendable, Equatable {
    public var timeZone: String
    public var weekdays: [Int]
    public var cutoff: String

    public init(timeZone: String, weekdays: [Int], cutoff: String) {
        self.timeZone = timeZone
        self.weekdays = weekdays
        self.cutoff = cutoff
    }
}

/// The owner's actions on an order, besides refuse.
public enum BipeOwnerOrderAction: String, Sendable {
    case accept
    case ready
    case done
    case cancel
}

extension BipeCatalogItem {
    /// An active product with a fixed price in centavos: Pedidos can order it (ADR 0105). Other prices need a quote.
    public var isOrderable: Bool {
        kind == .product && active && price.mode == .fixed && price.amountCents != nil
    }
}

// MARK: - Errors

extension BipeAPIError {
    /// The text of a failed Fila virtual call (queue-customer, queue-owner).
    public var queueMessage: String {
        guard case .server(let code, _, _) = self else {
            return self == .network ? BipeStrings.errorNetwork : BipeStrings.queueError
        }
        return switch code {
        case .queueClosed: BipeStrings.queueClosed
        case .alreadyInQueue: BipeStrings.queueErrorAlreadyJoined
        case .placeNotActive: BipeStrings.queueErrorInactive
        default: BipeStrings.queueError
        }
    }

    /// The text of a failed Orçamento call (quote-card, quote-builder). Accept errors of the booking or the order
    /// show their own texts.
    public var quoteMessage: String {
        guard case .server(let code, _, _) = self else {
            return self == .network ? BipeStrings.errorNetwork : BipeStrings.quoteError
        }
        return switch code {
        case .quoteExpired: BipeStrings.quoteErrorExpired
        case .quoteVersionChanged: BipeStrings.quoteErrorVersion
        case .slotTaken, .holdExpired, .tooManyOpenBookings, .outsideNotice, .outsideHorizon, .agendaNotPublished,
             .cancellationWindowClosed: agendaMessage
        case .orderAlreadyOpen, .ordersClosed, .itemUnavailable, .priceNotFixed, .paymentRequiresVerifiedBusiness,
             .pixChargeUnavailable: orderMessage
        default: BipeStrings.quoteError
        }
    }

    /// The text of a failed Pedidos call (order-customer, order-owner).
    public var orderMessage: String {
        guard case .server(let code, _, _) = self else {
            return self == .network ? BipeStrings.errorNetwork : BipeStrings.orderError
        }
        return switch code {
        case .orderAlreadyOpen: BipeStrings.orderErrorAlreadyOpen
        case .ordersClosed: BipeStrings.orderErrorClosed
        case .itemUnavailable: BipeStrings.orderErrorItem
        case .priceNotFixed: BipeStrings.orderErrorPrice
        case .paymentRequiresVerifiedBusiness, .pixChargeUnavailable: BipeStrings.orderErrorPix
        default: BipeStrings.orderError
        }
    }
}
