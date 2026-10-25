// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec agenda-my-bookings (bipe-shared product/screens/agenda-my-bookings.json): the customer's upcoming bookings
/// across businesses. A tap opens the chat with the business.
struct MyBookingsView: View {
    let people: PeopleService
    let openChat: (String) -> Void
    private let api: BipeBusinessAPI

    @State private var bookings: [BipeBooking] = []
    @State private var loading = true
    @State private var failed = false
    @State private var serviceNames: [String: String] = [:]
    @State private var agendaNames: [String: String] = [:]
    @State private var businessNames: [String: String] = [:]
    @State private var moving: BipeBooking?
    @State private var cancelling: BipeBooking?
    @State private var sharedFile: SharedFile?
    @State private var notice: String?
    @State private var opening = false

    init(people: PeopleService, openChat: @escaping (String) -> Void) {
        self.people = people
        self.openChat = openChat
        api = people.session.business(people.api)
    }

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.agendaMyBookings)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier(BipeScreen.agendaMyBookings.rawValue)
            .task { await load() }
            .refreshable { await load() }
            .sheet(item: $moving) { booking in
                AgendaBookView(api: api, business: booking.businessId, preselectedServiceId: booking.serviceId, moving: booking) { message in
                    moving = nil
                    if let message { notice = message }
                    Task { await load() }
                }
            }
            .alert(BipeStrings.agendaCancelConfirm, isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } })) {
                Button(BipeStrings.agendaCancel, role: .destructive) {
                    if let booking = cancelling { Task { await cancel(booking) } }
                }
                Button(BipeStrings.agendaKeep, role: .cancel) { cancelling = nil }
            } message: {
                Text(BipeStrings.agendaCancelConfirmBody)
            }
            .sheet(item: $sharedFile) { file in
                ShareSheet(items: [file.url])
            }
            .toast($notice)
    }

    @ViewBuilder
    private var content: some View {
        if loading && bookings.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && bookings.isEmpty {
            VStack(spacing: 16) {
                Text(BipeStrings.agendaLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.bipeProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if bookings.isEmpty {
            Text(BipeStrings.agendaNoBookings)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(bookings) { booking in
                    VStack(alignment: .leading, spacing: 8) {
                        Button {
                            open(booking.businessId)
                        } label: {
                            HStack {
                                Text(businessNames[booking.businessId] ?? Person.localpart(of: booking.businessId))
                                    .font(.headline)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Spacer()
                                PhosphorIcon.chatText.image
                                    .resizable()
                                    .frame(width: 20, height: 20)
                                    .foregroundStyle(BipeColor.accentInk)
                            }
                        }
                        .buttonStyle(.borderless)
                        .disabled(opening)
                        BookingCardView(booking: booking,
                                        serviceName: serviceNames[booking.serviceId],
                                        agendaName: agendaNames[booking.agendaId],
                                        actions: .customer(addToCalendar: { addToCalendar(booking) },
                                                           reschedule: { moving = booking },
                                                           cancel: { cancelling = booking }))
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                }
            }
            .listStyle(.plain)
        }
    }

    /// Action load: GET /v1/me/bookings, then the service names from each business's public services.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            bookings = try await api.myBookings().filter { $0.customer.userId == people.session.userId }.sorted { $0.start < $1.start }
            failed = false
        } catch {
            failed = true
            return
        }
        for businessId in Set(bookings.map(\.businessId)) {
            if businessNames[businessId] == nil, let person = try? await people.person(businessId) {
                businessNames[businessId] = person.displayName
            }
            if let services = try? await api.services(business: businessId) {
                for service in services { serviceNames[service.id] = service.name }
            }
            if let agendas = try? await api.agendas(business: businessId) {
                for agenda in agendas { agendaNames[agenda.id] = agenda.name }
            }
        }
    }

    /// Action openChat: the DM with the business, started when there is none.
    private func open(_ businessId: String) {
        guard !opening else { return }
        opening = true
        Task {
            defer { opening = false }
            if let roomId = try? await people.openOrCreateDM(with: businessId) { openChat(roomId) }
        }
    }

    private func addToCalendar(_ booking: BipeBooking) {
        sharedFile = BookingCalendarFile.write(booking, serviceName: serviceNames[booking.serviceId] ?? "",
                                               businessName: businessNames[booking.businessId] ?? Person.localpart(of: booking.businessId))
        if sharedFile == nil { notice = BipeStrings.agendaErrorSave }
    }

    private func cancel(_ booking: BipeBooking) async {
        cancelling = nil
        do {
            _ = try await api.cancel(business: booking.businessId, bookingId: booking.id)
        } catch {
            notice = (error as? BipeAPIError)?.agendaMessage ?? BipeStrings.agendaErrorSave
        }
        await load()
    }
}
