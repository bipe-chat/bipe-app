// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit

/// Spec year-recap (ADR 0223): the numbers of one calendar year, counted on this phone only.
struct YearRecap: Equatable {
    var year: Int
    var messagesSent = 0
    var activeDays = 0
    var people = 0
    var topChatName: String?
    var topReaction: String?
    var longestVoice: TimeInterval?
    /// Own messages a month (January first), a local hour (0 first) and a weekday (Sunday first).
    var byMonth = [Int](repeating: 0, count: 12)
    var byHour = [Int](repeating: 0, count: 24)
    var byWeekday = [Int](repeating: 0, count: 7)
    var isPartial = false

    /// Spec year-recap notes: the current year; in January, the year before.
    static func year(now: Date = Date(), calendar: Calendar = .current) -> Int {
        let year = calendar.component(.year, from: now)
        return calendar.component(.month, from: now) == 1 ? year - 1 : year
    }

    /// Spec year-recap messagesByHour: the busiest block of six hours.
    var peakPeriod: String {
        let blocks = stride(from: 0, to: 24, by: 6).map { byHour[$0..<($0 + 6)].reduce(0, +) }
        switch blocks.firstIndex(of: blocks.max() ?? 0) {
        case 0: return BipeStrings.yearRecapPeakDawn
        case 1: return BipeStrings.yearRecapPeakMorning
        case 2: return BipeStrings.yearRecapPeakAfternoon
        default: return BipeStrings.yearRecapPeakEvening
        }
    }
}

/// Counts own events of one year, room by room. Only own actions count (ADR 0223).
struct YearRecapTally {
    let ownUserId: String
    let start: UInt64
    let end: UInt64
    private let calendar: Calendar
    private var messages = 0
    private var days = Set<Int>()
    private var dms = 0
    private var perRoom: [String: Int] = [:]
    private var reactions: [String: (count: Int, newest: UInt64)] = [:]
    private var longestVoice: TimeInterval?
    private var byMonth = [Int](repeating: 0, count: 12)
    private var byHour = [Int](repeating: 0, count: 24)
    private var byWeekday = [Int](repeating: 0, count: 7)
    private(set) var isPartial = false

    init(ownUserId: String, year: Int, calendar: Calendar = .current) {
        self.ownUserId = ownUserId
        self.calendar = calendar
        let first = calendar.date(from: DateComponents(year: year, month: 1, day: 1)) ?? Date()
        let next = calendar.date(from: DateComponents(year: year + 1, month: 1, day: 1)) ?? Date()
        start = UInt64(max(0, first.timeIntervalSince1970 * 1000))
        end = UInt64(max(0, next.timeIntervalSince1970 * 1000))
    }

    private func inYear(_ timestamp: UInt64) -> Bool { timestamp >= start && timestamp < end }

    mutating func markPartial() { isPartial = true }

    mutating func add(roomId: String, isDm: Bool, events: [EventTimelineItem]) {
        var sent = 0
        for event in events {
            guard case .msgLike(let content) = event.content else { continue }
            // Own reactions count by their own time, also on an older message.
            for reaction in content.reactions {
                for sender in reaction.senders where sender.senderId == ownUserId && inYear(sender.timestamp) {
                    let old = reactions[reaction.key]
                    reactions[reaction.key] = ((old?.count ?? 0) + 1, max(old?.newest ?? 0, sender.timestamp))
                }
            }
            guard inYear(event.timestamp) else { continue }
            if case .unableToDecrypt = content.kind { isPartial = true }
            guard event.isOwn else { continue }
            if case .redacted = content.kind { continue }
            sent += 1
            let date = Date(milliseconds: event.timestamp)
            days.insert(Int(calendar.startOfDay(for: date).timeIntervalSince1970))
            let parts = calendar.dateComponents([.month, .hour, .weekday], from: date)
            if let month = parts.month, let hour = parts.hour, let weekday = parts.weekday {
                byMonth[month - 1] += 1
                byHour[hour] += 1
                byWeekday[weekday - 1] += 1
            }
            if case .message(let message) = content.kind, case .audio(let audio) = message.msgType, audio.voice != nil,
               let duration = audio.audio?.duration ?? audio.info?.duration {
                longestVoice = max(longestVoice ?? 0, duration)
            }
        }
        guard sent > 0 else { return }
        messages += sent
        perRoom[roomId, default: 0] += sent
        if isDm { dms += 1 }
    }

    /// The recap; chatName names the busiest chat with the chat-list row name.
    func recap(year: Int, chatName: (String) -> String?) -> YearRecap {
        let topRoom = perRoom.max { $0.value < $1.value }?.key
        let topReaction = reactions.max { ($0.value.count, $0.value.newest) < ($1.value.count, $1.value.newest) }?.key
        return YearRecap(year: year, messagesSent: messages, activeDays: days.count, people: dms,
                         topChatName: topRoom.flatMap(chatName), topReaction: topReaction,
                         longestVoice: longestVoice, byMonth: byMonth, byHour: byHour, byWeekday: byWeekday,
                         isPartial: isPartial)
    }
}

/// Spec year-recap: reads every joined chat once while the screen is open. Keeps nothing on disk.
@MainActor
final class YearRecapModel: ObservableObject {
    enum Phase: Equatable { case reading, ready, empty }

    /// Spec year-recap appear: 50 events a page, at most 40 pages a chat.
    static let page: UInt16 = 50
    static let maxPages = 40

    @Published private(set) var phase: Phase = .reading
    @Published private(set) var roomsDone = 0
    @Published private(set) var roomsTotal = 0
    @Published private(set) var recap: YearRecap?

    private let chatList: ChatListModel
    let year: Int

    init(chatList: ChatListModel, year: Int = YearRecap.year()) {
        self.chatList = chatList
        self.year = year
    }

    /// Action appear. Cancelled by close (the view's task ends).
    func appear() async {
        guard recap == nil else { return }
        let session = chatList.session
        let rooms = session.client.rooms().filter { $0.membership() == .joined }
        roomsTotal = rooms.count
        var tally = YearRecapTally(ownUserId: session.userId, year: year)
        for room in rooms {
            guard !Task.isCancelled else { return }
            await read(room, into: &tally)
            roomsDone += 1
        }
        let names = Dictionary(chatList.chats.map { ($0.roomId, $0.name) }, uniquingKeysWith: { first, _ in first })
        let recap = tally.recap(year: year) { names[$0] }
        self.recap = recap
        phase = recap.messagesSent == 0 ? .empty : .ready
    }

    private func read(_ room: Room, into tally: inout YearRecapTally) async {
        guard let timeline = try? await room.timeline() else {
            tally.markPartial()
            return
        }
        let isDm = (try? await room.roomInfo())?.isDm ?? false
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        let box = ItemsBox()
        let collector = Task { @MainActor in
            for await diffs in stream.updates {
                diffs.forEach { box.items.apply($0.vectorDiff) }
            }
        }
        defer { collector.cancel() }
        var reachedStart = false
        for _ in 0..<Self.maxPages {
            guard !Task.isCancelled else { return }
            guard let hitStart = try? await timeline.paginateBackwards(numEvents: Self.page) else {
                tally.markPartial()
                reachedStart = true
                break
            }
            // Diffs arrive on the listener after the page; give them a moment before the oldest time is read.
            try? await Task.sleep(for: .milliseconds(100))
            let oldest = box.items.lazy.compactMap { $0.asEvent()?.timestamp }.first
            if hitStart || (oldest.map { $0 < tally.start } ?? false) {
                reachedStart = true
                break
            }
        }
        if !reachedStart { tally.markPartial() }
        try? await Task.sleep(for: .milliseconds(200))
        tally.add(roomId: room.id(), isDm: isDm, events: box.items.compactMap { $0.asEvent() })
    }

    /// The timeline items of the room being read; the listener task fills it on the main actor.
    @MainActor private final class ItemsBox {
        var items: [TimelineItem] = []
    }
}

/// Spec year-recap.
struct YearRecapView: View {
    @StateObject private var model: YearRecapModel
    @State private var shareItems: YearRecapSharePayload?

    init(chatList: ChatListModel) {
        _model = StateObject(wrappedValue: YearRecapModel(chatList: chatList))
    }

    var body: some View {
        List {
            switch model.phase {
            case .reading:
                Section {
                    VStack(spacing: 12) {
                        ProgressView(value: Double(model.roomsDone), total: Double(max(model.roomsTotal, 1)))
                            .tint(BipeColor.accentFill)
                        Text(BipeStrings.yearRecapReading(model.roomsDone.formatted(), model.roomsTotal.formatted()))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    .padding(.vertical, 8)
                }
            case .empty:
                Section {
                    Text(BipeStrings.yearRecapEmpty(String(model.year)))
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            case .ready:
                if let recap = model.recap {
                    Section {
                        YearRecapCard(recap: recap, forShare: false)
                            .listRowInsets(EdgeInsets())
                    } footer: {
                        if recap.isPartial {
                            Text(BipeStrings.yearRecapPartial)
                        }
                    }
                    Section {
                        Button {
                            shareItems = YearRecapSharePayload(recap: recap)
                        } label: {
                            IconLabel(icon: .shareNetwork, text: BipeStrings.appShare, tint: BipeColor.accentInk)
                        }
                    } footer: {
                        Text(BipeStrings.yearRecapShareNote)
                    }
                }
            }
            Section {
                Text(BipeStrings.yearRecapOnDevice)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BipeStrings.yearRecapTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.yearRecap.rawValue)
        .task { await model.appear() }
        .sheet(item: $shareItems) { payload in
            PixShareSheet(items: payload.items)
        }
    }
}

/// The numbers. forShare leaves out the busiest chat: the card never names a person or a chat (ADR 0223).
struct YearRecapCard: View {
    let recap: YearRecap
    let forShare: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                PhosphorIcon.chartBar.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.yearRecapCardTitle(String(recap.year)))
                    .font(.headline)
                    .foregroundStyle(BipeColor.accentInk)
            }
            // Grid, not a lazy grid: ImageRenderer does not draw lazy containers.
            let tiles = numbers
            Grid(alignment: .topLeading, horizontalSpacing: 16, verticalSpacing: 16) {
                ForEach(Array(stride(from: 0, to: tiles.count, by: 2)), id: \.self) { index in
                    GridRow {
                        tile(tiles[index].value, tiles[index].label)
                        if index + 1 < tiles.count {
                            tile(tiles[index + 1].value, tiles[index + 1].label)
                        } else {
                            Color.clear.gridCellUnsizedAxes([.horizontal, .vertical])
                        }
                    }
                }
            }
            if !forShare, let chat = recap.topChatName {
                tile(chat, BipeStrings.yearRecapTopChat)
            }
            Text(recap.peakPeriod)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            ForEach(YearRecapChart.all(recap), id: \.title) { chart in
                YearRecapBars(chart: chart)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BipeColor.surfaceRaised)
    }

    /// Spec year-recap notes: a tile with no value does not show; 0 shows.
    private var numbers: [(value: String, label: String)] {
        var tiles = [(recap.messagesSent.formatted(), BipeStrings.yearRecapMessages),
                     (recap.activeDays.formatted(), BipeStrings.yearRecapDays),
                     (recap.people.formatted(), BipeStrings.yearRecapPeople)]
        if let reaction = recap.topReaction {
            tiles.append((reaction, BipeStrings.yearRecapTopReaction))
        }
        if let voice = recap.longestVoice {
            tiles.append((MediaFormat.duration(voice), BipeStrings.yearRecapLongestVoice))
        }
        return tiles.map { (value: $0.0, label: $0.1) }
    }

    private func tile(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title2.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(2)
            Text(label)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// One bar chart of the card: values, axis labels (every labelEvery bars) and the name of the peak.
struct YearRecapChart {
    let title: String
    let values: [Int]
    let labels: [String]
    let labelEvery: Int
    let peak: String

    /// Spec year-recap charts: by month, by hour, by weekday from the locale's first weekday. Ties go to the earliest.
    static func all(_ recap: YearRecap, calendar: Calendar = .current) -> [YearRecapChart] {
        func peakIndex(_ values: [Int]) -> Int { values.firstIndex(of: values.max() ?? 0) ?? 0 }
        let months = calendar.standaloneMonthSymbols
        let month = YearRecapChart(title: BipeStrings.yearRecapByMonth, values: recap.byMonth,
                                   labels: calendar.veryShortStandaloneMonthSymbols, labelEvery: 1,
                                   peak: months[peakIndex(recap.byMonth)])
        let peakHour = calendar.date(bySettingHour: peakIndex(recap.byHour), minute: 0, second: 0, of: Date()) ?? Date()
        let hour = YearRecapChart(title: BipeStrings.yearRecapByHour, values: recap.byHour,
                                  labels: (0..<24).map { String($0) }, labelEvery: 6,
                                  peak: peakHour.formatted(date: .omitted, time: .shortened))
        let order = (0..<7).map { ($0 + calendar.firstWeekday - 1) % 7 }
        let weekdayValues = order.map { recap.byWeekday[$0] }
        let weekday = YearRecapChart(title: BipeStrings.yearRecapByWeekday, values: weekdayValues,
                                     labels: order.map { calendar.veryShortStandaloneWeekdaySymbols[$0] }, labelEvery: 1,
                                     peak: calendar.standaloneWeekdaySymbols[order[peakIndex(weekdayValues)]])
        return [month, hour, weekday]
    }
}

/// Plain bars, no chart library; ImageRenderer draws them for the share image.
struct YearRecapBars: View {
    let chart: YearRecapChart
    private static let height: CGFloat = 64

    var body: some View {
        let top = chart.values.max() ?? 0
        let peak = chart.values.firstIndex(of: top)
        let spacing: CGFloat = chart.values.count > 12 ? 2 : 4
        VStack(alignment: .leading, spacing: 6) {
            Text(chart.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
            HStack(alignment: .bottom, spacing: spacing) {
                ForEach(chart.values.indices, id: \.self) { index in
                    let share = top == 0 ? 0 : CGFloat(chart.values[index]) / CGFloat(top)
                    RoundedRectangle(cornerRadius: 2)
                        .fill(index == peak ? BipeColor.accentFill : BipeColor.accentFill.opacity(0.3))
                        .frame(height: max(2, Self.height * share))
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(height: Self.height, alignment: .bottom)
            // One label for each group of labelEvery bars; the groups have the width of their bars.
            HStack(spacing: spacing) {
                ForEach(Array(stride(from: 0, to: chart.labels.count, by: chart.labelEvery)), id: \.self) { index in
                    Text(chart.labels[index])
                        .font(.caption2)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: chart.labelEvery == 1 ? .center : .leading)
                }
            }
            Text(BipeStrings.yearRecapChartPeak(chart.peak))
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(chart.title). \(BipeStrings.yearRecapChartPeak(chart.peak))")
    }
}

/// Action share: the card image on the light theme, numbers only.
struct YearRecapSharePayload: Identifiable {
    let id = UUID()
    let items: [Any]

    @MainActor init?(recap: YearRecap) {
        let renderer = ImageRenderer(content: YearRecapCard(recap: recap, forShare: true)
            .frame(width: 360)
            .environment(\.colorScheme, .light)
            .bipeRoundedType())
        renderer.scale = 3
        guard let image = renderer.uiImage else { return nil }
        items = [image]
    }
}
