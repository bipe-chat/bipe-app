// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec agenda-book (bipe-shared product/screens/agenda-book.json): service, person, a day on a week strip and a
/// free time, then the review with the policy. The time is held for 5 minutes until confirm (docs/scheduling.md).
@MainActor
final class AgendaBookModel: ObservableObject {
    enum Step: Equatable {
        case service
        case person
        case time
        case review
    }

    enum Phase: Equatable {
        case loading
        case loaded
        case failed
    }

    static let weekLength = 7

    @Published private(set) var step: Step = .service
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var services: [BipeCatalogItem] = []
    @Published private(set) var agendas: [BipeAgenda] = []
    @Published private(set) var week: [BipeSlotDay] = []
    @Published private(set) var weekStart: String
    @Published private(set) var weekLoading = false
    @Published private(set) var timeZone: TimeZone = .current
    @Published private(set) var selectedService: BipeCatalogItem?
    /// nil is Qualquer pessoa (any).
    @Published private(set) var selectedAgenda: String?
    @Published var selectedDay: String?
    @Published private(set) var selectedStart: Date?
    @Published private(set) var hold: BipeBooking?
    @Published private(set) var error: String?
    @Published private(set) var working = false

    let business: String
    /// The booking that Remarcar moves; the review step calls move instead of hold and confirm.
    let moving: BipeBooking?
    /// quote-card acceptBooking: the review step calls quote accept with target booking instead of hold and
    /// confirm. Returns the error text, or nil when the quote is accepted.
    let acceptQuote: (@MainActor (_ serviceId: String, _ agendaId: String?, _ start: Date) async -> String?)?
    private let api: BipeBusinessAPI
    private var today: String
    private var timeZoneKnown = false

    init(api: BipeBusinessAPI, business: String, moving: BipeBooking?,
         acceptQuote: (@MainActor (_ serviceId: String, _ agendaId: String?, _ start: Date) async -> String?)? = nil) {
        self.api = api
        self.business = business
        self.moving = moving
        self.acceptQuote = acceptQuote
        let today = BusinessDay.string(Date(), in: .current)
        self.today = today
        weekStart = today
    }

    var canGoBackAWeek: Bool { weekStart > today }

    /// Back is hidden on the first step; Remarcar and a quote's visit keep their service.
    var canGoBack: Bool {
        switch step {
        case .service: false
        case .person: keepsService == false
        case .time: keepsService == false || agendas.count > 1
        case .review: true
        }
    }

    /// Remarcar and a quote's visit book one known service.
    private var keepsService: Bool { moving != nil || acceptQuote != nil }

    var selectedSlots: [BipeSlot] {
        week.first { $0.date == selectedDay }?.slots ?? []
    }

    var agendaName: String? {
        selectedAgenda.flatMap { id in agendas.first { $0.id == id }?.name }
    }

    /// The policy line. The public routes do not give the business's cancel window yet, so it shows the ADR 0103
    /// default (bipe-chat/bipe: shared-spec mismatch, see the report).
    var policyLine: String {
        BipeStrings.agendaCancelPolicy(BookingText.minutes(BipeAgendaPolicy.defaults.cancelWindowMinutes))
    }

    /// Loads the services, then picks the given one (from the catálogo, or the moved booking's).
    func start(preselectedServiceId: String?) async {
        phase = .loading
        do {
            services = try await api.services(business: business)
            // A quote's visit books its service only.
            if acceptQuote != nil, let id = preselectedServiceId { services = services.filter { $0.id == id } }
            phase = .loaded
        } catch {
            self.error = (error as? BipeAPIError)?.agendaMessage
            phase = .failed
            return
        }
        if let id = preselectedServiceId, let service = services.first(where: { $0.id == id }) {
            await pickService(service)
        }
    }

    /// Action pickService: loads the agendas; one agenda skips the person step.
    func pickService(_ service: BipeCatalogItem) async {
        selectedService = service
        error = nil
        working = true
        defer { working = false }
        do {
            agendas = try await api.agendas(business: business, serviceId: service.id)
        } catch {
            self.error = (error as? BipeAPIError)?.agendaMessage
            return
        }
        if agendas.count <= 1 {
            selectedAgenda = agendas.first?.id
            step = .time
            await showWeek(from: today)
        } else {
            step = .person
        }
    }

    /// Action pickAgenda. nil is Qualquer pessoa.
    func pickAgenda(_ agendaId: String?) async {
        selectedAgenda = agendaId
        step = .time
        await showWeek(from: today)
    }

    /// Action showWeek: seven days from one slots call. Not before today.
    func showWeek(from start: String) async {
        guard let service = selectedService else { return }
        let first = max(start, today)
        weekStart = first
        weekLoading = true
        defer { weekLoading = false }
        do {
            let days = try await api.slots(business: business, serviceId: service.id, agendaId: selectedAgenda,
                                           dateFrom: first, dateTo: BusinessDay.adding(Self.weekLength - 1, to: first))
            timeZone = BusinessDay.timeZone(days.timeZone)
            // The first answer gives the business time zone: today is its today (spec agenda-book notes).
            if !timeZoneKnown {
                timeZoneKnown = true
                let businessToday = BusinessDay.string(Date(), in: timeZone)
                if businessToday != today {
                    today = businessToday
                    if weekStart < today { return await showWeek(from: today) }
                }
            }
            week = days.days
            if !week.contains(where: { $0.date == selectedDay && !$0.slots.isEmpty }) {
                selectedDay = week.first { !$0.slots.isEmpty }?.date ?? week.first?.date
            }
        } catch {
            week = []
            self.error = (error as? BipeAPIError)?.agendaMessage
        }
    }

    /// Action pickTime: holds the time and goes to review. Remarcar and a quote's visit go to review without a hold.
    func pickTime(_ slot: BipeSlot) async {
        guard let service = selectedService, !working else { return }
        error = nil
        selectedStart = slot.start
        if keepsService {
            step = .review
            return
        }
        working = true
        defer { working = false }
        do {
            hold = try await api.hold(business: business, serviceId: service.id, agendaId: selectedAgenda, start: slot.start)
            step = .review
        } catch {
            let apiError = error as? BipeAPIError
            self.error = apiError?.agendaMessage ?? BipeStrings.agendaErrorSave
            if case .server(.slotTaken, _, _) = apiError { await showWeek(from: weekStart) }
        }
    }

    /// Action confirm (or move when rescheduling). true when the flow is done.
    func confirm() async -> Bool {
        guard !working else { return false }
        error = nil
        working = true
        defer { working = false }
        if let acceptQuote {
            guard let service = selectedService, let start = selectedStart else { return false }
            if let message = await acceptQuote(service.id, selectedAgenda, start) {
                error = message
                return false
            }
            return true
        }
        do {
            if let moving, let start = selectedStart {
                _ = try await api.move(business: business, bookingId: moving.id, agendaId: selectedAgenda, start: start)
            } else if let hold {
                _ = try await api.confirm(business: business, bookingId: hold.id)
            } else {
                return false
            }
            return true
        } catch {
            let apiError = error as? BipeAPIError
            self.error = apiError?.agendaMessage ?? BipeStrings.agendaErrorSave
            switch apiError {
            case .server(.holdExpired, _, _), .server(.slotTaken, _, _):
                await backToTime()
            default:
                break
            }
            return false
        }
    }

    /// The hold ran out on the review step.
    func holdRanOut() async {
        guard step == .review, moving == nil else { return }
        error = BipeStrings.agendaErrorHoldExpired
        await backToTime()
    }

    private func backToTime() async {
        hold = nil
        selectedStart = nil
        step = .time
        await showWeek(from: weekStart)
    }

    /// Action back. Leaving review keeps nothing: an unconfirmed hold ends by itself after 5 minutes.
    func back() {
        error = nil
        switch step {
        case .service:
            break
        case .person:
            step = .service
        case .time:
            step = agendas.count > 1 ? .person : .service
        case .review:
            hold = nil
            selectedStart = nil
            step = .time
        }
    }
}

struct AgendaBookView: View {
    @StateObject private var model: AgendaBookModel
    private let preselectedServiceId: String?
    /// Closes the flow; the text is the result to show (bipe_agenda_booked), or nil.
    private let close: (String?) -> Void

    init(api: BipeBusinessAPI, business: String, preselectedServiceId: String?, moving: BipeBooking?,
         acceptQuote: (@MainActor (_ serviceId: String, _ agendaId: String?, _ start: Date) async -> String?)? = nil,
         close: @escaping (String?) -> Void) {
        _model = StateObject(wrappedValue: AgendaBookModel(api: api, business: business, moving: moving, acceptQuote: acceptQuote))
        self.preselectedServiceId = preselectedServiceId
        self.close = close
    }

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appCancel) { close(nil) }
                    }
                    if model.canGoBack {
                        ToolbarItem(placement: .topBarLeading) {
                            Button { model.back() } label: {
                                PhosphorIcon.caretLeft.image.resizable().frame(width: 20, height: 20)
                            }
                            .accessibilityLabel(BipeStrings.a11yBack)
                        }
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.agendaBook.rawValue)
        .task { await model.start(preselectedServiceId: preselectedServiceId) }
    }

    private var confirmTitle: String {
        if model.acceptQuote != nil { return BipeStrings.quoteAcceptBooking }
        return model.moving == nil ? BipeStrings.agendaConfirm : BipeStrings.agendaReschedule
    }

    private var title: String {
        switch model.step {
        case .service: model.acceptQuote != nil ? BipeStrings.quoteAcceptBooking
            : (model.moving == nil ? BipeStrings.agendaBook : BipeStrings.agendaReschedule)
        case .person: BipeStrings.agendaPickPerson
        case .time: BipeStrings.agendaPickTime
        case .review: BipeStrings.agendaReviewTitle
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 16) {
                Text(model.error ?? BipeStrings.agendaLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) {
                    Task { await model.start(preselectedServiceId: preselectedServiceId) }
                }
                .buttonStyle(.bipeProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            switch model.step {
            case .service: serviceStep
            case .person: personStep
            case .time: timeStep
            case .review: reviewStep
            }
        }
    }

    private var errorLine: some View {
        Group {
            if let error = model.error {
                Text(error)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
    }

    private var serviceStep: some View {
        List {
            Section {
                ForEach(model.services) { service in
                    Button {
                        Task { await model.pickService(service) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(service.name).foregroundStyle(BipeColor.inkPrimary)
                                if let minutes = service.durationMinutes {
                                    Text(BipeStrings.agendaDuration(String(minutes)))
                                        .font(.subheadline)
                                        .foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                            Spacer()
                            Text(service.price.displayText)
                                .font(.subheadline)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .disabled(model.working)
                }
            } header: {
                Text(BipeStrings.agendaPickService)
            } footer: {
                errorLine
            }
        }
        .listStyle(.insetGrouped)
    }

    private var personStep: some View {
        List {
            Section {
                Button {
                    Task { await model.pickAgenda(nil) }
                } label: {
                    Text(BipeStrings.agendaAnyone).foregroundStyle(BipeColor.inkPrimary)
                }
                ForEach(model.agendas) { agenda in
                    Button {
                        Task { await model.pickAgenda(agenda.id) }
                    } label: {
                        Text(agenda.name).foregroundStyle(BipeColor.inkPrimary)
                    }
                }
            } header: {
                Text(BipeStrings.agendaPickPerson)
            } footer: {
                errorLine
            }
        }
        .listStyle(.insetGrouped)
    }

    private var timeStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                weekStrip
                errorLine
                if model.weekLoading {
                    ProgressView().frame(maxWidth: .infinity)
                } else if model.selectedSlots.isEmpty {
                    Text(BipeStrings.agendaNoSlots)
                        .foregroundStyle(BipeColor.inkSecondary)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 8)], spacing: 8) {
                        ForEach(model.selectedSlots, id: \.self) { slot in
                            Button {
                                Task { await model.pickTime(slot) }
                            } label: {
                                Text(BookingText.time(slot.start))
                                    .font(.body.monospacedDigit())
                                    .frame(maxWidth: .infinity, minHeight: 44)
                            }
                            .buttonStyle(.bipeBordered)
                            .disabled(model.working)
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    private var weekStrip: some View {
        HStack(spacing: 4) {
            Button {
                Task { await model.showWeek(from: BusinessDay.adding(-AgendaBookModel.weekLength, to: model.weekStart)) }
            } label: {
                PhosphorIcon.caretLeft.image.resizable().frame(width: 16, height: 16).bipeTapTarget()
            }
            .disabled(!model.canGoBackAWeek || model.weekLoading)
            .accessibilityLabel(BusinessDay.title(BusinessDay.adding(-AgendaBookModel.weekLength, to: model.weekStart), in: model.timeZone))
            ForEach(model.week, id: \.date) { day in
                let parts = BusinessDay.stripParts(day.date, in: model.timeZone)
                let selected = day.date == model.selectedDay
                Button {
                    model.selectedDay = day.date
                } label: {
                    VStack(spacing: 2) {
                        Text(parts.weekday).font(.caption2)
                        Text(parts.number).font(.callout.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .foregroundStyle(selected ? BipeColor.inkOnAccent : (day.slots.isEmpty ? BipeColor.inkDisabled : BipeColor.inkPrimary))
                    .background(RoundedRectangle(cornerRadius: 10).fill(selected ? BipeColor.accentFill : Color.clear))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(BusinessDay.title(day.date, in: model.timeZone))
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
            Button {
                Task { await model.showWeek(from: BusinessDay.adding(AgendaBookModel.weekLength, to: model.weekStart)) }
            } label: {
                PhosphorIcon.caretRight.image.resizable().frame(width: 16, height: 16).bipeTapTarget()
            }
            .disabled(model.weekLoading)
            .accessibilityLabel(BusinessDay.title(BusinessDay.adding(AgendaBookModel.weekLength, to: model.weekStart), in: model.timeZone))
        }
    }

    private var reviewStep: some View {
        List {
            Section {
                if let service = model.selectedService {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(service.name).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                        if let minutes = service.durationMinutes {
                            Text(BipeStrings.agendaDuration(String(minutes))).foregroundStyle(BipeColor.inkSecondary)
                        }
                        Text(service.price.displayText).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                if let start = model.selectedStart {
                    Text(start.formatted(.dateTime.weekday(.wide).day().month(.wide)) + " · " + BookingText.time(start))
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                if let name = model.agendaName {
                    Text(BipeStrings.agendaWith(name))
                        .foregroundStyle(BipeColor.inkPrimary)
                }
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(model.policyLine)
                    if let expires = model.hold?.holdExpiresAt {
                        HoldCountdown(expires: expires)
                            .task(id: expires) {
                                let wait = expires.timeIntervalSinceNow
                                if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
                                if !Task.isCancelled { await model.holdRanOut() }
                            }
                    }
                    errorLine
                }
            }
            Section {
                Button {
                    Task {
                        if await model.confirm() { close(BipeStrings.agendaBooked) }
                    }
                } label: {
                    HStack {
                        Spacer()
                        if model.working { ProgressView() } else {
                            Text(confirmTitle).bold()
                        }
                        Spacer()
                    }
                }
                .disabled(model.working)
                .listRowBackground(BipeColor.accentWash)
            }
        }
        .listStyle(.insetGrouped)
    }
}

/// bipe_agenda_hold with the time left, once a second.
private struct HoldCountdown: View {
    let expires: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let left = max(0, Int(expires.timeIntervalSince(context.date).rounded(.up)))
            Text(BipeStrings.agendaHold(String(format: "%ld:%02ld", left / 60, left % 60)))
                .monospacedDigit()
        }
    }
}
