// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// A static map image from maps.bipe.net.br (ADR 0131), centred on `centre`, at the size of the view.
/// While a new image loads, the last image stays, moved and scaled to where its area is now.
/// The server draws "© OpenStreetMap" into each image; the view never crops it.
struct StaticMapView: View {
    let centre: MapPoint
    let size: CGSize

    @Environment(\.colorScheme) private var colorScheme
    @State private var shown: ShownMap?
    @State private var failed = false
    @State private var attempt = 0

    private struct ShownMap {
        var image: UIImage
        var centre: MapPoint
    }

    var body: some View {
        let request = BipeMap.request(centre, size: size, dark: colorScheme == .dark)
        ZStack {
            BipeColor.surfaceSunken
            if let shown {
                let scale = pow(2, CGFloat(centre.zoom - shown.centre.zoom))
                Image(uiImage: shown.image)
                    .resizable()
                    .frame(width: size.width, height: size.height)
                    .scaleEffect(scale)
                    .offset(BipeMap.offset(of: shown.centre, from: centre))
            }
            if failed {
                Button {
                    attempt += 1
                } label: {
                    VStack(spacing: 6) {
                        PhosphorIcon.arrowClockwise.image
                            .resizable()
                            .frame(width: 22, height: 22)
                        Text(BipeStrings.appMediaFailed)
                            .font(.caption)
                            .multilineTextAlignment(.center)
                    }
                    .padding(10)
                    .mediaScrim(RoundedRectangle(cornerRadius: 10))
                    .padding(8)
                }
                .buttonStyle(.plain)
            } else if shown?.centre != centre {
                ProgressView()
                    .padding(10)
                    .mediaScrim(Circle())
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .task(id: "\(request?.url.absoluteString ?? "")#\(attempt)") {
            await load(request)
        }
    }

    private func load(_ request: BipeMap.Request?) async {
        guard let request else { return }
        failed = false
        do {
            let (data, response) = try await URLSession.shared.data(from: request.url)
            guard (response as? HTTPURLResponse)?.statusCode == 200, let image = UIImage(data: data) else {
                throw URLError(.badServerResponse)
            }
            guard !Task.isCancelled else { return }
            shown = ShownMap(image: image, centre: request.centre)
        } catch {
            if !Task.isCancelled { failed = true }
        }
    }
}

/// The pin on a map: a dot in the accent colour with a ring.
struct MapPin: View {
    var body: some View {
        Circle()
            .fill(BipeColor.accentFill)
            .frame(width: 20, height: 20)
            .overlay(Circle().stroke(BipeColor.surfaceRaised, lineWidth: 3))
            .shadow(radius: 2, y: 1)
            .accessibilityHidden(true)
    }
}

/// A map the person can drag and zoom (specs location-send and location-view). `pinAt` nil keeps the pin in the
/// middle (location-send); else the pin stays on that place while the map moves (location-view).
struct DraggableMapView: View {
    @Binding var centre: MapPoint
    var pinAt: MapPoint?
    /// Called at the end of each drag or zoom.
    var onMove: () -> Void = {}

    @State private var drag: CGSize = .zero

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                StaticMapView(centre: centre, size: geometry.size)
                    .offset(drag)
                if let pinAt {
                    let offset = BipeMap.offset(of: pinAt, from: centre)
                    MapPin()
                        .offset(x: offset.width + drag.width, y: offset.height + drag.height)
                } else {
                    MapPin()
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { drag = $0.translation }
                    .onEnded { value in
                        centre = BipeMap.place(at: CGSize(width: -value.translation.width, height: -value.translation.height),
                                               from: centre)
                        drag = .zero
                        onMove()
                    }
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(BipeStrings.appLocationTitle)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: zoom(by: 1)
                case .decrement: zoom(by: -1)
                @unknown default: break
                }
            }
            .overlay(alignment: .bottomTrailing) {
                zoomButtons
                    .padding(12)
                    // The attribution sits in the bottom right corner of the image; keep it visible.
                    .padding(.bottom, 24)
            }
        }
    }

    private var zoomButtons: some View {
        VStack(spacing: 0) {
            Button { zoom(by: 1) } label: {
                PhosphorIcon.plus.image
                    .resizable()
                    .frame(width: 18, height: 18)
                    .bipeTapTarget()
            }
            .disabled(centre.zoom >= centre.maxZoom)
            .accessibilityLabel(BipeStrings.appLocationZoomIn)
            Divider().frame(width: 28)
            Button { zoom(by: -1) } label: {
                Capsule()
                    .frame(width: 16, height: 2)
                    .frame(width: 18, height: 18)
                    .bipeTapTarget()
            }
            .disabled(centre.zoom <= BipeRegistries.mapsMinZoom)
            .accessibilityLabel(BipeStrings.appLocationZoomOut)
        }
        .buttonStyle(.plain)
        .foregroundStyle(BipeColor.inkPrimary)
        .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.surfaceRaised))
        .shadow(radius: 2, y: 1)
    }

    private func zoom(by step: Int) {
        let next = centre.withZoom(centre.zoom + step)
        guard next != centre else { return }
        centre = next
        onMove()
    }
}
