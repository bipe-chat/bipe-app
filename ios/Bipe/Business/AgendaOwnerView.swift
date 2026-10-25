// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec agenda-owner (bipe-shared product/screens/agenda-owner.json): the company account's Agenda tab.
@MainActor
final class AgendaOwnerModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        /// GET /v1/me/business/agenda answered not_found with reason agenda_off.
        case off
        case on
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var state: BipeAgendaState?
    /// The day on screen, a local date of the business.
    @Published private(set) var day: String
    /// An agenda ID, or nil for Todos.
    @Published private(set) var agendaFilter: String?
    @Published private(set) var bookings: [BipeBooking] = []
    @Published private(set) var loadingDay = false
    @Published private(set) var customerNames: [String: String] = [:]
    @Published var notice: String?

    let api: BipeBusinessAPI
    let people: PeopleService

    init(api: BipeBusinessAPI, people: PeopleService) {
        self.api = api
        self.people = people
        day = BusinessDay.string(Date(), in: .current)
    }

    var timeZone: TimeZone { BusinessDay.timeZone(state?.timeZone) }
    var today: String { BusinessDay.string(Date(), in: timeZone) }
    var isToday: Bool { day == today }

    /// The agendas the day's actions apply to: the one on screen, or all for Todos.
    var agendaIdsOnScreen: [String] {
        if let agendaFilter { return [agendaFilter] }
        return state?.agendas.map(\.id) ?? []
    }

    /// Action refresh: the state, then the day.
    func load() async {
        if state == nil { phase = .loading }
        do {
            let loaded = try await api.agendaState()
            let first = state == nil
            state = loaded
            if first { day = BusinessDay.string(Date(), in: timeZone) }
            if let agendaFilter, !loaded.agendas.contains(where: { $0.id == agendaFilter }) { self.agendaFilter = nil }
            phase = .on
        } catch {
            if (error as? BipeAPIError)?.isAgendaOff == true {
                state = nil
                phase = .off
            } else if state == nil {
                phase = .failed
            }
            return
        }
        await loadDay()
    }

    /// The bookings of the day on screen, by start.
    func loadDay() async {
        loadingDay = true
        defer { loadingDay = false }
        guard let loaded = try? await api.ownerBookings(dateFrom: day, dateTo: day, agendaId: agendaFilter) else {
            notice = BipeStrings.agendaLoadFailed
            return
        }
        bookings = loaded.sorted { $0.start < $1.start }
        for userId in Set(bookings.compactMap(\.customer.userId)) where customerNames[userId] == nil {
            customerNames[userId] = (try? await people.person(userId))?.displayName ?? Person.localpart(of: userId)
        }
    }

    /// Action pickDate.
    func pick(day: String) async {
        self.day = day
        await loadDay()
    }

    /// Action pickAgenda. nil is Todos.
    func pick(agendaId: String?) async {
        agendaFilter = agendaId
        await loadDay()
    }

    func serviceName(_ id: String) -> String {
        state?.services.first { $0.id == id }?.name ?? ""
    }

    func agendaName(_ id: String) -> String {
        state?.agendas.first { $0.id == id }?.name ?? ""
    }

    func customerName(_ booking: BipeBooking) -> String {
        booking.customer.name ?? booking.customer.userId.map { customerNames[$0] ?? Person.localpart(of: $0) } ?? ""
    }

    /// Action block: POST /v1/me/business/agenda/blocks for the agenda on screen, or every agenda.
    func block(start: Date, end: Date) async -> Bool {
        do {
            try await api.block(agendaIds: agendaIdsOnScreen, start: start, end: end)
            return true
        } catch {
            notice = BipeStrings.agendaErrorSave
            return false
        }
    }

    /// Action closeToday, after the confirm: POST close-day. Returns the signed-in customers of the day's open
    /// bookings, for the message.
    func closeToday() async -> [String]? {
        do {
            try await api.closeDay(agendaIds: agendaIdsOnScreen, date: day)
        } catch {
            notice = BipeStrings.agendaErrorSave
            return nil
        }
        let ids = agendaIdsOnScreen
        var seen = Set<String>()
        return bookings
            .filter { $0.status.isOpen && ids.contains($0.agendaId) }
            .compactMap(\.customer.userId)
            .filter { seen.insert($0).inserted }
    }

    /// Sends the owner's text into the DM with each customer, from this phone: the server sends nothing (ADR 0102).
    func send(_ text: String, to customers: [String]) async {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        var sent = 0
        for userId in customers {
            guard let roomId = try? await people.openOrCreateDM(with: userId),
                  let room = try? people.session.client.getRoom(roomId: roomId),
                  let timeline = try? await room.timeline(),
                  let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: body, formatted: nil))),
                  (try? await timeline.send(msg: content)) != nil else { continue }
            sent += 1
        }
        notice = BipeStrings.agendaCloseTodaySent(String(sent))
    }
}

/// The Agenda tab of a company account (ADR 0103), after Grupos.
struct AgendaOwnerView: View {
    @StateObject private var model: AgendaOwnerModel
    private let chatList: ChatListModel
    private let openChat: (String) -> Void

    @State private var openBooking: OwnerBookingTarget?
    @State private var showsSetup = false
    @State private var showsSettings = false
    @State private var showsBlock = false
    @State private var confirmsClose = false
    @State private var closeMessage: CloseMessage?
    @Environment(\.scenePhase) private var scenePhase

    init(chatList: ChatListModel, api: BipeAPIClient, openChat: @escaping (String) -> Void) {
        let people = PeopleService(session: chatList.session, api: api, media: chatList.media)
        _model = StateObject(wrappedValue: AgendaOwnerModel(api: chatList.session.business(api), people: people))
        self.chatList = chatList
        self.openChat = openChat
    }

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.tabAgenda)
                .toolbar {
                    if model.phase == .on {
                        ToolbarItem(placement: .primaryAction) { menu }
                    }
                }
                .navigationDestination(isPresented: $showsSettings) {
                    if let state = model.state {
                        AgendaSettingsView(api: model.api, state: state, editSetup: {
                            showsSettings = false
                            showsSetup = true
                        }, changed: { Task { await model.load() } })
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.agendaOwner.rawValue)
        .task { await model.load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.load() } }
        }
        .toast($model.notice)
        .fullScreenCover(isPresented: $showsSetup) {
            AgendaSetupView(api: model.api, startsAtHours: model.state != nil) { message in
                showsSetup = false
                if let message { model.notice = message }
                Task { await model.load() }
            }
        }
        .sheet(item: $openBooking) { target in
            AgendaOwnerBookingView(api: model.api, mode: target.mode, state: model.state,
                                   customers: BusinessCustomer.fromChats(chatList.chats),
                                   customerName: target.customerName,
                                   openChat: { userId in
                                       openBooking = nil
                                       Task {
                                           if let roomId = try? await model.people.openOrCreateDM(with: userId) { openChat(roomId) }
                                       }
                                   },
                                   close: { message in
                                       openBooking = nil
                                       if let message { model.notice = message }
                                       Task { await model.loadDay() }
                                   })
        }
        .sheet(isPresented: $showsBlock) {
            AgendaBlockSheet(model: model) { showsBlock = false }
        }
        .alert(BipeStrings.agendaCloseToday, isPresented: $confirmsClose) {
            Button(BipeStrings.agendaCloseToday, role: .destructive) {
                Task {
                    guard let customers = await model.closeToday() else { return }
                    await model.loadDay()
                    if !customers.isEmpty {
                        closeMessage = CloseMessage(customers: customers, text: BipeStrings.agendaCloseTodayMessage)
                    }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.agendaCloseTodayBody)
        }
        .sheet(item: $closeMessage) { message in
            CloseTodayMessageSheet(message: message) { text in
                closeMessage = nil
                Task { await model.send(text, to: message.customers) }
            } cancel: {
                closeMessage = nil
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 16) {
                Text(BipeStrings.agendaLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await model.load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .off:
            VStack(spacing: 20) {
                PhosphorIcon.clock.image
                    .resizable()
                    .frame(width: 48, height: 48)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.agendaTabOffBody)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.agendaActivate) { showsSetup = true }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .on:
            dayView
        }
    }

    private var menu: some View {
        Menu {
            Button {
                openBooking = OwnerBookingTarget(mode: .new(day: model.day, start: nil, agendaId: model.agendaFilter), customerName: nil)
            } label: {
                Label(BipeStrings.agendaWalkIn, systemImage: "person.badge.plus")
            }
            Button { showsBlock = true } label: {
                Label(BipeStrings.agendaBlock, systemImage: "clock.badge.xmark")
            }
            if model.isToday {
                Button(role: .destructive) { confirmsClose = true } label: {
                    Label(BipeStrings.agendaCloseToday, systemImage: "xmark.circle")
                }
            }
            Divider()
            Button { showsSettings = true } label: {
                Label(BipeStrings.agendaSettingsTitle, systemImage: "gearshape")
            }
        } label: {
            Label {
                Text(BipeStrings.agendaSettingsTitle)
            } icon: {
                PhosphorIcon.dotsThree.image.resizable().frame(width: 22, height: 22)
            }
            .labelStyle(.iconOnly)
        }
    }

    private var dayView: some View {
        List {
            Section {
                dateBar
                agendaChips
                Text(model.state?.published == true ? BipeStrings.agendaPublished : BipeStrings.agendaNotPublished)
                    .font(.footnote)
                    .foregroundStyle(model.state?.published == true ? BipeColor.trustVerifiedInk : BipeColor.trustCautionInk)
            }
            .listRowBackground(Color.clear)
            Section {
                if model.bookings.isEmpty && !model.loadingDay {
                    Text(BipeStrings.agendaEmptyDay)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .listRowBackground(BipeColor.surfaceRaised)
                }
                ForEach(rows) { row in
                    switch row {
                    case .now:
                        NowLine()
                            .listRowBackground(Color.clear)
                    case .booking(let booking):
                        Button {
                            openBooking = OwnerBookingTarget(mode: .existing(booking), customerName: model.customerName(booking))
                        } label: {
                            OwnerBookingRow(booking: booking, service: model.serviceName(booking.serviceId),
                                            agenda: model.agendaFilter == nil ? model.agendaName(booking.agendaId) : nil,
                                            customer: model.customerName(booking))
                        }
                        .listRowBackground(BipeColor.surfaceRaised)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .refreshable { await model.load() }
    }

    /// The day's bookings by time, with the now-line on today.
    private var rows: [DayRow] {
        var rows = model.bookings.map(DayRow.booking)
        if model.isToday {
            let now = Date()
            let index = model.bookings.firstIndex { $0.start > now } ?? model.bookings.count
            rows.insert(.now, at: index)
        }
        return rows
    }

    private var dateBar: some View {
        HStack {
            Button {
                Task { await model.pick(day: BusinessDay.adding(-1, to: model.day)) }
            } label: {
                PhosphorIcon.caretLeft.image.resizable().frame(width: 18, height: 18).bipeTapTarget()
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(BusinessDay.title(BusinessDay.adding(-1, to: model.day), in: model.timeZone))
            Spacer()
            Text(BusinessDay.title(model.day, in: model.timeZone))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            if !model.isToday {
                Button(BipeStrings.agendaToday) { Task { await model.pick(day: model.today) } }
                    .buttonStyle(.borderless)
                    .font(.subheadline)
            }
            Spacer()
            Button {
                Task { await model.pick(day: BusinessDay.adding(1, to: model.day)) }
            } label: {
                PhosphorIcon.caretRight.image.resizable().frame(width: 18, height: 18).bipeTapTarget()
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(BusinessDay.title(BusinessDay.adding(1, to: model.day), in: model.timeZone))
        }
    }

    @ViewBuilder
    private var agendaChips: some View {
        if let agendas = model.state?.agendas, agendas.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(title: BipeStrings.agendaAllPeople, selected: model.agendaFilter == nil) {
                        Task { await model.pick(agendaId: nil) }
                    }
                    ForEach(agendas) { agenda in
                        chip(title: agenda.name, selected: model.agendaFilter == agenda.id) {
                            Task { await model.pick(agendaId: agenda.id) }
                        }
                    }
                }
            }
        }
    }

    private func chip(title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? BipeColor.inkOnAccent : BipeColor.inkPrimary)
                .background(Capsule().fill(selected ? BipeColor.accentFill : BipeColor.surfaceRaised))
                .overlay(Capsule().stroke(BipeColor.lineSubtle))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private enum DayRow: Identifiable {
    case now
    case booking(BipeBooking)

    var id: String {
        switch self {
        case .now: "now"
        case .booking(let booking): booking.id
        }
    }
}

private struct OwnerBookingTarget: Identifiable {
    let mode: AgendaOwnerBookingView.Mode
    let customerName: String?
    let id = UUID()
}

/// One booking of the day. Cancelled and no-show ones show dimmed.
private struct OwnerBookingRow: View {
    let booking: BipeBooking
    let service: String
    let agenda: String?
    let customer: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(BookingText.time(booking.start)).font(.headline.monospacedDigit())
                Text(BookingText.time(booking.end)).font(.caption.monospacedDigit()).foregroundStyle(BipeColor.inkSecondary)
            }
            .frame(minWidth: 56, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(customer).foregroundStyle(BipeColor.inkPrimary)
                Text(agenda.map { service + " · " + $0 } ?? service)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                Text(booking.status.text)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(booking.status.tint)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(BipeColor.inkPrimary)
        .opacity(booking.status == .cancelled || booking.status == .noShow ? 0.5 : 1)
        .accessibilityElement(children: .combine)
    }
}

/// The now indicator of today (bipe_agenda_now).
private struct NowLine: View {
    var body: some View {
        HStack(spacing: 8) {
            Text(BipeStrings.agendaNow)
                .font(.caption.bold())
                .foregroundStyle(BipeColor.trustDangerInk)
            Rectangle()
                .fill(BipeColor.trustDangerInk)
                .frame(height: 1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Bloquear horário: a start and an end on the day on screen, in the business time zone.
private struct AgendaBlockSheet: View {
    @ObservedObject var model: AgendaOwnerModel
    let close: () -> Void
    @State private var start = Date()
    @State private var end = Date()
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker(BipeStrings.agendaTime, selection: $start, displayedComponents: [.hourAndMinute])
                    DatePicker(BipeStrings.agendaTime, selection: $end, displayedComponents: [.hourAndMinute])
                } header: {
                    Text(BusinessDay.title(model.day, in: model.timeZone))
                } footer: {
                    Text(model.agendaFilter.map { model.agendaName($0) } ?? BipeStrings.agendaAllPeople)
                }
                .environment(\.timeZone, model.timeZone)
            }
            .navigationTitle(BipeStrings.agendaBlock)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel, action: close)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(BipeStrings.appSave) {
                        saving = true
                        Task {
                            let done = await model.block(start: start, end: end)
                            saving = false
                            if done {
                                close()
                                await model.load()
                            }
                        }
                    }
                    .disabled(saving || end <= start)
                }
            }
        }
        .onAppear {
            // Starts at the next hour of the day on screen (or 9:00 on another day), for one hour.
            let zone = model.timeZone
            let base: Date
            if model.isToday {
                base = Date(timeIntervalSinceReferenceDate: (Date().timeIntervalSinceReferenceDate / 3600).rounded(.up) * 3600)
            } else {
                base = (BusinessDay.start(of: model.day, in: zone) ?? Date()).addingTimeInterval(9 * 3600)
            }
            start = base
            end = base.addingTimeInterval(3600)
        }
    }
}

struct CloseMessage: Identifiable {
    let customers: [String]
    var text: String
    let id = UUID()
}

/// After Fechar hoje: the message the owner edits, sent to each customer of the day.
private struct CloseTodayMessageSheet: View {
    let message: CloseMessage
    let send: (String) -> Void
    let cancel: () -> Void
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(BipeStrings.agendaCloseTodayMessage, text: $text, axis: .vertical)
                        .lineLimit(3...8)
                }
                Section {
                    Button(BipeStrings.agendaCloseTodaySend) { send(text) }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle(BipeStrings.agendaCloseToday)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel, action: cancel)
                }
            }
        }
        .onAppear { text = message.text }
    }
}
