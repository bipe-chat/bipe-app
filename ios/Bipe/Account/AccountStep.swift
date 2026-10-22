// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

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
    static func run<T>(_ name: String, _ operation: @escaping () async throws -> T) async throws -> T {
        log.info("\(name, privacy: .public): start")
        let gate = Gate()
        do {
            let value: T = try await withCheckedThrowingContinuation { continuation in
                let work = Task {
                    do {
                        let value = try await operation()
                        if gate.claim() { continuation.resume(returning: value) }
                    } catch {
                        if gate.claim() { continuation.resume(throwing: error) }
                    }
                }
                Task {
                    try? await Task.sleep(nanoseconds: limit * 1_000_000_000)
                    if gate.claim() {
                        work.cancel()
                        continuation.resume(throwing: Failure.timedOut(name))
                    }
                }
            }
            log.info("\(name, privacy: .public): done")
            return value
        } catch {
            log.error("\(name, privacy: .public): failed, \(String(describing: error), privacy: .public)")
            throw error
        }
    }

    /// Lets only the first of the step and its timer resume the caller.
    private final class Gate: @unchecked Sendable {
        private let lock = NSLock()
        private var claimed = false

        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !claimed else { return false }
            claimed = true
            return true
        }
    }
}
