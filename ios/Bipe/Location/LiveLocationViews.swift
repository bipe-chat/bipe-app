// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit

/// Type LiveShare of spec location-view: a live location row from MsgLikeKind.liveLocation.
struct LiveLocationMessage: Equatable {
    /// The newest beacon; nil until the first one.
    var location: LocationMessage?
    var endsAt: Date
    var isLiveFlag: Bool

    init(_ content: LiveLocationContent) {
        location = content.locations.last.flatMap { LocationMessage(geoUri: $0.geoUri, description: $0.description) }
        endsAt = Date(milliseconds: content.ts + content.timeoutMs)
        isLiveFlag = content.isLive
    }

    func isLive(at now: Date = Date()) -> Bool { isLiveFlag && now < endsAt }

    /// bipe_app_location_live_until with the end time, else bipe_app_location_live_ended.
    func statusLine(at now: Date = Date()) -> String {
        isLive(at: now)
            ? BipeStrings.appLocationLiveUntil(endsAt.formatted(date: .omitted, time: .shortened))
            : BipeStrings.appLocationLiveEnded
    }
}

/// Spec location-view, live row: the map follows the newest beacon; the status line checks the end each minute.
struct TimelineLiveLocationRow: View {
    let live: LiveLocationMessage
    let openFull: () -> Void

    private static let width: CGFloat = 260

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            VStack(alignment: .leading, spacing: 4) {
                Color.clear
                    .aspectRatio(4 / 3, contentMode: .fit)
                    .frame(idealWidth: Self.width, maxWidth: Self.width)
                    .overlay {
                        GeometryReader { geometry in
                            ZStack {
                                if let location = live.location {
                                    StaticMapView(centre: location.point, size: geometry.size)
                                    MapPin().opacity(live.isLive(at: context.date) ? 1 : 0.5)
                                } else {
                                    BipeColor.surfaceSunken
                                    Text(BipeStrings.appLocationLiveWaiting)
                                        .font(.footnote)
                                        .foregroundStyle(BipeColor.inkSecondary)
                                        .multilineTextAlignment(.center)
                                        .padding(12)
                                }
                            }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                    .onTapGesture { if live.location != nil { openFull() } }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(BipeStrings.appLocationLiveTitle)
                    .accessibilityAddTraits(.isButton)
                Text(BipeStrings.appLocationLiveTitle)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(live.statusLine(at: context.date))
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .accessibilityIdentifier(BipeScreen.locationView.rawValue)
    }
}

/// Spec live-location-banner: the bar above the timeline while this account shares its location in the chat.
struct LiveLocationChatBar: View {
    let roomId: String
    let userId: String
    @ObservedObject private var center = LiveLocationCenter.shared

    var body: some View {
        if let share = center.share(in: roomId, userId: userId) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    MapPin().scaleEffect(0.7)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(BipeStrings.appLocationLiveSharing)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(BipeStrings.appLocationLiveUntil(share.endsAt.formatted(date: .omitted, time: .shortened)))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    Button(BipeStrings.appLocationLiveStop) {
                        Task { await center.stop(roomId: roomId) }
                    }
                    .buttonStyle(.bipeBordered)
                }
                if center.isForegroundOnly {
                    Text(BipeStrings.appLocationLiveOpenOnly)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustCautionInk)
                    Button(BipeStrings.contactsOpenSettings) {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .font(.footnote.weight(.semibold))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(BipeColor.accentWash)
            .accessibilityIdentifier(BipeScreen.liveLocationBanner.rawValue)
        }
    }
}

/// Spec live-location-banner: the row above the chat list while this account shares; stop ends every share.
struct LiveLocationListRow: View {
    let userId: String
    @ObservedObject private var center = LiveLocationCenter.shared

    var body: some View {
        if !center.shares(of: userId).isEmpty {
            HStack(spacing: 8) {
                MapPin().scaleEffect(0.7)
                Text(BipeStrings.appLocationLiveSharing)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                Button(BipeStrings.appLocationLiveStop) {
                    Task { await center.stopAll(userId: userId) }
                }
                .buttonStyle(.bipeBordered)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(BipeColor.accentWash)
            .accessibilityIdentifier(BipeScreen.liveLocationBanner.rawValue)
        }
    }
}
