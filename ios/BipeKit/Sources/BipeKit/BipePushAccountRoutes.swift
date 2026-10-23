// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Public routing codes only: no credential or message data. Apple sees a random code instead of a Matrix ID.
public final class BipePushAccountRoutes {
    private let defaults: UserDefaults
    private let lock = NSLock()
    private let key = "bipe.push.accountRoutes.v1"

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    public func route(for userId: String) -> String {
        lock.lock()
        defer { lock.unlock() }
        var routes = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        if let route = routes[userId], UUID(uuidString: route) != nil, routes.values.filter({ $0 == route }).count == 1 { return route }
        let route = UUID().uuidString.lowercased()
        routes[userId] = route
        defaults.set(routes, forKey: key)
        return route
    }

    public func account(for route: String) -> String? {
        guard UUID(uuidString: route) != nil else { return nil }
        lock.lock()
        defer { lock.unlock() }
        let routes = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        let owners = routes.filter { $0.value == route }
        return owners.count == 1 ? owners.first?.key : nil
    }

    public func remove(userId: String) {
        lock.lock()
        defer { lock.unlock() }
        var routes = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
        routes.removeValue(forKey: userId)
        defaults.set(routes, forKey: key)
    }
}
