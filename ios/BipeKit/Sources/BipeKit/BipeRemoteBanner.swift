// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
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
    
    /// bipe-core `remoteBannerState`. The update state needs an update link to open; without one the banner is a notice.
    public func state(appVersion: String, now: Date = .now) -> State {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let banner = RemoteBanner(expiresAt: expiresAt, minAppVersion: minAppVersion)
        switch remoteBannerState(banner: banner, appVersion: appVersion, now: formatter.string(from: now)) {
        case .hidden: return .hidden
        case .update: return updateLinks.map { State.update($0.ios) } ?? .notice
        case .notice: return .notice
        }
    }
    
    /// Compares dotted versions part by part as numbers (bipe-core `compareAppVersions`). Each part counts its leading
    /// digits only ("2-dev" is 2).
    public static func compareVersions(_ a: String, _ b: String) -> Int {
        Int(compareAppVersions(a: a, b: b))
    }
}

struct BipeRemoteBannerResponse: Decodable, Sendable {
    let banner: BipeRemoteBanner?
}
