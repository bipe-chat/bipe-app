// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The phone approves a web sign-in by QR code (MSC4108) in a private web view (ADR 0116, ADR 0118).
///
/// The app signs in with `m.login.password`, so it has no MAS browser session. The web view fills the MAS login form
/// once with the hidden password, then the user approves on the MAS consent page.
public enum BipeWebLink {
    /// The `/versions` unstable feature that a server with QR sign-in advertises.
    public static let qrLoginFeature = "org.matrix.msc4108"
    /// The MAS account page action that ends the session of one device (MSC4191).
    public static let sessionEndAction = "org.matrix.session_end"
    
    /// The localpart of a Matrix ID. The MAS login form accepts it as the username (ADR 0118 F5).
    public static func localpart(of userID: String) -> String? {
        guard userID.hasPrefix("@"), let colon = userID.firstIndex(of: ":") else { return nil }
        let localpart = userID[userID.index(after: userID.startIndex)..<colon]
        return localpart.isEmpty ? nil : String(localpart)
    }
    
    /// `true` only for an `https` URL on `masHost`. The web view loads nothing else.
    public static func isAllowed(_ url: URL, masHost: String) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host()?.lowercased() else { return false }
        return host == masHost.lowercased()
    }
    
    /// The MAS consent page of a device code grant is `/device/<id>`.
    public static func isDeviceConsentPath(_ url: URL) -> Bool {
        let components = url.pathComponents.filter { $0 != "/" }
        return components.count >= 2 && components[components.count - 2] == "device"
    }
    
    /// The MAS account page that asks to end the session of `deviceID`.
    public static func sessionEndURL(accountManagementURL: URL, deviceID: String) -> URL? {
        guard var components = URLComponents(url: accountManagementURL, resolvingAgainstBaseURL: false) else { return nil }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "action", value: sessionEndAction))
        items.append(URLQueryItem(name: "device_id", value: deviceID))
        components.queryItems = items
        return components.url
    }
    
    /// The first IPv4 or IPv6 address in the text of a page. The MAS consent page shows the IP of the browser
    /// that asks to sign in (ADR 0118 F6).
    public static func firstIPAddress(in text: String) -> String? {
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "()[],;\"'<>"))
        for token in text.components(separatedBy: separators) where !token.isEmpty {
            let candidate = token.trimmingCharacters(in: CharacterSet(charactersIn: ".:"))
            if isIPv4(candidate) || isIPv6(candidate) {
                return candidate
            }
        }
        return nil
    }
    
    private static func isIPv4(_ value: String) -> Bool {
        var address = in_addr()
        return value.contains(".") && inet_pton(AF_INET, value, &address) == 1
    }
    
    private static func isIPv6(_ value: String) -> Bool {
        var address = in6_addr()
        // Two colons at least, so a time like 12:30 does not count.
        return value.filter { $0 == ":" }.count >= 2 && inet_pton(AF_INET6, value, &address) == 1
    }
}

/// Contract: `GET /v1/web-link/place`. Every field is optional.
public struct BipeWebLinkPlace: Decodable, Sendable, Equatable {
    public let city: String?
    public let region: String?
    /// ISO 3166-1 alpha-2.
    public let country: String?
    
    public init(city: String?, region: String?, country: String?) {
        self.city = city
        self.region = region
        self.country = country
    }
    
    /// "City, Region" in Brazil; the country name is added for a place abroad. `nil` when the place is unknown.
    public func label(locale: Locale = .current) -> String? {
        var parts = [city, region].compactMap { $0?.isEmpty == false ? $0 : nil }
        if let country, !country.isEmpty, country.uppercased() != "BR" || parts.isEmpty {
            parts.append(locale.localizedString(forRegionCode: country) ?? country)
        }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }
}

/// The Matrix and MAS calls that the web sign-in needs and the SDK does not offer for a `m.login.password` session.
public struct BipeMatrixAccountAPI: Sendable {
    private let homeserver: URL
    private let session: URLSession
    
    public init(homeserver: URL, session: URLSession = .shared) {
        self.homeserver = homeserver
        self.session = session
    }
    
    /// `true` when `/versions` advertises QR sign-in (MSC4108).
    public func supportsQRLogin() async -> Bool {
        struct Versions: Decodable {
            let unstableFeatures: [String: Bool]?
        }
        guard let versions: Versions = try? await get(["_matrix", "client", "versions"], accessToken: nil) else { return false }
        return versions.unstableFeatures?[BipeWebLink.qrLoginFeature] == true
    }
    
    /// The MAS issuer and account page (MSC2965). The stable path first, then the unstable one.
    public func authMetadata() async throws -> BipeAuthMetadata {
        if let metadata: BipeAuthMetadata = try? await get(["_matrix", "client", "v1", "auth_metadata"], accessToken: nil) {
            return metadata
        }
        return try await get(["_matrix", "client", "unstable", "org.matrix.msc2965", "auth_metadata"], accessToken: nil)
    }
    
    /// `GET /_matrix/client/v3/devices`: every device of the account, this one too.
    public func devices(accessToken: String) async throws -> [BipeMatrixDevice] {
        struct Devices: Decodable {
            let devices: [BipeMatrixDevice]
        }
        let response: Devices = try await get(["_matrix", "client", "v3", "devices"], accessToken: accessToken)
        return response.devices
    }
    
    private func get<Response: Decodable>(_ path: [String], accessToken: String?) async throws -> Response {
        let url = path.reduce(homeserver) { $0.appending(path: $1, directoryHint: .notDirectory) }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200..<300).contains(httpResponse.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Response.self, from: data)
    }
}

/// MSC2965 authorization server metadata. Only the fields the web sign-in uses.
public struct BipeAuthMetadata: Decodable, Sendable, Equatable {
    public let issuer: URL
    public let accountManagementUri: URL?
    
    public init(issuer: URL, accountManagementUri: URL?) {
        self.issuer = issuer
        self.accountManagementUri = accountManagementUri
    }
    
    /// The MAS host. The private web view loads only this host.
    public var host: String? {
        issuer.host()?.lowercased()
    }
}

/// One device of the account (`GET /_matrix/client/v3/devices`).
public struct BipeMatrixDevice: Decodable, Sendable, Equatable, Identifiable {
    public let deviceId: String
    public let displayName: String?
    public let lastSeenTs: Int64?
    
    public init(deviceId: String, displayName: String?, lastSeenTs: Int64?) {
        self.deviceId = deviceId
        self.displayName = displayName
        self.lastSeenTs = lastSeenTs
    }
    
    public var id: String {
        deviceId
    }
    
    public var lastSeen: Date? {
        lastSeenTs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
    }
}
