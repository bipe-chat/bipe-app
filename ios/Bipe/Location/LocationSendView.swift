// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreLocation
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit

/// One position from the system, with "when in use" access only (spec location-send action appear).
@MainActor
final class OnePosition: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum State: Equatable {
        case idle
        case locating
        case found(CLLocation)
        case denied
        case unavailable
    }

    @Published private(set) var state = State.idle
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// Asks for access the first time, then reads one position.
    func request() {
        switch manager.authorizationStatus {
        case .notDetermined:
            state = .locating
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            state = .denied
        default:
            state = .locating
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            guard self.state == .locating else { return }
            switch status {
            case .notDetermined:
                break
            case .denied, .restricted:
                self.state = .denied
            default:
                self.manager.requestLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        Task { @MainActor in self.state = .found(location) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let denied = (error as? CLError)?.code == .denied
        Task { @MainActor in
            if case .found = self.state { return }
            self.state = denied ? .denied : .unavailable
        }
    }
}

/// Spec location-send: send the current location, or drag the map and send the place under the pin.
struct LocationSendView: View {
    @ObservedObject var timeline: ChatTimelineModel
    let session: AccountSession
    let replyToEventId: String?
    let close: () -> Void

    @StateObject private var position = OnePosition()
    @State private var centre = MapPoint.brazil
    @State private var isAtOwnPosition = false
    @State private var centredOnce = false
    @State private var isSending = false
    @State private var sendFailed = false
    @State private var choosingDuration = false
    @State private var liveFailed = false
    @ObservedObject private var live = LiveLocationCenter.shared

    private var ownLocation: CLLocation? {
        if case .found(let location) = position.state { return location }
        return nil
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                DraggableMapView(centre: $centre, pinAt: nil, onMove: { isAtOwnPosition = false })
                    .overlay(alignment: .topTrailing) { myLocationButton.padding(12) }
                    .overlay(alignment: .top) { statusLine }
                bottomBar
            }
            .navigationTitle(BipeStrings.appLocationSendTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel, action: close)
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.locationSend.rawValue)
        .onAppear { position.request() }
        .onChange(of: position.state) { _, state in
            // The map follows the first position only; after a drag it stays where the person put it.
            guard case .found(let location) = state, !centredOnce else { return }
            centredOnce = true
            centre = MapPoint(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
            isAtOwnPosition = true
        }
    }

    private var statusText: String? {
        switch position.state {
        case .locating: return BipeStrings.appLocationFinding
        case .unavailable: return BipeStrings.appLocationUnavailable
        default: return nil
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if let text = statusText {
            Text(text)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .mediaScrim(Capsule())
                .padding(.top, 12)
                .padding(.horizontal, 64)
        }
    }

    private var myLocationButton: some View {
        Button {
            if let location = ownLocation {
                centre = MapPoint(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
                isAtOwnPosition = true
            } else {
                centredOnce = false
                position.request()
            }
        } label: {
            Circle()
                .stroke(lineWidth: 2)
                .frame(width: 18, height: 18)
                .overlay(Circle().frame(width: 6, height: 6))
                .foregroundStyle(BipeColor.accentInk)
                .padding(4)
                .bipeTapTarget()
                .background(Circle().fill(BipeColor.surfaceRaised))
                .shadow(radius: 2, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(BipeStrings.appLocationMyLocation)
    }

    private var bottomBar: some View {
        VStack(alignment: .leading, spacing: 12) {
            if position.state == .denied {
                VStack(alignment: .leading, spacing: 6) {
                    Text(BipeStrings.appLocationPermission)
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
            if sendFailed {
                Text(BipeStrings.appLocationSendFailed)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            if liveFailed {
                Text(BipeStrings.appLocationLiveStartFailed)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            Text(BipeStrings.appLocationDragHint)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            Button {
                Task { await send() }
            } label: {
                Group {
                    if isSending {
                        ProgressView()
                    } else {
                        Text(isAtOwnPosition ? BipeStrings.appLocationSendCurrent : BipeStrings.appLocationSendPin)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            .disabled(isSending)
            // Spec location-send action startLive; a chat that already gets this account's share shows the banner instead.
            if live.share(in: timeline.roomId, userId: session.userId) == nil {
                Button {
                    if position.state == .denied || position.state == .idle {
                        position.request()
                    } else {
                        choosingDuration = true
                    }
                } label: {
                    Text(BipeStrings.appLocationLiveStart)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bipeBordered)
                .disabled(isSending || position.state == .denied)
                .confirmationDialog(BipeStrings.appLocationLiveDurationTitle, isPresented: $choosingDuration,
                                    titleVisibility: .visible) {
                    ForEach(BipeRegistries.mapsLiveDurationsMinutes, id: \.self) { minutes in
                        Button(LiveLocationRule.durationLabel(minutes: minutes)) {
                            Task { await startLive(minutes: minutes) }
                        }
                    }
                    Button(BipeStrings.appCancel, role: .cancel) {}
                }
                Text(BipeStrings.appLocationLiveFooter)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BipeColor.surfaceRaised)
    }

    /// Action startLive: Room.startLiveLocationShare, then the screen closes and live-location-banner shows.
    private func startLive(minutes: Int) async {
        guard let room = timeline.matrixRoom else {
            liveFailed = true
            return
        }
        isSending = true
        liveFailed = false
        defer { isSending = false }
        do {
            try await LiveLocationCenter.shared.start(session: session, room: room, minutes: minutes)
            close()
        } catch {
            liveFailed = true
        }
    }

    /// Action send: m.location through Timeline.sendLocation, asset sender at the own position, else pin.
    private func send() async {
        guard let sdkTimeline = timeline.timeline else {
            sendFailed = true
            return
        }
        isSending = true
        sendFailed = false
        defer { isSending = false }
        let own = isAtOwnPosition ? ownLocation : nil
        let geoUri = GeoURI.make(latitude: own?.coordinate.latitude ?? centre.latitude,
                                 longitude: own?.coordinate.longitude ?? centre.longitude,
                                 uncertainty: own?.horizontalAccuracy)
        do {
            try await sdkTimeline.sendLocation(body: BipeStrings.appLocationBody(geoUri),
                                               geoUri: geoUri,
                                               description: nil,
                                               zoomLevel: UInt8(centre.zoom),
                                               assetType: own != nil ? .sender : .pin,
                                               repliedToEventId: replyToEventId)
            close()
        } catch {
            sendFailed = true
        }
    }
}
