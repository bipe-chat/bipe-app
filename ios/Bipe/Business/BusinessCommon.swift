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
