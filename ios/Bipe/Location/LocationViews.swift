// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MapKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Type LocationMessage of spec location-view.
struct LocationMessage: Equatable {
    var point: MapPoint
    var isPin: Bool
    var description: String?

    /// nil when the geo URI cannot be read (the row is then unsupported).
    init?(_ content: LocationContent) {
        guard let place = GeoURI.parse(content.geoUri) else { return nil }
        point = MapPoint(latitude: place.latitude, longitude: place.longitude,
                         zoom: content.zoomLevel.map(Int.init) ?? BipeRegistries.mapsDefaultZoom)
        // MSC3488: a missing or unknown asset is the sender's own position.
        if case .pin = content.asset { isPin = true } else { isPin = false }
        description = content.description?.nilIfBlank
    }

    var label: String {
        isPin ? BipeStrings.appLocationPinned : BipeStrings.appLocationCurrent
    }

    /// Action openInMaps: Apple Maps. The place leaves Bipe only here, when the person taps.
    func openInMaps() {
        let coordinate = CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
        let item = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
        item.name = description ?? label
        item.openInMaps(launchOptions: [MKLaunchOptionsMapCenterKey: NSValue(mkCoordinate: coordinate)])
    }
}

/// Spec location-view: the content of a location message row. The bubble, sender, reply quote, footer and
/// reactions come from the message row of spec timeline-rows.
struct TimelineLocationRow: View {
    let location: LocationMessage
    let openFull: () -> Void

    private static let width: CGFloat = 260

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Color.clear
                .aspectRatio(4 / 3, contentMode: .fit)
                .frame(idealWidth: Self.width, maxWidth: Self.width)
                .overlay {
                    GeometryReader { geometry in
                        ZStack {
                            StaticMapView(centre: location.point, size: geometry.size)
                            MapPin()
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
                .onTapGesture(perform: openFull)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(location.label)
                .accessibilityAddTraits(.isButton)
            Text(location.label)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(BipeColor.inkPrimary)
            if let description = location.description {
                Text(description)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: Self.width, alignment: .leading)
            }
        }
        .accessibilityIdentifier(BipeScreen.locationView.rawValue)
    }
}

/// Spec location-view: the full-screen map of one location.
struct LocationFullView: View {
    let location: LocationMessage
    let senderName: String
    let sentAt: Date
    let close: () -> Void

    @State private var centre: MapPoint

    init(location: LocationMessage, senderName: String, sentAt: Date, close: @escaping () -> Void) {
        self.location = location
        self.senderName = senderName
        self.sentAt = sentAt
        self.close = close
        _centre = State(initialValue: location.point)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                DraggableMapView(centre: $centre, pinAt: location.point)
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(location.label)
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                        if let description = location.description {
                            Text(description)
                                .foregroundStyle(BipeColor.inkPrimary)
                        }
                        HStack(spacing: 6) {
                            Text(senderName)
                                .lineLimit(1)
                            Text(sentAt.formatted(date: .abbreviated, time: .shortened))
                        }
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                    }
                    Button {
                        location.openInMaps()
                    } label: {
                        Text(BipeStrings.appLocationOpenMaps)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeProminent)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(BipeColor.surfaceRaised)
            }
            .navigationTitle(BipeStrings.appLocationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: close) {
                        PhosphorIcon.x.toolbarImage(size: 22)
                    }
                    .accessibilityLabel(BipeStrings.appClose)
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.locationView.rawValue)
    }
}
