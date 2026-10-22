// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import os

/// One step of opening an account (spec account-start): a time limit, so a step that never returns becomes a failure,
/// and a log line before and after, so a device log shows where a start stops. Never logs a password or a token.
enum AccountStep {
    enum Failure: Error {
        /// The step did not finish within the time limit.
        case timedOut(String)
        /// The saved session has no SDK store on this phone (the app was deleted and installed again).
        case noStore
    }

    static let log = Logger(subsystem: "chat.bipe.app", category: "account-start")

    /// The time limit of each step.
    static let limit: UInt64 = 20

    /// Runs the step. On time-out it throws `timedOut` at once and leaves the step running; the SDK call ignores
    /// cancellation.
    static func run<T: Sendable>(_ name: String, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        log.notice("\(name, privacy: .public): start")
        do {
            let value = try await BipeDeadline.run(seconds: TimeInterval(limit), operation: operation)
            log.notice("\(name, privacy: .public): done")
            return value
        } catch is BipeDeadline.Expired {
            log.error("\(name, privacy: .public): timed out")
            throw Failure.timedOut(name)
        } catch {
            log.error("\(name, privacy: .public): failed")
            throw error
        }
    }
}
