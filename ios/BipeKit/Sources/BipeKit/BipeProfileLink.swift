// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeProfileLink.swift): the link part only.
// The QR code views stay for the screen issue.

import Foundation

/// Profile links: `https://bipe.chat/@handle`. The web page there falls back to the App Store;
/// the app opens it as a universal link (`applinks:bipe.chat`).
/// Links on the old host `proseio.com.br` still parse: that host redirects to `bipe.chat`.
public nonisolated enum BipeProfileLink {
    public static let host = "bipe.chat"
    /// Hosts whose links the app accepts. The old host stays for links already shared.
    static let acceptedHosts: Set<String> = [host, "www.\(host)", "proseio.com.br", "www.proseio.com.br"]
    
    static func isBipeLinkHost(_ url: URL) -> Bool {
        guard let host = url.host()?.lowercased() else { return false }
        return acceptedHosts.contains(host)
    }
    
    public static func url(handle: String) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/@\(handle)"
        return components.url
    }
    
    /// The query item of the counter QR code of Fila virtual: `https://bipe.chat/@handle?fila` (spec queue-customer).
    public static let queueQueryItem = "fila"

    /// The counter QR code link of a business's queue.
    public static func queueURL(handle: String) -> URL? {
        guard let profile = url(handle: handle), var components = URLComponents(url: profile, resolvingAgainstBaseURL: false) else { return nil }
        components.queryItems = [URLQueryItem(name: queueQueryItem, value: nil)]
        return components.url
    }

    /// The handle in a queue link (a profile link with the `fila` query item), or `nil` when the URL is not one.
    public static func queueHandle(from url: URL) -> String? {
        guard let handle = handle(from: url),
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              items.contains(where: { $0.name == queueQueryItem }) else { return nil }
        return handle
    }

    /// The handle in a profile link, lowercased, or `nil` when the URL is not one.
    public static func handle(from url: URL) -> String? {
        guard url.scheme == "https", isBipeLinkHost(url) else {
            return nil
        }
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count == 1, let first = components.first, first.hasPrefix("@") else { return nil }
        let handle = BipeHandle.sanitize(first).lowercased()
        return BipeHandle.localProblem(for: handle) == nil ? handle : nil
    }
    
    /// The Matrix user ID in a `https://matrix.to/#/@user:server` permalink.
    public static func matrixUserID(fromPermalink url: URL) -> String? {
        guard url.host() == "matrix.to", let fragment = url.fragment(percentEncoded: false) else { return nil }
        let path = fragment.split(separator: "?", maxSplits: 1).first.map(String.init) ?? fragment
        let userID = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return userID.hasPrefix("@") && !userID.contains("/") ? userID : nil
    }
}

public extension BipeHandleResolver {
    /// The profile link for a user with a known handle.
    func profileLink(for userID: String) -> URL? {
        handle(for: userID).flatMap(BipeProfileLink.url(handle:))
    }
    
    /// Swaps a matrix.to user permalink for the Bipe profile link when the handle is known.
    func shareURL(for permalink: URL) -> URL {
        BipeProfileLink.matrixUserID(fromPermalink: permalink).flatMap(profileLink(for:)) ?? permalink
    }
}
