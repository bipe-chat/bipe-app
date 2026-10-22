// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Spec remote-banner (bipe-shared product/screens/remote-banner.json), ADR 0117. Written from the contract
// RemoteBanner in api/openapi.yaml and the reference remoteBannerState() and compareAppVersions() in
// bipe-shared tools/lib/registries.mjs; cases in test-vectors/remote-banner.json.

import Foundation

/// Contract `RemoteBanner`. Title and text are pt-BR only and show as they are.
public struct BipeRemoteBanner: Codable, Sendable, Equatable {
    public struct UpdateLinks: Codable, Sendable, Equatable {
        public let ios: String
        public let android: String
    }

    public let id: String
    public let title: String
    public let text: String
    public let link: String
    public let minAppVersion: String?
    public let updateLinks: UpdateLinks?
    public let expiresAt: Date

    /// What the app shows for the banner.
    public enum State: String, Sendable, Equatable {
        case hidden
        case notice
        case update
    }

    /// Reference `remoteBannerState(banner, appVersion, now)`.
    public static func state(of banner: BipeRemoteBanner?, appVersion: String, now: Date) -> State {
        guard let banner, banner.expiresAt > now else { return .hidden }
        if let minimum = banner.minAppVersion, compareVersions(appVersion, minimum) < 0 {
            return .update
        }
        return .notice
    }

    /// Reference `compareAppVersions(a, b)`: -1, 0 or 1. Missing parts count as 0; each part reads its leading digits.
    public static func compareVersions(_ a: String, _ b: String) -> Int {
        let pa = a.split(separator: ".", omittingEmptySubsequences: false)
        let pb = b.split(separator: ".", omittingEmptySubsequences: false)
        func part(_ parts: [Substring], _ index: Int) -> Int {
            guard index < parts.count else { return 0 }
            return Int(parts[index].prefix(while: \.isASCIIDigitCharacter)) ?? 0
        }
        for index in 0..<max(pa.count, pb.count) {
            let x = part(pa, index), y = part(pb, index)
            if x != y { return x < y ? -1 : 1 }
        }
        return 0
    }
}

private extension Character {
    var isASCIIDigitCharacter: Bool { ("0"..."9").contains(self) }
}

public extension BipeAPIClient {
    private struct BannerResponse: Decodable {
        let banner: BipeRemoteBanner?
    }

    /// `GET /v1/banner`. No auth; sends nothing about the person.
    func remoteBanner() async throws(BipeAPIError) -> BipeRemoteBanner? {
        let response: BannerResponse = try await send(method: "GET", path: ["v1", "banner"], body: Empty?.none)
        return response.banner
    }
}
