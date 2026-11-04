// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
#if canImport(DeclaredAgeRange)
import DeclaredAgeRange
#endif
import UIKit

/// ADR 0229, bipe-chat/bipe#994: the age comes from Apple Declared Age Range, never from a date of birth. After
/// sign-in and on foreground, when the server has no signal or one older than ``BipeRegistries/ageSignalRecheckDays``,
/// the app asks the OS and sends the range. The server maps it to a band; the app keeps nothing.
@MainActor
final class AgeSignalModel {
    private let api: BipeAPIClient
    private let session: AccountSession
    private var running = false

    init(api: BipeAPIClient, session: AccountSession) {
        self.api = api
        self.session = session
    }

    /// A failed read or send does nothing; the next check tries again.
    func check() async {
        guard !running else { return }
        running = true
        defer { running = false }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await self.api.account(accessToken: token)
        }
        // An older server has no age_band: it has no age route either.
        guard let status, status.ageBand != nil else { return }
        if let checked = status.ageCheckedAt,
           Date().timeIntervalSince(checked) < TimeInterval(BipeRegistries.ageSignalRecheckDays) * 86_400 {
            return
        }
        let signal = await Self.readSignal()
        _ = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> String in
            try await self.api.sendAgeSignal(source: signal.source, lower: signal.lower, upper: signal.upper, method: signal.method, accessToken: token)
        }
    }

    private struct Signal {
        var source = "none"
        var lower: Int?
        var upper: Int?
        var method: String?
    }

    /// Declined, not eligible, an OS before iOS 26 or an error: source none. The server treats none as unknown.
    private static func readSignal() async -> Signal {
        #if canImport(DeclaredAgeRange)
        guard #available(iOS 26.0, *) else { return Signal() }
        let service = AgeRangeService.shared
        if #available(iOS 26.2, *), (try? await service.isEligibleForAgeFeatures) == false {
            return Signal()
        }
        guard let presenter = topViewController() else { return Signal() }
        let gates = BipeRegistries.ageSignalAppleGates
        guard let first = gates.first else { return Signal() }
        let response = try? await service.requestAgeRange(ageGates: first,
                                                          gates.count > 1 ? gates[1] : nil,
                                                          gates.count > 2 ? gates[2] : nil,
                                                          in: presenter)
        guard case let .sharing(range) = response else { return Signal() }
        return Signal(source: "apple", lower: range.lowerBound, upper: range.upperBound,
                      method: range.ageRangeDeclaration.map { String(describing: $0) })
        #else
        return Signal()
        #endif
    }

    private static func topViewController() -> UIViewController? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        var top = windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
