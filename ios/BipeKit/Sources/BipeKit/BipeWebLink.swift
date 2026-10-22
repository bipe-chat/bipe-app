// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The MAS web pages of specs linked-devices and link-computer-approve (ADR 0116, ADR 0118), written from the specs,
// the Matrix spec and api/openapi.yaml.

import Foundation

/// Rules of the private web view that opens MAS pages with the hidden password.
public enum BipeWebLink {
    /// The localpart of a Matrix ID: "@ana:bipe.net.br" → "ana".
    public static func localpart(of userID: String) -> String {
        var id = Substring(userID)
        if id.hasPrefix("@") { id = id.dropFirst() }
        return String(id.split(separator: ":", maxSplits: 1).first ?? id)
    }

    /// The web view loads only https pages on the MAS host.
    public static func isAllowed(_ url: URL, host: String) -> Bool {
        url.scheme?.lowercased() == "https" && url.host()?.lowercased() == host.lowercased()
    }

    /// The MAS account page that ends one device's session (MSC2965 account management actions).
    public static func sessionEndURL(accountManagementURI: URL, deviceID: String) -> URL? {
        guard var components = URLComponents(url: accountManagementURI, resolvingAgainstBaseURL: false) else { return nil }
        var items = components.queryItems ?? []
        items.append(URLQueryItem(name: "action", value: "org.matrix.session_end"))
        items.append(URLQueryItem(name: "device_id", value: deviceID))
        components.queryItems = items
        return components.url
    }

    /// JavaScript that fills the MAS login form once and submits it. The values go in as JSON strings, so they are
    /// escaped. Returns true when the page had a password field.
    public static func loginScript(username: String, password: String) -> String? {
        guard let data = try? JSONEncoder().encode([username, password]),
              let values = String(data: data, encoding: .utf8) else { return nil }
        return """
        (function (v) {
          var field = document.querySelector('form input[type=password]');
          if (!field) { return false; }
          var form = field.form;
          var user = form.querySelector('input[name=username]');
          if (user) { user.value = v[0]; }
          field.value = v[1];
          if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
          return true;
        })(\(values));
        """
    }

    /// JavaScript that answers whether the page has a password field (the MAS login form).
    public static let isLoginFormScript = "!!document.querySelector('form input[type=password]')"
}

/// The parts of the auth metadata that the app reads (MSC2965).
public struct BipeAuthMetadata: Decodable, Sendable {
    public let issuer: String
    public let accountManagementUri: String?

    /// The MAS host, from the account management URI, else the issuer.
    public var host: String? {
        (accountManagementUri.flatMap(URL.init(string:)) ?? URL(string: issuer))?.host()
    }
}

/// One device of the account (Matrix spec client-server, GET /_matrix/client/v3/devices).
public struct BipeMatrixDevice: Decodable, Sendable, Equatable, Identifiable {
    public let deviceId: String
    public let displayName: String?
    public let lastSeenTs: Int64?

    public var id: String { deviceId }

    public var lastUsed: Date? {
        lastSeenTs.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
    }
}

/// The few Matrix client-server calls that the SDK has no FFI for (spec linked-devices notes).
public struct BipeMatrixAccountAPI: Sendable {
    public let homeserver: URL
    private let session: URLSession

    public init(homeserver: URL, session: URLSession = .shared) {
        self.homeserver = homeserver
        self.session = session
    }

    private struct DevicesResponse: Decodable {
        let devices: [BipeMatrixDevice]
    }

    /// `GET /_matrix/client/v3/devices`.
    public func devices(accessToken: String) async throws(BipeAPIError) -> [BipeMatrixDevice] {
        let response: DevicesResponse = try await get(["_matrix", "client", "v3", "devices"], accessToken: accessToken)
        return response.devices
    }

    /// `GET /_matrix/client/v1/auth_metadata`, then the unstable MSC2965 path.
    public func authMetadata() async throws(BipeAPIError) -> BipeAuthMetadata {
        do {
            return try await get(["_matrix", "client", "v1", "auth_metadata"], accessToken: nil)
        } catch {
            return try await get(["_matrix", "client", "unstable", "org.matrix.msc2965", "auth_metadata"], accessToken: nil)
        }
    }

    private func get<Response: Decodable>(_ path: [String], accessToken: String?) async throws(BipeAPIError) -> Response {
        let url = path.reduce(homeserver) { $0.appending(path: $1, directoryHint: .notDirectory) }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw .network
        }
        guard let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else {
            throw .unexpectedResponse(statusCode: (response as? HTTPURLResponse)?.statusCode)
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw .unexpectedResponse(statusCode: status)
        }
    }
}

public extension BipeAPIClient {
    /// `POST /v1/web-link/finish`: ends the MAS browser sessions that the private web view made (ADR 0118).
    func finishWebLink(since: Date, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "POST", path: ["v1", "web-link", "finish"],
                                          body: WebLinkFinish(since: since.formatted(Date.ISO8601FormatStyle(includingFractionalSeconds: true))),
                                          accessToken: accessToken)
    }
}

private struct WebLinkFinish: Encodable {
    let since: String
}
