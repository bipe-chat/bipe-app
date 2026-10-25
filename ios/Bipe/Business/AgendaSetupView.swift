// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec agenda-setup (bipe-shared product/screens/agenda-setup.json): Ativar agenda on the phone in a few steps.
@MainActor
final class AgendaSetupModel: ObservableObject {
    enum Step: Int, Comparable {
        case template
        case hours
        case people
        case services
        case publish

        static func < (lhs: Step, rhs: Step) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    /// Type DayHours: one ISO weekday, Monday 1 to Sunday 7.
    struct DayHours: Identifiable, Equatable {
        var weekday: Int
        var open: Bool
        var ranges: [ClockRange]
        var id: Int { weekday }
    }

    /// One local range, `HH:MM` to `HH:MM`.
    struct ClockRange: Identifiable, Equatable {
        var start: String
        var end: String
        let id = UUID()
    }

    @Published private(set) var step: Step
    @Published private(set) var state: BipeAgendaState?
    /// The agenda whose hours are on screen.
    @Published private(set) var hoursAgendaId: String?
    @Published var days: [DayHours] = (1...7).map { DayHours(weekday: $0, open: false, ranges: []) }
    @Published private(set) var saving = false
    @Published private(set) var loading = false
    @Published var error: String?

    let api: BipeBusinessAPI
    /// The first step on screen: agenda-settings opens setup at the hours step.
    let firstStep: Step

    init(api: BipeBusinessAPI, startsAtHours: Bool) {
        self.api = api
        let first: Step = startsAtHours ? .hours : .template
        firstStep = first
        step = first
    }

    var canGoBack: Bool { step > firstStep }

    func appear() async {
        guard firstStep == .hours, state == nil else { return }
        await refreshState()
        await loadHours(agendaId: state?.agendas.first?.id)
    }

    func refreshState() async {
        loading = true
        defer { loading = false }
        do {
            state = try await api.agendaState()
        } catch {
            self.error = BipeStrings.agendaLoadFailed
        }
    }

    /// Action pickTemplate: activates with the template and the phone's time zone; the server makes the first agenda
    /// and the services as Catálogo items.
    func pickTemplate(_ id: String) async {
        saving = true
        error = nil
        defer { saving = false }
        do {
            state = try await api.activateAgenda(templateId: id, timeZone: TimeZone.current.identifier)
        } catch {
            self.error = BipeStrings.agendaErrorSave
            return
        }
        step = .hours
        await loadHours(agendaId: state?.agendas.first?.id)
    }

    /// The hours of one agenda, from GET .../availability.
    func loadHours(agendaId: String?) async {
        hoursAgendaId = agendaId
        guard let agendaId else { return }
        loading = true
        defer { loading = false }
        guard let rules = try? await api.availability(agendaId: agendaId) else {
            error = BipeStrings.agendaLoadFailed
            return
        }
        days = (1...7).map { weekday in
            let ranges = rules.filter { $0.weekday == weekday }.map { ClockRange(start: $0.start, end: $0.end) }
            return DayHours(weekday: weekday, open: !ranges.isEmpty, ranges: ranges)
        }
    }

    /// Action setDay. A day turned on starts with 09:00 to 18:00.
    func setDay(_ weekday: Int, open: Bool) {
        guard let index = days.firstIndex(where: { $0.weekday == weekday }) else { return }
        days[index].open = open
        if open && days[index].ranges.isEmpty {
            days[index].ranges = [ClockRange(start: "09:00", end: "18:00")]
        }
    }

    /// Action addRange: a new range after the last one, for a lunch break.
    func addRange(_ weekday: Int) {
        guard let index = days.firstIndex(where: { $0.weekday == weekday }) else { return }
        let lastEnd = days[index].ranges.map { Self.minutes($0.end) }.max() ?? 8 * 60
        let start = min(lastEnd + 60, 23 * 60)
        let end = min(start + 180, 23 * 60 + 59)
        guard end > start else { return }
        days[index].ranges.append(ClockRange(start: Self.clock(start), end: Self.clock(end)))
    }

    func removeRanges(_ weekday: Int, at offsets: IndexSet) {
        guard let index = days.firstIndex(where: { $0.weekday == weekday }) else { return }
        days[index].ranges.remove(atOffsets: offsets)
        if days[index].ranges.isEmpty { days[index].open = false }
    }

    /// Ranges of a day must end after they start and must not overlap.
    var hoursAreValid: Bool {
        days.filter(\.open).allSatisfy { day in
            let sorted = day.ranges.map { (Self.minutes($0.start), Self.minutes($0.end)) }.sorted { $0.0 < $1.0 }
            return sorted.indices.allSatisfy { index in
                sorted[index].1 > sorted[index].0 && (index == 0 || sorted[index].0 >= sorted[index - 1].1)
            }
        }
    }

    /// Action saveHours: PUT .../availability with the rules, then the people step.
    func saveHours() async {
        guard let agendaId = hoursAgendaId else { return }
        saving = true
        error = nil
        defer { saving = false }
        let rules = days.filter(\.open).flatMap { day in
            day.ranges.map { BipeAvailabilityRule(weekday: day.weekday, start: $0.start, end: $0.end) }
        }
        do {
            _ = try await api.setAvailability(agendaId: agendaId, rules: rules)
        } catch {
            self.error = BipeStrings.agendaErrorSave
            return
        }
        step = .people
    }

    /// Action addPerson. The person needs no Bipe account (ADR 0099).
    func addPerson(_ name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        saving = true
        defer { saving = false }
        do {
            _ = try await api.createAgenda(BipeAgendaInput(name: trimmed, sort: state?.agendas.count ?? 0))
        } catch {
            self.error = BipeStrings.agendaErrorSave
        }
        await refreshState()
    }

    /// Action renamePerson.
    func rename(_ agenda: BipeAgenda, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != agenda.name else { return }
        do {
            _ = try await api.replaceAgenda(id: agenda.id, BipeAgendaInput(name: trimmed, active: agenda.active, sort: agenda.sort))
        } catch {
            self.error = BipeStrings.agendaErrorSave
        }
        await refreshState()
    }

    /// Action removePerson. Bookings keep it.
    func removePerson(_ agenda: BipeAgenda) async {
        do {
            try await api.deleteAgenda(id: agenda.id)
        } catch {
            self.error = BipeStrings.agendaErrorSave
        }
        await refreshState()
        if hoursAgendaId == agenda.id { hoursAgendaId = state?.agendas.first?.id }
    }

    /// Action publish: PUT /v1/me/business/agenda with published true. true when published.
    func publish() async -> Bool {
        guard let state else { return false }
        saving = true
        error = nil
        defer { saving = false }
        do {
            self.state = try await api.saveAgendaSettings(published: true, timeZone: state.timeZone)
            return true
        } catch {
            self.error = (error as? BipeAPIError)?.isPublishIncomplete == true ? BipeStrings.agendaPublishIncomplete : BipeStrings.agendaErrorSave
            return false
        }
    }

    func next() {
        error = nil
        if let following = Step(rawValue: step.rawValue + 1) { step = following }
    }

    /// Action back.
    func back() {
        error = nil
        guard canGoBack, let previous = Step(rawValue: step.rawValue - 1) else { return }
        // The template step runs once: activation does nothing the second time.
        step = previous == .template ? firstStep : previous
    }

    static func minutes(_ clock: String) -> Int {
        let parts = clock.split(separator: ":").compactMap { Int($0) }
        return parts.count == 2 ? parts[0] * 60 + parts[1] : 0
    }

    static func clock(_ minutes: Int) -> String {
        String(format: "%02ld:%02ld", minutes / 60, minutes % 60)
    }
}

struct AgendaSetupView: View {
    @StateObject private var model: AgendaSetupModel
    /// Closes the setup; the text is the result to show (bipe_agenda_setup_done), or nil.
    private let close: (String?) -> Void
    @State private var newPerson = ""
    @State private var names: [String: String] = [:]
    @State private var editing: CatalogEditTarget?
    @State private var removing: BipeAgenda?

    init(api: BipeBusinessAPI, startsAtHours: Bool, close: @escaping (String?) -> Void) {
        _model = StateObject(wrappedValue: AgendaSetupModel(api: api, startsAtHours: startsAtHours))
        self.close = close
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                switch model.step {
                case .template: templateStep
                case .hours: hoursStep
                case .people: peopleStep
                case .services: servicesStep
                case .publish: publishStep
                }
            }
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
            .sheet(item: $editing) { target in
                CatalogItemEditView(api: model.api, item: target.item,
                                    nextSort: (model.state?.services.map(\.sort).max() ?? -1) + 1,
                                    agenda: model.state,
                                    close: { _ in
                                        editing = nil
                                        Task { await model.refreshState() }
                                    })
            }
            .alert(BipeStrings.agendaRemovePerson, isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
                Button(BipeStrings.agendaRemovePerson, role: .destructive) {
                    if let agenda = removing { Task { await model.removePerson(agenda) } }
                    removing = nil
                }
                Button(BipeStrings.appCancel, role: .cancel) { removing = nil }
            } message: {
                Text(removing?.name ?? "")
            }
        }
        .accessibilityIdentifier(BipeScreen.agendaSetup.rawValue)
        .task { await model.appear() }
    }

    private var title: String {
        switch model.step {
        case .template: BipeStrings.agendaActivate
        case .hours: BipeStrings.agendaSetupHours
        case .people: BipeStrings.agendaSetupPeople
        case .services: BipeStrings.agendaSetupReview
        case .publish: BipeStrings.agendaPublish
        }
    }

    @ViewBuilder
    private var errorFooter: some View {
        if let error = model.error {
            Text(error).foregroundStyle(BipeColor.trustDangerInk)
        }
    }

    private func continueButton(disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Spacer()
                if model.saving { ProgressView() } else { Text(BipeStrings.actionContinue).bold() }
                Spacer()
            }
        }
        .disabled(disabled || model.saving)
        .listRowBackground(BipeColor.accentWash)
    }

    // MARK: Steps

    private var templateStep: some View {
        List {
            Section {
                ForEach(BipeAgendaTemplates.templates, id: \.id) { template in
                    Button {
                        Task { await model.pickTemplate(template.id) }
                    } label: {
                        Text(BipeLocalized.string(template.titleKey)).foregroundStyle(BipeColor.inkPrimary)
                    }
                    .disabled(model.saving)
                }
            } header: {
                Text(BipeStrings.agendaPickTemplate)
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(BipeStrings.agendaTemplateHint)
                    errorFooter
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    private var hoursStep: some View {
        List {
            if let agendas = model.state?.agendas, agendas.count > 1 {
                Section {
                    Picker(BipeStrings.agendaPerson, selection: Binding(get: { model.hoursAgendaId ?? "" },
                                                                         set: { id in Task { await model.loadHours(agendaId: id) } })) {
                        ForEach(agendas) { agenda in Text(agenda.name).tag(agenda.id) }
                    }
                }
            }
            ForEach($model.days) { $day in
                Section {
                    Toggle(isOn: Binding(get: { day.open }, set: { model.setDay(day.weekday, open: $0) })) {
                        Text(Self.weekdayName(day.weekday))
                    }
                    if day.open {
                        ForEach($day.ranges) { $range in
                            HStack {
                                ClockPicker(clock: $range.start)
                                Text(verbatim: "–").foregroundStyle(BipeColor.inkSecondary)
                                ClockPicker(clock: $range.end)
                            }
                        }
                        .onDelete { model.removeRanges(day.weekday, at: $0) }
                        Button(BipeStrings.agendaAddRange) { model.addRange(day.weekday) }
                    } else {
                        Text(BipeStrings.agendaClosed).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            Section {
                continueButton(disabled: !model.hoursAreValid) {
                    Task { await model.saveHours() }
                }
            } footer: {
                errorFooter
            }
        }
        .listStyle(.insetGrouped)
    }

    private var peopleStep: some View {
        List {
            Section {
                ForEach(model.state?.agendas ?? []) { agenda in
                    TextField(BipeStrings.agendaPersonName, text: Binding(get: { names[agenda.id] ?? agenda.name },
                                                                          set: { names[agenda.id] = $0 }))
                        .onSubmit { Task { await model.rename(agenda, to: names[agenda.id] ?? agenda.name) } }
                }
                .onDelete { offsets in
                    guard let agendas = model.state?.agendas, agendas.count > 1 else { return }
                    removing = offsets.first.map { agendas[$0] }
                }
            }
            Section {
                TextField(BipeStrings.agendaPersonName, text: $newPerson)
                Button(BipeStrings.agendaAddPerson) {
                    let name = newPerson
                    newPerson = ""
                    Task { await model.addPerson(name) }
                }
                .disabled(newPerson.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.saving)
            }
            Section {
                continueButton {
                    Task {
                        // Saves names typed but not submitted.
                        for agenda in model.state?.agendas ?? [] {
                            if let name = names[agenda.id] { await model.rename(agenda, to: name) }
                        }
                        names = [:]
                        model.next()
                    }
                }
            } footer: {
                errorFooter
            }
        }
        .listStyle(.insetGrouped)
    }

    private var servicesStep: some View {
        List {
            Section {
                ForEach(model.state?.services ?? []) { service in
                    Button { editing = CatalogEditTarget(item: service) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(service.name).foregroundStyle(BipeColor.inkPrimary)
                            Text([service.durationMinutes.map { BipeStrings.agendaDuration(String($0)) }, service.price.displayText]
                                .compactMap { $0 }.joined(separator: " · "))
                                .font(.subheadline)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .accessibilityLabel(BipeStrings.catalogItemOpenA11y(service.name))
                }
                Button(BipeStrings.agendaAddService) { editing = CatalogEditTarget(item: nil) }
            } footer: {
                Text(BipeStrings.agendaTemplateHint)
            }
            Section {
                continueButton { model.next() }
            } footer: {
                errorFooter
            }
        }
        .listStyle(.insetGrouped)
    }

    private var publishStep: some View {
        List {
            Section {
                Text(BipeStrings.agendaTabOffBody).foregroundStyle(BipeColor.inkPrimary)
            }
            Section {
                Button {
                    Task {
                        if await model.publish() { close(BipeStrings.agendaSetupDone) }
                    }
                } label: {
                    HStack {
                        Spacer()
                        if model.saving { ProgressView() } else { Text(BipeStrings.agendaPublish).bold() }
                        Spacer()
                    }
                }
                .disabled(model.saving)
                .listRowBackground(BipeColor.accentWash)
            } footer: {
                errorFooter
            }
        }
        .listStyle(.insetGrouped)
    }

    /// The weekday's name in the phone's language, ISO Monday 1 to Sunday 7.
    static func weekdayName(_ weekday: Int) -> String {
        let symbols = Calendar.current.standaloneWeekdaySymbols
        // Calendar symbols start on Sunday.
        return symbols[weekday % 7].capitalized(with: Locale.current)
    }
}

/// A local wall time `HH:MM`, picked with the system time picker. The wall time has no time zone of its own.
private struct ClockPicker: View {
    @Binding var clock: String

    private static let utc = TimeZone(identifier: "UTC")!

    var body: some View {
        DatePicker(BipeStrings.agendaTime, selection: Binding(get: { date }, set: { clock = Self.clock(of: $0) }),
                   displayedComponents: [.hourAndMinute])
            .labelsHidden()
            .environment(\.timeZone, Self.utc)
    }

    private var date: Date {
        Date(timeIntervalSince1970: TimeInterval(AgendaSetupModel.minutes(clock) * 60))
    }

    private static func clock(of date: Date) -> String {
        let parts = BusinessDay.calendar(utc).dateComponents([.hour, .minute], from: date)
        return AgendaSetupModel.clock((parts.hour ?? 0) * 60 + (parts.minute ?? 0))
    }
}
