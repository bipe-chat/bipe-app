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

    var id: String {
        switch self {
        case .book(let serviceId): "book-\(serviceId ?? "")"
        case .reschedule(let booking): "move-\(booking.id)"
        case .catalog: "catalog"
        case .ownerBooking(let booking): "owner-\(booking.id)"
        }
    }
}

/// Spec business-chat-bar (bipe-shared product/screens/business-chat-bar.json): the tools a company turned on, for
/// its chat and its profile, and the cards of the open bookings with it (agenda-booking-card). Nothing comes from the
/// room: the cards come only from bipe-api (ADR 0102).
@MainActor
final class BusinessChatModel: ObservableObject {
    /// true only when the other person is a company account. The tools are hidden otherwise.
    @Published private(set) var isCompany = false
    @Published private(set) var handle: String?
    @Published private(set) var canBook = false
    @Published private(set) var hasCatalog = false
    /// Open bookings of this DM: with the business, or (owner mode) of this customer.
    @Published private(set) var bookings: [BipeBooking] = []
    /// true on the company account: the cards show the customer and open agenda-owner-booking.
    @Published private(set) var ownerMode = false
    @Published private(set) var serviceNames: [String: String] = [:]
    @Published private(set) var agendaNames: [String: String] = [:]
    @Published var route: BusinessRoute?
    @Published var cancelling: BipeBooking?
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

    /// The business segment of the customer routes: the handle, else the Matrix ID.
    var business: String { handle ?? businessId ?? "" }

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
        }
        if withBookings { await loadBookings(peer: peer) }
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
        bookings = []
        ownerMode = false
    }

    private func loadTools(of peer: String) async {
        let client = client
        let session = session
        let resolved = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> [String: String] in
            try await client.resolveHandles(userIDs: [peer], accessToken: token)
        }
        if let found = resolved?[peer] { handle = found }
        let services = (try? await api.services(business: business)) ?? []
        canBook = !services.isEmpty
        for service in services { serviceNames[service.id] = service.name }
        if canBook, let agendas = try? await api.agendas(business: business) {
            for agenda in agendas { agendaNames[agenda.id] = agenda.name }
        }
        hasCatalog = !((try? await api.catalog(business: business)) ?? []).isEmpty
    }

    private func loadBookings(peer: String) async {
        guard ownerMode || isCompany, let all = try? await api.myBookings() else {
            if !(ownerMode || isCompany) { bookings = [] }
            return
        }
        if ownerMode {
            // The company's own bookings, for the customer of this DM (agenda-booking-card owner mode).
            bookings = all.filter { $0.customer.userId == peer }
            if !bookings.isEmpty, let state = try? await api.agendaState() {
                for service in state.services { serviceNames[service.id] = service.name }
                for agenda in state.agendas { agendaNames[agenda.id] = agenda.name }
            }
        } else {
            bookings = all.filter { $0.businessId == peer }
        }
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
}

/// The header menu items of the DM with a business: one per tool that is on (business-chat-bar).
/// Native menu labels use SF Symbols (AGENTS.md, Icons).
struct BusinessMenuItems: View {
    @ObservedObject var model: BusinessChatModel

    var body: some View {
        if model.isCompany && model.canBook {
            Button { model.route = .book(serviceId: nil) } label: {
                Label(BipeStrings.agendaBook, systemImage: "calendar.badge.plus")
            }
        }
        if model.isCompany && model.hasCatalog {
            Button { model.route = .catalog } label: {
                Label(BipeStrings.catalogOpen, systemImage: "list.bullet.rectangle")
            }
        }
    }
}

/// The buttons under the name on person-profile of a company account (ADR 0103: the button is Agendar).
struct BusinessProfileButtons: View {
    @ObservedObject var model: BusinessChatModel

    var body: some View {
        if model.isCompany && (model.canBook || model.hasCatalog) {
            HStack(spacing: 12) {
                if model.canBook {
                    Button { model.route = .book(serviceId: nil) } label: {
                        Text(BipeStrings.agendaBook).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                if model.hasCatalog {
                    Button { model.route = .catalog } label: {
                        Text(BipeStrings.catalogOpen).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .controlSize(.large)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(BipeStrings.businessToolsA11y)
            .accessibilityIdentifier(BipeScreen.businessChatBar.rawValue)
        }
    }
}

/// The open bookings of the chat, pinned above the timeline (ADR 0102).
struct BookingCardStack: View {
    @ObservedObject var model: BusinessChatModel

    var body: some View {
        if !model.bookings.isEmpty {
            VStack(spacing: 8) {
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
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(BipeColor.lineSubtle))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(BipeScreen.agendaBookingCard.rawValue)
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
                                close: { model.route = nil })
                case .ownerBooking(let booking):
                    AgendaOwnerBookingView(api: model.api, mode: .existing(booking), state: nil,
                                           customers: booking.customer.userId.map { [BusinessCustomer(userId: $0, name: model.peerName)] } ?? [],
                                           customerName: booking.customer.name ?? model.peerName, openChat: nil,
                                           close: { finished($0) })
                }
            }
            .alert(BipeStrings.agendaCancelConfirm, isPresented: Binding(get: { model.cancelling != nil }, set: { if !$0 { model.cancelling = nil } })) {
                Button(BipeStrings.agendaCancel, role: .destructive) {
                    if let booking = model.cancelling { Task { await model.cancel(booking) } }
                }
                Button(BipeStrings.agendaKeep, role: .cancel) { model.cancelling = nil }
            } message: {
                Text(BipeStrings.agendaCancelConfirmBody)
            }
            .sheet(item: $model.sharedFile) { file in
                ShareSheet(items: [file.url])
            }
            .toast($model.notice)
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await model.refresh() } }
            }
    }

    /// A flow closed: shows its result and refreshes the cards.
    private func finished(_ message: String?) {
        model.route = nil
        if let message { model.notice = message }
        Task { await model.refresh() }
    }
}
