// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// What the business tools open from a chat or a profile.
enum BusinessRoute: Identifiable {
    /// agenda-book, with the service picked when the catálogo opened it.
    case book(serviceId: String?)
    /// agenda-book with moving set (Remarcar).
    case reschedule(BipeBooking)
    case catalog
    /// agenda-owner-booking, on the company side of the DM.
    case ownerBooking(BipeBooking)
    /// order-customer, with one of the item when catalog-view opened it.
    case order(itemId: String?)
    /// quote-card acceptBooking: agenda-book with the quote's visit service.
    case quoteBooking(BipeQuote)
    /// quote-card showVersion: the older versions, read only.
    case quoteVersions(BipeQuote)
    /// queue-customer.
    case queue
    /// order-owner, one order, on the company side of the DM.
    case ownerOrder(BipeOrder)
    /// quote-card revise: quote-builder for a new version, on the company side of the DM.
    case ownerQuote(BipeQuote)
    /// queue-owner, on the company side of the DM.
    case ownerQueue

    var id: String {
        switch self {
        case .book(let serviceId): "book-\(serviceId ?? "")"
        case .reschedule(let booking): "move-\(booking.id)"
        case .catalog: "catalog"
        case .ownerBooking(let booking): "owner-\(booking.id)"
        case .order(let itemId): "order-\(itemId ?? "")"
        case .quoteBooking(let quote): "quote-book-\(quote.id)"
        case .quoteVersions(let quote): "quote-versions-\(quote.id)"
        case .queue: "queue"
        case .ownerOrder(let order): "owner-order-\(order.id)"
        case .ownerQuote(let quote): "owner-quote-\(quote.id)"
        case .ownerQueue: "owner-queue"
        }
    }
}

/// Spec business-chat-bar (bipe-shared product/screens/business-chat-bar.json): the tools a company turned on, for
/// its chat and its profile, and the cards of the open records with it: bookings (agenda-booking-card), the open
/// order (order-customer), offered or recent quotes (quote-card) and the active queue place (queue-customer).
/// Nothing comes from the room: the cards come only from bipe-api (ADR 0102).
@MainActor
final class BusinessChatModel: ObservableObject {
    /// true only when the other person is a company account. The tools are hidden otherwise.
    @Published private(set) var isCompany = false
    @Published private(set) var handle: String?
    @Published private(set) var canBook = false
    @Published private(set) var hasCatalog = false
    /// The catalogue has an active product with a fixed price and amount_cents: Fazer pedido shows.
    @Published private(set) var canOrder = false
    /// Today's queue is open: Entrar na fila shows.
    @Published private(set) var queueOpen = false
    /// Open bookings of this DM: with the business, or (owner mode) of this customer.
    @Published private(set) var bookings: [BipeBooking] = []
    /// Open orders of this DM, and the ones of the last day, so that a refusal or a delivery still shows.
    @Published private(set) var orders: [BipeOrder] = []
    /// Offered and expired quotes of this DM, and the ones answered in the last two days.
    @Published private(set) var quotes: [BipeQuote] = []
    /// The active queue place of this DM: with the business, or (owner mode) of this customer.
    @Published private(set) var queuePlaces: [BipeQueuePosition] = []
    /// true on the company account: the cards show the customer and open the owner screens.
    @Published private(set) var ownerMode = false
    @Published private(set) var serviceNames: [String: String] = [:]
    @Published private(set) var agendaNames: [String: String] = [:]
    @Published var route: BusinessRoute?
    @Published var cancelling: BipeBooking?
    @Published var cancellingOrder: BipeOrder?
    @Published var leavingQueue: BipeQueuePosition?
    /// quote-card acceptOrder: the quote whose pickup or delivery choice shows.
    @Published var acceptingQuote: BipeQuote?
    /// quote-card request: the bipe_quote_request_body confirm shows.
    @Published var requestingQuote = false
    @Published private(set) var working = false
    @Published var sharedFile: SharedFile?
    @Published var notice: String?

    let session: AccountSession
    let api: BipeBusinessAPI
    private let client: BipeAPIClient
    private let media: MediaLoader
    private(set) var businessId: String?
    private(set) var peerName = ""
    private var loadedPeer: String?
    private var withBookings = true

    init(session: AccountSession, media: MediaLoader, api: BipeAPIClient = BipeAPIClient(baseURL: AppConfig.apiBaseURL)) {
        self.session = session
        self.media = media
        client = api
        self.api = session.business(api)
    }

    /// Names and DMs for the owner screens opened from the company side of the DM.
    private(set) lazy var people = PeopleService(session: session, api: client, media: media)

    /// The business segment of the customer routes: the handle, else the Matrix ID.
    var business: String { handle ?? businessId ?? "" }

    /// The owner screens opened from the DM: the customer is the other person; the DM is open already.
    var ownerContext: BusinessOwnerContext {
        BusinessOwnerContext(api: api, people: people,
                             customers: businessId.map { [BusinessCustomer(userId: $0, name: peerName)] } ?? [],
                             openChat: nil)
    }

    /// Action load. Runs when the chat or profile opens and when the app comes to the front. A failure hides the
    /// tool, never the chat.
    /// `withBookings` false on person-profile, which shows the tools only.
    func load(peerUserId: String?, peerName: String, withBookings: Bool = true) async {
        guard let peer = peerUserId, peer != session.userId else {
            reset()
            return
        }
        if loadedPeer != peer {
            reset()
            loadedPeer = peer
        }
        businessId = peer
        self.peerName = peerName
        self.withBookings = withBookings
        let meCompany = await media.isCompany(userId: session.userId)
        let peerCompany = await media.isCompany(userId: peer)
        ownerMode = meCompany
        isCompany = peerCompany
        if peerCompany {
            await loadTools(of: peer)
        } else {
            canBook = false
            hasCatalog = false
            canOrder = false
            queueOpen = false
        }
        if withBookings { await loadRecords(peer: peer) }
    }

    /// Reloads after an action or when the app comes to the front.
    func refresh() async {
        guard let businessId else { return }
        await load(peerUserId: businessId, peerName: peerName, withBookings: withBookings)
    }

    private func reset() {
        loadedPeer = nil
        businessId = nil
        handle = nil
        isCompany = false
        canBook = false
        hasCatalog = false
        canOrder = false
        queueOpen = false
        bookings = []
        orders = []
        quotes = []
        queuePlaces = []
        ownerMode = false
    }

    private func loadTools(of peer: String) async {
        let client = client
        let session = session
        let resolved = try? await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> [String: String] in
            try await client.resolveHandles(userIDs: [peer], openID: token)
        }
        if let found = resolved?[peer] { handle = found }
        let services = (try? await api.services(business: business)) ?? []
        canBook = !services.isEmpty
        for service in services { serviceNames[service.id] = service.name }
        if canBook, let agendas = try? await api.agendas(business: business) {
            for agenda in agendas { agendaNames[agenda.id] = agenda.name }
        }
        let items = (try? await api.catalog(business: business)) ?? []
        hasCatalog = !items.isEmpty
        canOrder = items.contains { $0.isOrderable }
        queueOpen = (try? await api.queue(business: business))?.open ?? false
    }

    /// The cards of this DM. Each list loads on its own: a failure keeps the cards it had.
    private func loadRecords(peer: String) async {
        guard ownerMode || isCompany else {
            bookings = []
            orders = []
            quotes = []
            queuePlaces = []
            return
        }
        // The company's own records, for the customer of this DM (owner mode), or the customer's with this business.
        if let all = try? await api.myBookings() {
            if ownerMode {
                bookings = all.filter { $0.customer.userId == peer }
                if !bookings.isEmpty, let state = try? await api.agendaState() {
                    for service in state.services { serviceNames[service.id] = service.name }
                    for agenda in state.agendas { agendaNames[agenda.id] = agenda.name }
                }
            } else {
                bookings = all.filter { $0.businessId == peer }
            }
        }
        let now = Date()
        if let all = try? await api.myOrders() {
            orders = all.filter { order in
                let mine = ownerMode ? order.customer.userId == peer : order.businessId == peer
                return mine && (order.status.isOpen || order.createdAt > now.addingTimeInterval(-86_400))
            }
        }
        if let all = try? await api.myQuotes() {
            quotes = all.filter { quote in
                let mine = ownerMode ? quote.customerId == peer : quote.businessId == peer
                let open = quote.status == .offered || quote.status == .expired
                return mine && (open || (quote.current?.createdAt ?? .distantPast) > now.addingTimeInterval(-2 * 86_400))
            }
        }
        await loadQueue(peer: peer)
    }

    private func loadQueue(peer: String) async {
        guard let all = try? await api.myQueuePlaces() else { return }
        queuePlaces = all.filter { position in
            let mine = ownerMode ? position.place.customer.userId == peer : position.place.businessId == peer
            return mine && position.place.status.isActive
        }
    }

    /// queue-customer load from the chat card: every 30 seconds while the card shows and the place is active.
    func refreshQueue() async {
        guard let businessId, withBookings, ownerMode || isCompany else { return }
        await loadQueue(peer: businessId)
    }

    /// agenda-booking-card addToCalendar.
    func addToCalendar(_ booking: BipeBooking) {
        sharedFile = BookingCalendarFile.write(booking, serviceName: serviceNames[booking.serviceId] ?? "", businessName: peerName)
        if sharedFile == nil { notice = BipeStrings.agendaErrorSave }
    }

    /// agenda-booking-card cancel, after bipe_agenda_cancel_confirm.
    func cancel(_ booking: BipeBooking) async {
        cancelling = nil
        do {
            _ = try await api.cancel(business: business, bookingId: booking.id)
        } catch {
            notice = (error as? BipeAPIError)?.agendaMessage ?? BipeStrings.agendaErrorSave
        }
        await refresh()
    }

    /// quote-card request: POST /v1/businesses/{business}/quotes/requests, then bipe_quote_requested. The description
    /// and photos go in the chat as messages.
    func requestQuote() async {
        requestingQuote = false
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            _ = try await api.requestQuote(business: business)
            notice = BipeStrings.quoteRequested
        } catch {
            notice = (error as? BipeAPIError)?.quoteMessage ?? BipeStrings.quoteError
        }
    }

    /// quote-card acceptOrder: target order, the chosen pickup or delivery, paid in person. The order card shows after.
    func acceptQuote(_ quote: BipeQuote, fulfilment: BipeFulfilment) async {
        acceptingQuote = nil
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            _ = try await api.acceptQuoteAsOrder(business: business, quoteId: quote.id, version: quote.currentVersion, fulfilment: fulfilment)
            notice = BipeStrings.orderSent
        } catch {
            notice = (error as? BipeAPIError)?.quoteMessage ?? BipeStrings.quoteError
        }
        await refresh()
    }

    /// quote-card acceptBooking: the confirm of agenda-book. Returns the error text, or nil.
    func acceptQuoteBooking(_ quote: BipeQuote, serviceId: String, agendaId: String?, start: Date) async -> String? {
        do {
            _ = try await api.acceptQuoteAsBooking(business: business, quoteId: quote.id, version: quote.currentVersion,
                                                   serviceId: serviceId, agendaId: agendaId, start: start)
            return nil
        } catch {
            return (error as? BipeAPIError)?.quoteMessage ?? BipeStrings.quoteError
        }
    }

    /// quote-card refuse, with the current version.
    func refuseQuote(_ quote: BipeQuote) async {
        guard !working else { return }
        working = true
        defer { working = false }
        do {
            _ = try await api.refuseQuote(business: business, quoteId: quote.id, version: quote.currentVersion)
        } catch {
            notice = (error as? BipeAPIError)?.quoteMessage ?? BipeStrings.quoteError
        }
        await refresh()
    }

    /// order-customer cancel, after bipe_order_cancel_confirm.
    func cancelOrder(_ order: BipeOrder) async {
        cancellingOrder = nil
        do {
            _ = try await api.cancelOrder(business: business, orderId: order.id)
        } catch {
            notice = (error as? BipeAPIError)?.orderMessage ?? BipeStrings.orderError
        }
        await refresh()
    }

    /// queue-customer leave, after bipe_queue_leave_confirm.
    func leaveQueue(_ position: BipeQueuePosition) async {
        leavingQueue = nil
        do {
            _ = try await api.leaveQueue(business: business, placeId: position.place.id)
        } catch {
            notice = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
        await refresh()
    }
}

/// The header menu items of the DM with a business: one per tool that is on (business-chat-bar).
/// Native menu labels use SF Symbols (AGENTS.md, Icons).
struct BusinessMenuItems: View {
    @ObservedObject var model: BusinessChatModel

    var body: some View {
        if model.isCompany {
            if model.canBook {
                Button { model.route = .book(serviceId: nil) } label: {
                    Label(BipeStrings.agendaBook, systemImage: "calendar.badge.plus")
                }
            }
            if model.hasCatalog {
                Button { model.route = .catalog } label: {
                    Label(BipeStrings.catalogOpen, systemImage: "list.bullet.rectangle")
                }
            }
            if model.canOrder {
                Button { model.route = .order(itemId: nil) } label: {
                    Label(BipeStrings.orderMake, systemImage: "cart")
                }
            }
            Button { model.requestingQuote = true } label: {
                Label(BipeStrings.quoteRequest, systemImage: "doc.text")
            }
            if model.queueOpen {
                Button { model.route = .queue } label: {
                    Label(BipeStrings.queueJoin, systemImage: "list.number")
                }
            }
        }
    }
}

/// The buttons under the name on person-profile of a company account (ADR 0103: the button is Agendar).
struct BusinessProfileButtons: View {
    @ObservedObject var model: BusinessChatModel

    private struct Tool: Identifiable {
        let id: String
        let title: String
        let prominent: Bool
        let action: () -> Void
    }

    /// Pedir orçamento is on for every company (spec business-chat-bar).
    private var tools: [Tool] {
        guard model.isCompany else { return [] }
        var tools: [Tool] = []
        if model.canBook {
            tools.append(Tool(id: "book", title: BipeStrings.agendaBook, prominent: true) { model.route = .book(serviceId: nil) })
        }
        if model.hasCatalog {
            tools.append(Tool(id: "catalog", title: BipeStrings.catalogOpen, prominent: false) { model.route = .catalog })
        }
        if model.canOrder {
            tools.append(Tool(id: "order", title: BipeStrings.orderMake, prominent: false) { model.route = .order(itemId: nil) })
        }
        tools.append(Tool(id: "quote", title: BipeStrings.quoteRequest, prominent: false) { model.requestingQuote = true })
        if model.queueOpen {
            tools.append(Tool(id: "queue", title: BipeStrings.queueJoin, prominent: !model.canBook) { model.route = .queue })
        }
        return tools
    }

    var body: some View {
        let shown = tools
        if !shown.isEmpty {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                ForEach(shown) { tool in
                    if tool.prominent {
                        Button(action: tool.action) {
                            Text(tool.title).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bipeProminent)
                    } else {
                        Button(action: tool.action) {
                            Text(tool.title).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bipeBordered)
                    }
                }
            }
            .controlSize(.large)
            .disabled(model.working)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(BipeStrings.businessToolsA11y)
            .accessibilityIdentifier(BipeScreen.businessChatBar.rawValue)
        }
    }
}

/// The cards of the chat, pinned above the timeline (ADR 0102): bookings, the order, quotes and the queue place.
struct BookingCardStack: View {
    @ObservedObject var model: BusinessChatModel

    private var isEmpty: Bool {
        model.bookings.isEmpty && model.orders.isEmpty && model.quotes.isEmpty && model.queuePlaces.isEmpty
    }

    var body: some View {
        if !isEmpty {
            VStack(spacing: 8) {
                ForEach(model.queuePlaces) { position in
                    QueueCardView(position: position,
                                  customerName: model.ownerMode ? (position.place.customer.name ?? model.peerName) : nil,
                                  actions: model.ownerMode ? .owner(open: { model.route = .ownerQueue })
                                      : .customer(leave: { model.leavingQueue = position }))
                }
                ForEach(model.orders) { order in
                    OrderCardView(order: order,
                                  customerName: model.ownerMode ? (order.customer.name ?? model.peerName) : nil,
                                  actions: model.ownerMode ? .owner(open: { model.route = .ownerOrder(order) })
                                      : .customer(cancel: { model.cancellingOrder = order }))
                }
                ForEach(model.quotes) { quote in
                    QuoteCardView(quote: quote,
                                  visitServiceName: quote.current?.visitServiceId.flatMap { model.serviceNames[$0] },
                                  actions: model.ownerMode ? .owner(revise: { model.route = .ownerQuote(quote) })
                                      : .customer(acceptOrder: { model.acceptingQuote = quote },
                                                  acceptBooking: { model.route = .quoteBooking(quote) },
                                                  refuse: { Task { await model.refuseQuote(quote) } }),
                                  showVersions: { model.route = .quoteVersions(quote) })
                        .disabled(model.working)
                }
                ForEach(model.bookings) { booking in
                    BookingCardView(booking: booking,
                                    serviceName: model.serviceNames[booking.serviceId],
                                    agendaName: model.agendaNames[booking.agendaId],
                                    customerName: model.ownerMode ? (booking.customer.name ?? model.peerName) : nil,
                                    actions: model.ownerMode ? .owner(open: { model.route = .ownerBooking(booking) }) : .customer(
                                        addToCalendar: { model.addToCalendar(booking) },
                                        reschedule: { model.route = .reschedule(booking) },
                                        cancel: { model.cancelling = booking }))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .accessibilityIdentifier(BipeScreen.businessChatBar.rawValue)
            // queue-customer load: the card reads again every 30 seconds while it shows and the place is active.
            .refreshEvery30Seconds(while: !model.queuePlaces.isEmpty) { await model.refreshQueue() }
        }
    }
}

/// Spec agenda-booking-card: service, person, date, time and status, with the customer's actions, or (owner mode)
/// the customer and a tap that opens agenda-owner-booking.
struct BookingCardView: View {
    enum Actions {
        case customer(addToCalendar: () -> Void, reschedule: () -> Void, cancel: () -> Void)
        case owner(open: () -> Void)
        case none
    }

    let booking: BipeBooking
    let serviceName: String?
    let agendaName: String?
    /// Owner mode: the customer's name.
    var customerName: String? = nil
    let actions: Actions

    var body: some View {
        switch actions {
        case .owner(let open):
            Button(action: open) { card }
                .buttonStyle(.plain)
        default:
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(BipeStrings.agendaCardTitle)
                .font(.caption.bold())
                .foregroundStyle(BipeColor.accentInk)
            if let serviceName, !serviceName.isEmpty {
                Text(serviceName)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            Text(BookingText.when(booking))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkPrimary)
            if let agendaName, !agendaName.isEmpty {
                Text(BipeStrings.agendaWith(agendaName))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if let customerName, !customerName.isEmpty {
                Text(customerName)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            Text(booking.status.text)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(booking.status.tint)
            if case .customer(let addToCalendar, let reschedule, let cancel) = actions {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { buttons(addToCalendar, reschedule, cancel) }
                    VStack(alignment: .leading, spacing: 6) { buttons(addToCalendar, reschedule, cancel) }
                }
                .padding(.top, 2)
            }
        }
        .businessCard(.agendaBookingCard)
    }

    @ViewBuilder
    private func buttons(_ addToCalendar: @escaping () -> Void, _ reschedule: @escaping () -> Void, _ cancel: @escaping () -> Void) -> some View {
        Button(BipeStrings.agendaAddToCalendar, action: addToCalendar)
        if booking.status.isOpen {
            Button(BipeStrings.agendaReschedule, action: reschedule)
        }
        if booking.canCancel() {
            Button(BipeStrings.agendaCancel, role: .destructive, action: cancel)
        }
    }
}

extension View {
    /// The sheets, dialogs and refresh of the business tools of a chat or a profile.
    func businessChatTools(_ model: BusinessChatModel) -> some View {
        modifier(BusinessChatTools(model: model))
    }
}

private struct BusinessChatTools: ViewModifier {
    @ObservedObject var model: BusinessChatModel
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .sheet(item: $model.route) { route in
                sheet(route)
            }
            .alert(BipeStrings.agendaCancelConfirm, isPresented: Binding(get: { model.cancelling != nil }, set: { if !$0 { model.cancelling = nil } })) {
                Button(BipeStrings.agendaCancel, role: .destructive) {
                    if let booking = model.cancelling { Task { await model.cancel(booking) } }
                }
                Button(BipeStrings.agendaKeep, role: .cancel) { model.cancelling = nil }
            } message: {
                Text(BipeStrings.agendaCancelConfirmBody)
            }
            .alert(BipeStrings.orderCancelConfirm, isPresented: Binding(get: { model.cancellingOrder != nil }, set: { if !$0 { model.cancellingOrder = nil } })) {
                Button(BipeStrings.orderCancel, role: .destructive) {
                    if let order = model.cancellingOrder { Task { await model.cancelOrder(order) } }
                }
                Button(BipeStrings.appCancel, role: .cancel) { model.cancellingOrder = nil }
            }
            .alert(BipeStrings.queueLeaveConfirm, isPresented: Binding(get: { model.leavingQueue != nil }, set: { if !$0 { model.leavingQueue = nil } })) {
                Button(BipeStrings.queueLeave, role: .destructive) {
                    if let position = model.leavingQueue { Task { await model.leaveQueue(position) } }
                }
                Button(BipeStrings.appCancel, role: .cancel) { model.leavingQueue = nil }
            }
            .alert(BipeStrings.quoteRequest, isPresented: $model.requestingQuote) {
                Button(BipeStrings.quoteRequest) { Task { await model.requestQuote() } }
                Button(BipeStrings.appCancel, role: .cancel) {}
            } message: {
                Text(BipeStrings.quoteRequestBody)
            }
            .confirmationDialog(BipeStrings.quoteAccept,
                                isPresented: Binding(get: { model.acceptingQuote != nil }, set: { if !$0 { model.acceptingQuote = nil } }),
                                titleVisibility: .visible, presenting: model.acceptingQuote) { quote in
                ForEach(BipeFulfilment.allCases, id: \.self) { fulfilment in
                    Button(fulfilment.text) { Task { await model.acceptQuote(quote, fulfilment: fulfilment) } }
                }
                Button(BipeStrings.appCancel, role: .cancel) {}
            } message: { _ in
                Text(BipeStrings.orderFulfilment + "\n" + BipeStrings.orderPayOnSite)
            }
            .sheet(item: $model.sharedFile) { file in
                ShareSheet(items: [file.url])
            }
            .toast($model.notice)
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await model.refresh() } }
            }
    }

    @ViewBuilder
    private func sheet(_ route: BusinessRoute) -> some View {
        switch route {
        case .book(let serviceId):
            AgendaBookView(api: model.api, business: model.business, preselectedServiceId: serviceId, moving: nil,
                           close: { finished($0) })
        case .reschedule(let booking):
            AgendaBookView(api: model.api, business: model.business, preselectedServiceId: booking.serviceId, moving: booking,
                           close: { finished($0) })
        case .catalog:
            CatalogView(api: model.api, business: model.business, canBook: model.canBook,
                        book: { serviceId in model.route = .book(serviceId: serviceId) },
                        addToOrder: model.canOrder ? { itemId in model.route = .order(itemId: itemId) } : nil,
                        close: { model.route = nil })
        case .ownerBooking(let booking):
            AgendaOwnerBookingView(api: model.api, mode: .existing(booking), state: nil,
                                   customers: booking.customer.userId.map { [BusinessCustomer(userId: $0, name: model.peerName)] } ?? [],
                                   customerName: booking.customer.name ?? model.peerName, openChat: nil,
                                   close: { finished($0) })
        case .order(let itemId):
            OrderCustomerView(api: model.api, business: model.business, initialItemId: itemId, close: { finished($0) })
        case .quoteBooking(let quote):
            AgendaBookView(api: model.api, business: model.business, preselectedServiceId: quote.current?.visitServiceId, moving: nil,
                           acceptQuote: { serviceId, agendaId, start in
                               await model.acceptQuoteBooking(quote, serviceId: serviceId, agendaId: agendaId, start: start)
                           },
                           close: { finished($0) })
        case .quoteVersions(let quote):
            QuoteVersionsView(quote: quote, close: { model.route = nil })
        case .queue:
            QueueCustomerView(api: model.api, business: model.business, close: { finished($0) })
        case .ownerOrder(let order):
            OrderOwnerDetailView(context: model.ownerContext, order: order, customerName: order.customer.name ?? model.peerName,
                                 close: { finished($0) })
        case .ownerQuote(let quote):
            QuoteBuilderView(context: model.ownerContext, mode: .revise(quote), customerName: model.peerName, close: { finished($0) })
        case .ownerQueue:
            NavigationStack {
                QueueOwnerView(context: model.ownerContext)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(BipeStrings.appClose) { finished(nil) }
                        }
                    }
            }
        }
    }

    /// A flow closed: shows its result and refreshes the cards.
    private func finished(_ message: String?) {
        model.route = nil
        if let message { model.notice = message }
        Task { await model.refresh() }
    }
}
