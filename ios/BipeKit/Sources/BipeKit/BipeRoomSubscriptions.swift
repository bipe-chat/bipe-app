// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

/// The pinned SDK replaces its room subscriptions on every call. Lists lease one union per account session.
public actor BipeRoomSubscriptions {
    private let apply: @Sendable ([String]) async throws -> Void
    private var owners: [String: Set<String>] = [:]
    private var applied: Set<String>?
    private var applying = false
    private var closed = false

    public init(apply: @escaping @Sendable ([String]) async throws -> Void) { self.apply = apply }

    public func update(owner: String, roomIDs: [String]) async {
        guard !closed else { return }
        owners[owner] = Set(roomIDs.filter { !$0.isEmpty })
        await reconcile()
    }

    public func remove(owner: String) async {
        owners.removeValue(forKey: owner)
        await reconcile()
    }

    public func close() async {
        closed = true
        owners.removeAll()
        await reconcile()
    }

    private func reconcile() async {
        guard !applying else { return }
        applying = true
        defer { applying = false }
        while true {
            let target = owners.values.reduce(into: Set<String>()) { $0.formUnion($1) }
            guard target != applied else { return }
            do { try await apply(target.sorted()) }
            catch { return } // A failed apply is not acknowledged; the next owner update retries.
            applied = target
            // Owner updates may have arrived while the SDK was suspended. Drain their newest union.
        }
    }
}
