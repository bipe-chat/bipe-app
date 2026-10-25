// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Network

/// Spec data-use (bipe-chat/bipe#242): counts the media Bipe sends and downloads on this phone, by mobile data and
/// Wi-Fi, today and this calendar month, and says when the app saves mobile data. The counters stay on the phone.
final class DataUseMeter: @unchecked Sendable {
    static let shared = DataUseMeter()

    /// The data saver switch (spec data-use, state dataSaver).
    static let saverKey = "bipe.dataUse.saver"

    struct Count: Equatable {
        var sentBytes: Int64 = 0
        var receivedBytes: Int64 = 0
    }

    struct Snapshot: Equatable {
        var todayMobile = Count()
        var todayWifi = Count()
        var monthMobile = Count()
        var monthWifi = Count()
    }

    private let lock = NSLock()
    private let defaults: UserDefaults
    private let monitor = NWPathMonitor()
    private var isCellular = false
    private var isConstrained = false
    /// Media URLs already counted, so a download from the SDK cache after a restart is not counted again.
    private var counted: [String]
    private var prunedFor: String?
    private static let countedKey = "bipe.dataUse.counted"
    private static let countedMax = 2000

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        counted = defaults.stringArray(forKey: Self.countedKey) ?? []
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.withLock {
                self.isCellular = path.usesInterfaceType(.cellular)
                self.isConstrained = path.isConstrained
            }
        }
        monitor.start(queue: DispatchQueue(label: "chat.bipe.dataUse"))
    }

    /// true when the system Low Data Mode is on (spec data-use, state systemSaverOn).
    var systemSaverOn: Bool { lock.withLock { isConstrained } }

    /// On mobile data with the switch or the system saver on: photos and stickers wait for a tap (spec timeline-media).
    var savesData: Bool {
        let switchOn = defaults.object(forKey: Self.saverKey) as? Bool ?? BipeRegistries.dataSaverDefault
        return lock.withLock { isCellular && (switchOn || isConstrained) }
    }

    /// An upload Bipe started.
    func recordSent(_ bytes: Int64) {
        add(bytes, field: "sent")
    }

    /// A download of `url`, counted once per media URL.
    func recordReceived(_ bytes: Int64, url: String) {
        let isNew = lock.withLock { () -> Bool in
            guard !counted.contains(url) else { return false }
            counted.append(url)
            if counted.count > Self.countedMax { counted.removeFirst(counted.count - Self.countedMax) }
            defaults.set(counted, forKey: Self.countedKey)
            return true
        }
        if isNew { add(bytes, field: "received") }
    }

    func snapshot(now: Date = .now) -> Snapshot {
        lock.withLock {
            Snapshot(todayMobile: count(period: Self.day(now), network: "mobile"),
                     todayWifi: count(period: Self.day(now), network: "wifi"),
                     monthMobile: count(period: Self.month(now), network: "mobile"),
                     monthWifi: count(period: Self.month(now), network: "wifi"))
        }
    }

    // MARK: Private

    private func add(_ bytes: Int64, field: String, now: Date = .now) {
        guard bytes > 0 else { return }
        lock.withLock {
            let network = isCellular ? "mobile" : "wifi"
            for period in [Self.day(now), Self.month(now)] {
                let key = "bipe.dataUse.\(period).\(network).\(field)"
                defaults.set((defaults.object(forKey: key) as? Int64 ?? 0) + bytes, forKey: key)
            }
            prune(now: now)
        }
    }

    private func count(period: String, network: String) -> Count {
        Count(sentBytes: defaults.object(forKey: "bipe.dataUse.\(period).\(network).sent") as? Int64 ?? 0,
              receivedBytes: defaults.object(forKey: "bipe.dataUse.\(period).\(network).received") as? Int64 ?? 0)
    }

    /// Drops counters of earlier days and months: the screen shows only today and this month.
    private func prune(now: Date) {
        guard prunedFor != Self.day(now) else { return }
        prunedFor = Self.day(now)
        let keep = ["bipe.dataUse.\(Self.day(now)).", "bipe.dataUse.\(Self.month(now))."]
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("bipe.dataUse.") {
            guard key != Self.countedKey, key != Self.saverKey else { continue }
            if !keep.contains(where: key.hasPrefix) { defaults.removeObject(forKey: key) }
        }
    }

    private static func day(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "d%04d%02d%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    private static func month(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month], from: date)
        return String(format: "m%04d%02d", parts.year ?? 0, parts.month ?? 0)
    }
}
