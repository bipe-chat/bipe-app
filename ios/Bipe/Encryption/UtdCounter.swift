// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The unable-to-decrypt hook (bipe-chat/bipe#1122): counts on this phone each message the SDK could not decrypt, by
/// cause. It keeps no event ID, room ID, sender or text, and sends nothing. The counts go into a problem report as
/// utd.json, only when the person includes the logs (spec problem-report).
final class UtdCounter: UnableToDecryptDelegate, @unchecked Sendable {
    private static let countsKey = "bipe.utd.counts"
    private static let lateKey = "bipe.utd.late"
    private static let lastKey = "bipe.utd.last"
    private static let lock = NSLock()

    func onUtd(info: UnableToDecryptInfo) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        let defaults = UserDefaults.standard
        let cause = String(describing: info.cause)
        var counts = defaults.dictionary(forKey: Self.countsKey) as? [String: Int] ?? [:]
        counts[cause, default: 0] += 1
        defaults.set(counts, forKey: Self.countsKey)
        // A late decryption: the SDK decrypted it after the grace time, so the person saw it locked for a while.
        if info.timeToDecryptMs != nil {
            defaults.set(defaults.integer(forKey: Self.lateKey) + 1, forKey: Self.lateKey)
        }
        defaults.set(Date(), forKey: Self.lastKey)
    }

    /// utd.json for the problem report: totals per cause on this phone, the late ones and the last time.
    static func reportFile() -> BipeProblemReport.File? {
        lock.lock()
        defer { lock.unlock() }
        let defaults = UserDefaults.standard
        let counts = defaults.dictionary(forKey: countsKey) as? [String: Int] ?? [:]
        var report: [String: Any] = ["causes": counts, "total": counts.values.reduce(0, +), "late": defaults.integer(forKey: lateKey)]
        if let last = defaults.object(forKey: lastKey) as? Date {
            report["last"] = ISO8601DateFormatter().string(from: last)
        }
        guard let data = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]) else { return nil }
        return BipeProblemReport.File(name: "utd.json", contentType: "application/json", data: data)
    }
}
