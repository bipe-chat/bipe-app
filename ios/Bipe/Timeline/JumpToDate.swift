// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import SwiftUI

/// Spec chat-timeline dayFocus (bipe-chat/bipe#828): the event the day view opens on, and the start of the picked day.
struct DayFocus: Identifiable, Hashable {
    var eventId: String
    var day: Date

    var id: String { eventId }
}

/// The days of timeline rows, for the floating date and jumpToDate of spec chat-timeline.
enum TimelineDays {
    /// The date a row carries: the date separator's day, or the message's time.
    static func date(of row: TimelineRow) -> Date? {
        row.day ?? row.message?.sentAt
    }

    /// The day of the row at index: its own date, else the nearest date above it, else below it.
    static func day(at index: Int, in rows: [TimelineRow]) -> Date? {
        guard rows.indices.contains(index) else { return nil }
        for row in rows[...index].reversed() {
            if let date = date(of: row) { return date }
        }
        for row in rows[index...] {
            if let date = date(of: row) { return date }
        }
        return nil
    }

    /// The first row on or after the start of a day.
    static func firstRow(onOrAfter dayStart: Date, in rows: [TimelineRow]) -> TimelineRow? {
        rows.first { row in
            guard let date = date(of: row) else { return false }
            return Calendar.current.startOfDay(for: date) >= dayStart
        }
    }

    /// true when the loaded rows reach back before the start of the day, so the day is on screen already.
    static func reaches(_ dayStart: Date, in rows: [TimelineRow], reachedStart: Bool) -> Bool {
        if reachedStart { return true }
        guard let oldest = rows.lazy.compactMap(date(of:)).first else { return false }
        return oldest < dayStart
    }
}

/// Matrix client-server GET /_matrix/client/v1/rooms/{roomId}/timestamp_to_event (MSC3030): the first event at or
/// after a time. matrix-sdk-ffi has no call for it, so the app asks the homeserver with the session's token.
enum TimestampToEvent {
    struct Failed: Error {}

    private struct Response: Decodable {
        let eventId: String
        let originServerTs: UInt64

        enum CodingKeys: String, CodingKey {
            case eventId = "event_id"
            case originServerTs = "origin_server_ts"
        }
    }

    /// The first event at or after `time` (dir f), or nil when the room has none.
    static func first(client: Client, roomId: String, onOrAfter time: Date) async throws -> (eventId: String, sentAt: Date)? {
        let auth = try client.session()
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/@:!#?&"))
        let room = roomId.addingPercentEncoding(withAllowedCharacters: allowed) ?? roomId
        let base = auth.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let ts = UInt64(max(0, time.timeIntervalSince1970 * 1000))
        guard let url = URL(string: "\(base)/_matrix/client/v1/rooms/\(room)/timestamp_to_event?ts=\(ts)&dir=f") else {
            throw Failed()
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(auth.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        switch (response as? HTTPURLResponse)?.statusCode {
        case 200:
            let found = try JSONDecoder().decode(Response.self, from: data)
            return (found.eventId, Date(milliseconds: found.originServerTs))
        case 404:
            return nil
        default:
            throw Failed()
        }
    }
}

/// Spec chat-timeline floatingDate: the day of the top row while the list scrolls. A button in the main timeline.
struct FloatingDatePill: View {
    let day: Date
    /// nil in the day view and the thread view: text only.
    let pick: (() -> Void)?

    var body: some View {
        if let pick {
            Button(action: pick) { label }
                .buttonStyle(.plain)
                .accessibilityHint(BipeStrings.jumpToDateTitle)
        } else {
            label
        }
    }

    private var label: some View {
        Text(daySeparatorText(day))
            .font(.caption.bold())
            .foregroundStyle(BipeColor.inkPrimary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(BipeColor.surfaceRaised))
            .overlay(Capsule().stroke(BipeColor.lineSubtle))
            .frame(minHeight: BipeTapTarget.minimum)
            .contentShape(Rectangle())
    }
}

/// Spec chat-timeline jumpToDate: a calendar up to today. Picking a day jumps at once.
struct JumpToDateSheet: View {
    let pick: (Date) -> Void
    let cancel: () -> Void
    @State private var day = Date()

    var body: some View {
        NavigationStack {
            DatePicker(BipeStrings.jumpToDateTitle, selection: $day, in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.graphical)
                .labelsHidden()
                .padding()
                .navigationTitle(BipeStrings.jumpToDateTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appCancel, action: cancel)
                    }
                }
                .onChange(of: day) { _, picked in pick(picked) }
        }
        .presentationDetents([.medium, .large])
    }
}
