// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreGraphics
import Foundation

/// Type MapCentre of spec location-send: a place and a zoom.
struct MapPoint: Equatable, Hashable {
    var latitude: Double
    var longitude: Double
    var zoom: Int

    init(latitude: Double, longitude: Double, zoom: Int = BipeRegistries.mapsDefaultZoom) {
        self.latitude = latitude
        self.longitude = longitude
        self.zoom = BipeMap.clampZoom(zoom)
    }

    /// The centre of Brazil at minZoom: the start of location-send until the position comes.
    static let brazil = MapPoint(latitude: -14.2, longitude: -51.9, zoom: BipeRegistries.mapsMinZoom)

    func withZoom(_ zoom: Int) -> MapPoint {
        MapPoint(latitude: latitude, longitude: longitude, zoom: zoom)
    }
}

/// Maps from registries/maps.json (ADR 0131): static images and Web Mercator geometry.
/// tileserver-gl renders with 512-point tiles, so the world is 512 * 2^z points wide (spec location-send notes).
enum BipeMap {
    private static let maxLatitude = 85.05112878

    static func clampZoom(_ zoom: Int) -> Int {
        min(max(zoom, BipeRegistries.mapsMinZoom), BipeRegistries.mapsMaxZoom)
    }

    private static func worldSize(_ zoom: Int) -> Double {
        512 * pow(2, Double(zoom))
    }

    /// The point of a place on the world at zoom, in points from the top left.
    private static func project(latitude: Double, longitude: Double, zoom: Int) -> CGPoint {
        let size = worldSize(zoom)
        let lat = min(max(latitude, -maxLatitude), maxLatitude)
        let sine = sin(lat * .pi / 180)
        let x = (longitude + 180) / 360 * size
        let y = (0.5 - log((1 + sine) / (1 - sine)) / (4 * .pi)) * size
        return CGPoint(x: x, y: y)
    }

    private static func unproject(_ point: CGPoint, zoom: Int) -> (latitude: Double, longitude: Double) {
        let size = worldSize(zoom)
        var longitude = Double(point.x) / size * 360 - 180
        longitude = (longitude + 180).truncatingRemainder(dividingBy: 360)
        if longitude < 0 { longitude += 360 }
        longitude -= 180
        let n = Double.pi - 2 * Double.pi * Double(point.y) / size
        let latitude = min(max(atan(sinh(n)) * 180 / .pi, -maxLatitude), maxLatitude)
        return (latitude, longitude)
    }

    /// The place under a point that is `offset` away from the view centre (x right, y down).
    static func place(at offset: CGSize, from centre: MapPoint) -> MapPoint {
        let origin = project(latitude: centre.latitude, longitude: centre.longitude, zoom: centre.zoom)
        let moved = unproject(CGPoint(x: origin.x + offset.width, y: origin.y + offset.height), zoom: centre.zoom)
        return MapPoint(latitude: moved.latitude, longitude: moved.longitude, zoom: centre.zoom)
    }

    /// Where a place shows, in points from the view centre, on a map centred at `centre` (at the zoom of `centre`).
    static func offset(of place: MapPoint, from centre: MapPoint) -> CGSize {
        let a = project(latitude: place.latitude, longitude: place.longitude, zoom: centre.zoom)
        let b = project(latitude: centre.latitude, longitude: centre.longitude, zoom: centre.zoom)
        return CGSize(width: a.x - b.x, height: a.y - b.y)
    }

    /// A static map request for a view of `size` points. A view larger than maxStaticSize asks for half the size
    /// one zoom lower, which covers the same area; the image is then drawn at the view size.
    struct Request: Equatable {
        var url: URL
        var centre: MapPoint
    }

    static func request(_ centre: MapPoint, size: CGSize, dark: Bool) -> Request? {
        var width = Int(size.width.rounded())
        var height = Int(size.height.rounded())
        guard width > 0, height > 0 else { return nil }
        var zoom = centre.zoom
        let limit = BipeRegistries.mapsMaxStaticSize
        while max(width, height) > limit, zoom > 0 {
            width = (width + 1) / 2
            height = (height + 1) / 2
            zoom -= 1
        }
        width = min(width, limit)
        height = min(height, limit)
        let path = BipeRegistries.mapsStaticPath
            .replacingOccurrences(of: "{style}", with: dark ? BipeRegistries.mapsStyleDark : BipeRegistries.mapsStyleLight)
            .replacingOccurrences(of: "{lon}", with: GeoURI.format(centre.longitude))
            .replacingOccurrences(of: "{lat}", with: GeoURI.format(centre.latitude))
            .replacingOccurrences(of: "{zoom}", with: String(zoom))
            .replacingOccurrences(of: "{width}", with: String(width))
            .replacingOccurrences(of: "{height}", with: String(height))
        guard let url = URL(string: BipeRegistries.mapsBaseUrl + path) else { return nil }
        return Request(url: url, centre: centre)
    }
}

/// RFC 5870 geo URIs of m.location (spec location-view notes).
enum GeoURI {
    /// Decimal degrees with coordinateDecimals digits, always with a dot.
    static func format(_ value: Double) -> String {
        String(format: "%.\(BipeRegistries.mapsCoordinateDecimals)f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    /// 'geo:<lat>,<lon>' and ';u=<metres>' when the uncertainty is known.
    static func make(latitude: Double, longitude: Double, uncertainty: Double? = nil) -> String {
        var uri = "geo:\(format(latitude)),\(format(longitude))"
        if let uncertainty, uncertainty > 0 {
            uri += ";u=\(Int(uncertainty.rounded()))"
        }
        return uri
    }

    /// Latitude and longitude, or nil when the URI is not a geo URI with valid WGS 84 coordinates.
    static func parse(_ uri: String) -> (latitude: Double, longitude: Double)? {
        let text = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.lowercased().hasPrefix("geo:") else { return nil }
        let rest = text.dropFirst(4)
        let coordinates = rest.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        let plain = coordinates.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        let parts = plain.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3,
              let latitude = Double(parts[0].trimmingCharacters(in: .whitespaces)),
              let longitude = Double(parts[1].trimmingCharacters(in: .whitespaces)),
              (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
        return (latitude, longitude)
    }
}
