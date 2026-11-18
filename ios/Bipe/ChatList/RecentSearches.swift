// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Combine
import Foundation

/// State recent of spec chat-search: the last 10 queries that opened a result, on this device, per account. iOS
/// and the Mac use it.
@MainActor
final class RecentSearches: ObservableObject {
    static let limit = 10

    @Published private(set) var queries: [String]
    private let key: String

    init(userId: String) {
        key = Self.key(userId)
        queries = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    func add(_ query: String) {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        queries = Array(([query] + queries.filter { $0 != query }).prefix(Self.limit))
        UserDefaults.standard.set(queries, forKey: key)
    }

    /// Action clearRecent.
    func clear() {
        queries = []
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// Sign-out (bipe-chat/bipe#1370): this account's recent searches leave the device.
    static func forget(userId: String) {
        UserDefaults.standard.removeObject(forKey: key(userId))
    }

    private static func key(_ userId: String) -> String { "recentSearches.\(userId)" }
}
