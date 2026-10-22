// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The remote critical banner above the chat list (ADR 0117, bipe#386): an outage, a forced update or a change of terms.
/// `GET /v1/banner`. Port of `remoteBannerState()` in bipe-shared; `test-vectors/remote-banner.json` holds the cases.
public struct BipeRemoteBanner: Decodable, Sendable, Equatable {
    public struct UpdateLinks: Decodable, Sendable, Equatable {
        public let ios: URL
        public let android: URL
    }
    
    public enum State: Equatable, Sendable {
        case hidden
        /// Opens `link`. The person can dismiss it by `id`.
        case notice
        /// The app is below `minAppVersion`: opens the update link and cannot be dismissed.
        case update(URL)
    }
    
    public let id: String
    public let title: String
    public let text: String
    public let link: URL
    public let minAppVersion: String?
    public let updateLinks: UpdateLinks?
    public let expiresAt: String
    
    public func state(appVersion: String, now: Date = .now) -> State {
        guard let expiry = Self.parseDate(expiresAt), now < expiry else { return .hidden }
        if let minAppVersion, Self.compareVersions(appVersion, minAppVersion) < 0, let updateLinks {
            return .update(updateLinks.ios)
        }
        return .notice
    }
    
    /// Compares dotted versions part by part as numbers. Each part counts its leading digits only ("2-dev" is 2).
    public static func compareVersions(_ a: String, _ b: String) -> Int {
        let pa = a.split(separator: ".", omittingEmptySubsequences: false)
        let pb = b.split(separator: ".", omittingEmptySubsequences: false)
        for index in 0..<max(pa.count, pb.count) {
            let x = index < pa.count ? leadingNumber(pa[index]) : 0
            let y = index < pb.count ? leadingNumber(pb[index]) : 0
            if x != y {
                return x < y ? -1 : 1
            }
        }
        return 0
    }
    
    private static func leadingNumber(_ part: Substring) -> Int {
        Int(part.prefix { $0.isASCII && $0.isNumber }) ?? 0
    }
    
    private static func parseDate(_ value: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return withFraction.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
}

struct BipeRemoteBannerResponse: Decodable, Sendable {
    let banner: BipeRemoteBanner?
}
