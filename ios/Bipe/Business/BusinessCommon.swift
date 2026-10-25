// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Phosphor
import SwiftUI

// Shared pieces of the business tools (Agenda and Catálogo, ADRs 0099 to 0104).

extension AccountSession {
    /// The business routes of bipe-api with this account's token, read again on each call.
    @MainActor func business(_ api: BipeAPIClient) -> BipeBusinessAPI {
        BipeBusinessAPI(api: api) { [weak self] in self?.accessToken() }
    }
}

/// A customer the owner picks for an encaixe: the other person of one of the company's DMs.
struct BusinessCustomer: Identifiable, Hashable {
    var userId: String
    var name: String
    var id: String { userId }

    /// The company's DM partners, from its chat list.
    @MainActor static func fromChats(_ chats: [ChatSummary]) -> [BusinessCustomer] {
        var seen = Set<String>()
        return chats.compactMap { chat in
            guard chat.isDm, let userId = chat.peerUserId, seen.insert(userId).inserted else { return nil }
            return BusinessCustomer(userId: userId, name: chat.name)
        }
    }
}

/// Local dates of a business, `yyyy-MM-dd` in its time zone (SlotDays.time_zone, AgendaState.time_zone).
/// Times on screen use the phone's time zone and locale; days follow the business (spec agenda-book notes).
enum BusinessDay {
    static func timeZone(_ identifier: String?) -> TimeZone {
        identifier.flatMap(TimeZone.init(identifier:)) ?? .current
    }

    static func calendar(_ timeZone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    /// The local date of an instant in the time zone.
    static func string(_ date: Date, in timeZone: TimeZone) -> String {
        let parts = calendar(timeZone).dateComponents([.year, .month, .day], from: date)
        return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// Midnight of the local date in the time zone.
    static func start(of day: String, in timeZone: TimeZone) -> Date? {
        let numbers = day.split(separator: "-").compactMap { Int($0) }
        guard numbers.count == 3 else { return nil }
        return calendar(timeZone).date(from: DateComponents(year: numbers[0], month: numbers[1], day: numbers[2]))
    }

    /// The date `days` later (or earlier, when negative).
    static func adding(_ days: Int, to day: String) -> String {
        let utc = TimeZone(identifier: "UTC")!
        guard let date = start(of: day, in: utc), let moved = calendar(utc).date(byAdding: .day, value: days, to: date) else { return day }
        return string(moved, in: utc)
    }

    /// `sáb., 25 de out.` in the phone's locale, for the business's date.
    static func title(_ day: String, in timeZone: TimeZone) -> String {
        guard let date = start(of: day, in: timeZone) else { return day }
        var style = Date.FormatStyle.dateTime.weekday(.abbreviated).day().month(.abbreviated)
        style.timeZone = timeZone
        return date.formatted(style)
    }

    /// The weekday initial and the day number of a week strip cell.
    static func stripParts(_ day: String, in timeZone: TimeZone) -> (weekday: String, number: String) {
        guard let date = start(of: day, in: timeZone) else { return ("", day) }
        var weekday = Date.FormatStyle.dateTime.weekday(.abbreviated)
        weekday.timeZone = timeZone
        var number = Date.FormatStyle.dateTime.day()
        number.timeZone = timeZone
        return (date.formatted(weekday), date.formatted(number))
    }
}

/// How bookings read on screen.
enum BookingText {
    /// The start time in the phone's time zone and locale.
    static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }

    /// `sáb., 25 de out. · 14:00`, in the phone's time zone.
    static func when(_ booking: BipeBooking) -> String {
        booking.start.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)) + " · " + time(booking.start)
    }

    /// A span of minutes in words: `2 horas`, `30 minutos`.
    static func minutes(_ minutes: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = minutes % 60 == 0 && minutes >= 60 ? [.hour] : [.hour, .minute]
        formatter.unitsStyle = .full
        return formatter.string(from: TimeInterval(minutes * 60)) ?? String(minutes)
    }
}

extension BipeBookingStatus {
    /// The status line of agenda-booking-card and agenda-owner-booking.
    var text: String {
        switch self {
        case .held: BipeStrings.agendaStatusHeld
        case .requested: BipeStrings.agendaStatusRequested
        case .confirmed: BipeStrings.agendaStatusConfirmed
        case .done: BipeStrings.agendaStatusDone
        case .cancelled: BipeStrings.agendaStatusCancelled
        case .noShow: BipeStrings.agendaStatusNoShow
        }
    }

    var tint: Color {
        switch self {
        case .confirmed, .done: BipeColor.trustVerifiedInk
        case .held, .requested: BipeColor.trustCautionInk
        case .cancelled, .noShow: BipeColor.inkSecondary
        }
    }
}

/// A file for the system share sheet.
struct SharedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// agenda-booking-card addToCalendar: a local .ics file for the system calendar. Nothing goes to the server.
enum BookingCalendarFile {
    static func write(_ booking: BipeBooking, serviceName: String, businessName: String) -> SharedFile? {
        let title = BipeStrings.agendaCalendarTitle(serviceName, businessName)
        let ics = BipeBookingCalendar.ics(uid: booking.id, start: booking.start, end: booking.end, title: title)
        let name = booking.id.filter { $0.isLetter || $0.isNumber || $0 == "-" }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bipe-\(name.isEmpty ? "booking" : name).ics")
        do {
            try Data(ics.utf8).write(to: url, options: .atomic)
        } catch {
            return nil
        }
        return SharedFile(url: url)
    }
}

/// A photo of a catalogue item from its public URL, or a plain square while it loads.
struct CatalogPhoto: View {
    let url: URL?
    var side: CGFloat = 56

    var body: some View {
        ZStack {
            BipeColor.surfaceSunken
            if let url {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
            } else {
                PhosphorIcon.image.image
                    .resizable()
                    .frame(width: side * 0.4, height: side * 0.4)
                    .foregroundStyle(BipeColor.glyphSecondary)
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - Pedidos, Orçamentos and Fila virtual (ADRs 0105 to 0107)

/// What the owner screens of Pedidos, Orçamentos and Fila virtual need besides the API: names of customers, the
/// company's DM partners for the pickers, and the DM with a customer.
struct BusinessOwnerContext {
    let api: BipeBusinessAPI
    let people: PeopleService
    /// The company's DM partners: an owner-entered order or a walk-in can name one.
    let customers: [BusinessCustomer]
    /// Opens the DM with the signed-in customer (action openChat); nil inside that DM.
    let openChat: ((String) -> Void)?

    /// The customer's name: the DM name, else the profile name, else the ID's localpart. Never the Matrix ID.
    @MainActor func name(of userId: String) async -> String {
        if let known = customers.first(where: { $0.userId == userId }) { return known.name }
        return (try? await people.person(userId))?.displayName ?? Person.localpart(of: userId)
    }

    /// The names of these signed-in customers that `known` does not have yet.
    @MainActor func names(for userIds: [String], known: [String: String]) async -> [String: String] {
        var names = known
        for userId in Set(userIds) where names[userId] == nil {
            names[userId] = await name(of: userId)
        }
        return names
    }
}

/// How the records of Pedidos, Orçamentos and Fila virtual read on screen. Amounts are BRL centavos from the API.
enum BusinessText {
    static func money(_ cents: Int) -> String {
        BipeMoney.text(cents: cents)
    }

    /// A quantity with up to two decimals in the phone's locale, without grouping: `2`, `2,5`.
    static func quantity(_ quantity: Double) -> String {
        quantity.formatted(.number.precision(.fractionLength(0...2)).grouping(.never))
    }

    /// bipe_order_line: `2 × Pão de queijo`.
    static func line(_ line: BipeOrderLine) -> String {
        BipeStrings.orderLine(quantity(line.quantity), line.name)
    }

    /// bipe_queue_wait with bipe_queue_wait_range, or bipe_queue_wait_unknown: never zero (spec queue-customer notes).
    static func wait(_ wait: BipeQueueWait?) -> String {
        guard let wait else { return BipeStrings.queueWaitUnknown }
        return BipeStrings.queueWait(BipeStrings.queueWaitRange(String(wait.minMinutes), String(wait.maxMinutes)))
    }

    /// The date and time of a record in the phone's time zone: `sáb., 25 de out. · 14:00`.
    static func when(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated)) + " · " + BookingText.time(date)
    }

    /// The customer of an order or a place: the typed name, or the signed-in customer's name.
    static func customer(_ customer: BipeBookingCustomer, names: [String: String]) -> String {
        customer.name ?? customer.userId.map { names[$0] ?? Person.localpart(of: $0) } ?? ""
    }
}

extension BipeFulfilment {
    var text: String {
        switch self {
        case .pickup: BipeStrings.orderPickup
        case .delivery: BipeStrings.orderDelivery
        }
    }
}

extension BipeOrderStatus {
    var text: String {
        switch self {
        case .sent: BipeStrings.orderStatusSent
        case .accepted: BipeStrings.orderStatusAccepted
        case .refused: BipeStrings.orderStatusRefused
        case .ready: BipeStrings.orderStatusReady
        case .done: BipeStrings.orderStatusDone
        case .cancelled: BipeStrings.orderStatusCancelled
        }
    }

    var tint: Color {
        switch self {
        case .accepted, .ready, .done: BipeColor.trustVerifiedInk
        case .sent: BipeColor.trustCautionInk
        case .refused, .cancelled: BipeColor.inkSecondary
        }
    }
}

extension BipeOrderRefuseReason {
    var text: String {
        switch self {
        case .unavailable: BipeStrings.orderRefuseUnavailable
        case .closed: BipeStrings.orderRefuseClosed
        case .cannotDeliver: BipeStrings.orderRefuseCannotDeliver
        case .cannotFulfil: BipeStrings.orderRefuseCannotFulfil
        }
    }
}

extension BipeQuoteStatus {
    var text: String {
        switch self {
        case .offered: BipeStrings.quoteStatusOffered
        case .accepted: BipeStrings.quoteStatusAccepted
        case .refused: BipeStrings.quoteStatusRefused
        case .expired: BipeStrings.quoteStatusExpired
        }
    }

    var tint: Color {
        switch self {
        case .accepted: BipeColor.trustVerifiedInk
        case .offered: BipeColor.trustCautionInk
        case .refused, .expired: BipeColor.inkSecondary
        }
    }
}

extension BipeQueuePlaceStatus {
    var text: String {
        switch self {
        case .waiting: BipeStrings.queueStatusWaiting
        case .called: BipeStrings.queueCalled
        case .served: BipeStrings.queueStatusServed
        case .noShow: BipeStrings.queueStatusNoShow
        case .left: BipeStrings.queueStatusLeft
        }
    }

    var tint: Color {
        switch self {
        case .called: BipeColor.trustVerifiedInk
        case .waiting: BipeColor.trustCautionInk
        case .served, .noShow, .left: BipeColor.inkSecondary
        }
    }
}

/// The frame of the cards pinned above the timeline (agenda-booking-card, order, quote and queue cards).
struct BusinessCardFrame: ViewModifier {
    let screen: BipeScreen

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(BipeColor.lineSubtle))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(screen.rawValue)
    }
}

extension View {
    func businessCard(_ screen: BipeScreen) -> some View {
        modifier(BusinessCardFrame(screen: screen))
    }
}

/// A status line: the text in its tint. The state is in the words, never in the colour alone.
struct BusinessStatusLine: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(tint)
    }
}

/// A local wall time `HH:MM`, picked with the system time picker. The wall time has no time zone of its own.
struct BusinessClockPicker: View {
    let title: String
    @Binding var clock: String

    private static let utc = TimeZone(identifier: "UTC")!

    var body: some View {
        DatePicker(title, selection: Binding(get: { date }, set: { clock = Self.clock(of: $0) }), displayedComponents: [.hourAndMinute])
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

/// A reload every 30 seconds while the view shows (spec queue-customer and queue-owner load and refresh).
struct BusinessRefreshLoop: ViewModifier {
    let active: Bool
    let reload: () async -> Void

    func body(content: Content) -> some View {
        content.task(id: active) {
            guard active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                if Task.isCancelled { return }
                await reload()
            }
        }
    }
}

extension View {
    func refreshEvery30Seconds(while active: Bool, _ reload: @escaping () async -> Void) -> some View {
        modifier(BusinessRefreshLoop(active: active, reload: reload))
    }
}
