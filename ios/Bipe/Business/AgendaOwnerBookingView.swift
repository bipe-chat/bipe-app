// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec agenda-owner-booking (bipe-shared product/screens/agenda-owner-booking.json): one booking on the company side,
/// or a new encaixe, with Remarcar, Cancelar, Não veio, and Aceitar or Recusar for a requested booking.
struct AgendaOwnerBookingView: View {
    enum Mode {
        /// An encaixe on the day, at the tapped time when there is one.
        case new(day: String?, start: Date?, agendaId: String?)
        case existing(BipeBooking)
    }

    private enum CustomerSource: Hashable {
        case chats
        case typed
    }

    let api: BipeBusinessAPI
    let mode: Mode
    /// The business's DM partners; an encaixe can name one.
    let customers: [BusinessCustomer]
    /// The customer's name for an existing booking.
    let customerName: String?
    /// Opens the DM with the signed-in customer; nil inside that DM.
    let openChat: ((String) -> Void)?
    /// Closes the sheet; the text is the result to show, or nil.
    let close: (String?) -> Void

    @State private var state: BipeAgendaState?
    @State private var loadFailed = false
    @State private var serviceId = ""
    @State private var agendaId = ""
    @State private var start = Date()
    @State private var source: CustomerSource = .chats
    @State private var customerUserId: String?
    @State private var typedName = ""
    @State private var rescheduling = false
    @State private var saving = false
    @State private var error: String?
    @State private var confirmsCancel = false

    init(api: BipeBusinessAPI, mode: Mode, state: BipeAgendaState?, customers: [BusinessCustomer], customerName: String?,
         openChat: ((String) -> Void)?, close: @escaping (String?) -> Void) {
        self.api = api
        self.mode = mode
        self.customers = customers
        self.customerName = customerName
        self.openChat = openChat
        self.close = close
        _state = State(initialValue: state)
    }

    private var booking: BipeBooking? {
        if case .existing(let booking) = mode { return booking }
        return nil
    }

    private var timeZone: TimeZone { BusinessDay.timeZone(state?.timeZone) }

    private var editing: Bool { booking == nil || rescheduling }

    var body: some View {
        NavigationStack {
            Group {
                if state == nil {
                    if loadFailed {
                        VStack(spacing: 16) {
                            Text(BipeStrings.agendaLoadFailed)
                                .foregroundStyle(BipeColor.inkSecondary)
                                .multilineTextAlignment(.center)
                            Button(BipeStrings.appRetry) { Task { await loadState() } }
                                .buttonStyle(.borderedProminent)
                        }
                        .padding(24)
                    } else {
                        ProgressView()
                    }
                } else {
                    form
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(booking == nil ? BipeStrings.agendaWalkIn : (booking.map { serviceName($0.serviceId) } ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) {
                        if rescheduling { rescheduling = false } else { close(nil) }
                    }
                }
                if editing {
                    ToolbarItem(placement: .confirmationAction) {
                        if saving { ProgressView() } else {
                            Button(BipeStrings.appSave) { Task { await save() } }
                                .disabled(!canSave)
                        }
                    }
                }
            }
            .alert(BipeStrings.agendaCancelConfirm, isPresented: $confirmsCancel) {
                Button(BipeStrings.agendaCancel, role: .destructive) { Task { await act(.cancel) } }
                Button(BipeStrings.agendaKeep, role: .cancel) {}
            } message: {
                Text(BipeStrings.agendaCancelConfirmBody)
            }
        }
        .accessibilityIdentifier(BipeScreen.agendaOwnerBooking.rawValue)
        .task {
            if state == nil { await loadState() }
            fill()
        }
    }

    @ViewBuilder
    private var form: some View {
        Form {
            if let booking, !rescheduling {
                details(booking)
                actions(booking)
            } else {
                editors
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
    }

    private func details(_ booking: BipeBooking) -> some View {
        Section {
            LabeledContent(BipeStrings.agendaService, value: serviceName(booking.serviceId))
            LabeledContent(BipeStrings.agendaPerson, value: agendaName(booking.agendaId))
            LabeledContent(BipeStrings.agendaDate, value: booking.start.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)))
            LabeledContent(BipeStrings.agendaTime, value: BookingText.time(booking.start))
            LabeledContent(BipeStrings.agendaCustomer, value: booking.customer.name ?? customerName ?? "")
            Text(booking.status.text)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(booking.status.tint)
        }
    }

    @ViewBuilder
    private func actions(_ booking: BipeBooking) -> some View {
        Section {
            if booking.status == .requested {
                Button(BipeStrings.agendaAccept) { Task { await act(.accept) } }
                Button(BipeStrings.agendaDecline, role: .destructive) { Task { await act(.refuse) } }
            }
            if booking.status == .requested || booking.status == .confirmed {
                Button(BipeStrings.agendaReschedule) { rescheduling = true }
            }
            if (booking.status == .confirmed || booking.status == .done) && booking.start <= Date() {
                Button(BipeStrings.agendaMarkNoShow) { Task { await act(.noShow) } }
            }
            if let userId = booking.customer.userId, let openChat {
                Button(BipeStrings.appPersonMessage) { openChat(userId) }
            }
            if booking.canCancel() {
                Button(BipeStrings.agendaCancel, role: .destructive) { confirmsCancel = true }
            }
        }
        .disabled(saving)
    }

    @ViewBuilder
    private var editors: some View {
        if let state {
            Section {
                if booking == nil {
                    Picker(BipeStrings.agendaService, selection: $serviceId) {
                        ForEach(state.services) { service in
                            Text(service.name).tag(service.id)
                        }
                    }
                }
                Picker(BipeStrings.agendaPerson, selection: $agendaId) {
                    ForEach(state.agendas) { agenda in
                        Text(agenda.name).tag(agenda.id)
                    }
                }
                DatePicker(BipeStrings.agendaDate, selection: $start, displayedComponents: [.date])
                DatePicker(BipeStrings.agendaTime, selection: $start, displayedComponents: [.hourAndMinute])
            }
            .environment(\.timeZone, timeZone)
            if booking == nil {
                Section {
                    Picker(BipeStrings.agendaCustomer, selection: $source) {
                        Text(BipeStrings.agendaCustomerFromChats).tag(CustomerSource.chats)
                        Text(BipeStrings.agendaCustomerName).tag(CustomerSource.typed)
                    }
                    .pickerStyle(.segmented)
                    if source == .chats {
                        Picker(BipeStrings.agendaCustomer, selection: $customerUserId) {
                            Text(verbatim: "—").tag(String?.none)
                            ForEach(customers) { customer in
                                Text(customer.name).tag(String?.some(customer.userId))
                            }
                        }
                    } else {
                        TextField(BipeStrings.agendaCustomerName, text: $typedName)
                    }
                } header: {
                    Text(BipeStrings.agendaCustomer)
                } footer: {
                    if source == .typed { Text(BipeStrings.agendaWalkInHint) }
                }
            }
        }
    }

    private var canSave: Bool {
        guard !saving, !agendaId.isEmpty else { return false }
        guard booking == nil else { return true }
        guard !serviceId.isEmpty else { return false }
        switch source {
        case .chats: return customerUserId != nil
        case .typed: return !typedName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private func serviceName(_ id: String) -> String {
        state?.services.first { $0.id == id }?.name ?? ""
    }

    private func agendaName(_ id: String) -> String {
        state?.agendas.first { $0.id == id }?.name ?? ""
    }

    private func loadState() async {
        do {
            state = try await api.agendaState()
            loadFailed = false
        } catch {
            loadFailed = true
        }
    }

    /// The editors start from the booking, or from the day and time the owner tapped.
    private func fill() {
        guard let state, serviceId.isEmpty, agendaId.isEmpty else { return }
        switch mode {
        case .existing(let booking):
            serviceId = booking.serviceId
            agendaId = booking.agendaId
            start = booking.start
        case .new(let day, let tapped, let agenda):
            serviceId = state.services.first?.id ?? ""
            agendaId = agenda ?? state.agendas.first?.id ?? ""
            customerUserId = customers.count == 1 ? customers.first?.userId : nil
            if let tapped {
                start = tapped
            } else {
                let zone = BusinessDay.timeZone(state.timeZone)
                let today = BusinessDay.string(Date(), in: zone)
                let chosen = day ?? today
                if chosen == today {
                    // The next quarter hour.
                    let quarter: TimeInterval = 15 * 60
                    start = Date(timeIntervalSinceReferenceDate: (Date().timeIntervalSinceReferenceDate / quarter).rounded(.up) * quarter)
                } else if let midnight = BusinessDay.start(of: chosen, in: zone) {
                    start = midnight.addingTimeInterval(9 * 3600)
                }
            }
        }
    }

    /// Action save: a new encaixe, or Remarcar (move). slot_taken shows bipe_agenda_error_slot_taken.
    private func save() async {
        saving = true
        error = nil
        defer { saving = false }
        do {
            if let booking {
                _ = try await api.ownerMove(bookingId: booking.id, agendaId: agendaId, start: start)
            } else {
                let customer: BipeBookingCustomer = switch source {
                case .chats: .user(customerUserId ?? "")
                case .typed: .named(typedName.trimmingCharacters(in: .whitespacesAndNewlines))
                }
                _ = try await api.ownerBook(serviceId: serviceId, agendaId: agendaId, start: start, customer: customer)
            }
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.agendaMessage ?? BipeStrings.agendaErrorSave
        }
    }

    /// Actions cancel, noShow, accept and refuse.
    private func act(_ action: BipeOwnerBookingAction) async {
        guard let booking else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            _ = try await api.ownerAction(bookingId: booking.id, action)
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.agendaMessage ?? BipeStrings.agendaErrorSave
        }
    }
}
