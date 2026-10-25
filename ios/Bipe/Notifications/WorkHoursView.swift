// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec work-hours (ADR 0138 rule 4, bipe-chat/bipe#511): the weekly opening hours of a work account.
@MainActor
final class WorkHoursModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case shown
        case saving
        case failed
    }

    @Published private(set) var hours: BipeWorkHours = .defaults
    @Published private(set) var phase: Phase = .loading

    private let session: AccountSession
    private let api: BipeAPIClient
    private let onSaved: (BipeWorkHours) -> Void

    init(session: AccountSession, api: BipeAPIClient, onSaved: @escaping (BipeWorkHours) -> Void) {
        self.session = session
        self.api = api
        self.onSaved = onSaved
    }

    var openNow: Bool { hours.isOpen(at: .now) }

    /// Action appear.
    func appear() async {
        if let read = await WorkHoursSync.refresh(session: session, api: api) {
            hours = read
        }
        phase = .shown
    }

    /// Action setEnabled.
    func setEnabled(_ isOn: Bool) async {
        var next = hours
        next.enabled = isOn
        await save(next)
    }

    /// The first range of a weekday, or `nil` when closed.
    func range(of day: Int) -> BipeWorkHours.Range? {
        hours.days.first { $0.day == day }?.ranges.first
    }

    /// Action setDay: open or closed, with one range. A close time not after the open time is refused here.
    func setDay(_ day: Int, range: BipeWorkHours.Range?) async {
        if let range {
            guard let start = BipeWorkHours.minutes(range.start), let end = BipeWorkHours.minutes(range.end), end > start else {
                phase = .failed
                return
            }
        }
        var next = hours
        next.days.removeAll { $0.day == day }
        if let range {
            next.days.append(.init(day: day, ranges: [range]))
        }
        next.days.sort { $0.day < $1.day }
        await save(next)
    }

    private func save(_ next: BipeWorkHours) async {
        let old = hours
        hours = next
        phase = .saving
        let api = api
        do {
            let saved = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeWorkHours in
                try await api.setWorkHours(next, accessToken: token)
            }
            hours = saved
            WorkHoursSync.store(saved, userId: session.userId)
            onSaved(saved)
            phase = .shown
        } catch {
            hours = old
            phase = .failed
        }
    }
}

struct WorkHoursView: View {
    @StateObject private var model: WorkHoursModel

    init(session: AccountSession, api: BipeAPIClient, onSaved: @escaping (BipeWorkHours) -> Void) {
        _model = StateObject(wrappedValue: WorkHoursModel(session: session, api: api, onSaved: onSaved))
    }

    var body: some View {
        List {
            Section {
                Toggle(isOn: Binding(get: { model.hours.enabled }, set: { isOn in Task { await model.setEnabled(isOn) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(BipeStrings.workHoursEnabled)
                        if model.hours.enabled {
                            Text(model.openNow ? BipeStrings.workHoursOpenNow : BipeStrings.workHoursClosedNow)
                                .font(.footnote)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                }
            } footer: {
                Text(BipeStrings.workHoursBody)
            }
            Section {
                ForEach(1...7, id: \.self) { day in
                    WorkDayRow(name: Self.weekdayName(day), range: model.range(of: day)) { range in
                        Task { await model.setDay(day, range: range) }
                    }
                }
            }
            .disabled(model.phase == .loading || model.phase == .saving)
            if model.phase == .failed {
                Section {
                    Text(BipeStrings.workHoursSaveError)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
        .tint(BipeColor.accentFill)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.workHoursTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.workHours.rawValue)
        .task { await model.appear() }
    }

    /// The weekday name of the system calendar in the app language. ISO day 1 is Monday.
    static func weekdayName(_ isoDay: Int) -> String {
        let symbols = Calendar.current.standaloneWeekdaySymbols
        return symbols[isoDay % 7].capitalized(with: Locale.current)
    }
}

/// One weekday: open with its hours, or closed.
private struct WorkDayRow: View {
    let name: String
    let range: BipeWorkHours.Range?
    let onChange: (BipeWorkHours.Range?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(get: { range != nil }, set: { isOn in
                onChange(isOn ? BipeWorkHours.Range(start: "08:00", end: "18:00") : nil)
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                    Text(range.map { BipeStrings.workHoursRange($0.start, $0.end) } ?? BipeStrings.workHoursClosed)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            if let range {
                DatePicker(BipeStrings.workHoursOpens, selection: time(range.start) { onChange(.init(start: $0, end: range.end)) },
                           displayedComponents: .hourAndMinute)
                DatePicker(BipeStrings.workHoursCloses, selection: time(range.end) { onChange(.init(start: range.start, end: $0)) },
                           displayedComponents: .hourAndMinute)
            }
        }
    }

    /// A time picker over HH:MM. 24:00 shows as 00:00 and stays midnight until it changes.
    private func time(_ hhmm: String, set: @escaping (String) -> Void) -> Binding<Date> {
        Binding(get: {
            let minutes = (BipeWorkHours.minutes(hhmm) ?? 0) % (24 * 60)
            return Calendar.current.startOfDay(for: .now).addingTimeInterval(TimeInterval(minutes * 60))
        }, set: { date in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            set(String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0))
        })
    }
}
