// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// The signed operator list and issuer registry on this device (bipe-shared docs/signed-lists.md, bipe-chat/bipe#645).
/// bipe-core verifies every envelope; this type only stores, fetches and picks. Rules:
/// - Start from the newest valid list: the stored envelope or the bundled one (`BipeRegistries`), whichever has the
///   higher version. The bundled check skips expiry, so an old build still starts.
/// - Fetch from the core only (A-3), at the current list's `region.core_api`, when the app comes to the foreground and
///   the last fetch is more than 1 hour old, and every 6 hours while it runs.
/// - Keep a fetched envelope only when `verifySignedList` says valid against the current list. Store the bytes as
///   received. A failed fetch or a rejected list keeps the current list and shows nothing.
/// - "Who is a Bipe user" is `bipeServerStatus` on the current list, with the core's stale rule.
public final class SignedLists: @unchecked Sendable {
    public static let shared = SignedLists()

    public static let foregroundInterval: TimeInterval = 60 * 60
    public static let runningInterval: TimeInterval = 6 * 60 * 60
    /// A list is a few kilobytes. A larger answer is not a list (bipe-shared#431: every delivery path sets a size limit).
    static let maxBytes = 256 * 1024

    private let lock = NSLock()
    private let directory: URL?
    private let session: URLSession
    private var lists: [ListType: VerifiedList] = [:]
    private var lastFetch: Date?
    private var loop: Task<Void, Never>?
    /// Answers of `isBipeServer` for the current operator list, until it changes or its `expires` passes.
    private var memo: [String: Bool] = [:]
    private var memoUntil: Int64 = .max

    /// `directory` nil keeps nothing on disk (tests).
    init(directory: URL? = SignedLists.defaultDirectory, session: URLSession = .shared) {
        self.directory = directory
        self.session = session
        for type in [ListType.operators, .issuers] {
            lists[type] = Self.newest(bundled: Self.bundled(type), stored: directory.flatMap { try? String(contentsOf: Self.file(type, in: $0), encoding: .utf8) }, type: type)
        }
    }

    static var defaultDirectory: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.appendingPathComponent("signed-lists", isDirectory: true)
    }

    /// The signer set that this build trusts (registries/list-signers.json). A key that is not base64url is dropped.
    static var signerSet: SignerSet {
        SignerSet(
            threshold: UInt32(BipeRegistries.listSignerThreshold),
            keys: BipeRegistries.listSignerKeys.compactMap { kid, key in Data(base64URLEncoded: key).map { TrustedKey(kid: kid, publicKey: $0) } }
        )
    }

    static func bundled(_ type: ListType) -> String {
        type == .operators ? BipeRegistries.bundledOperatorList : BipeRegistries.bundledIssuerRegistry
    }

    private static func file(_ type: ListType, in directory: URL) -> URL {
        directory.appendingPathComponent(type == .operators ? "operators.json" : "issuers.json")
    }

    private static func path(_ type: ListType) -> String { type == .operators ? "v1/operators" : "v1/issuers" }

    /// The higher version of the two valid envelopes. Expiry is not checked here: a stale list follows the stale rule.
    static func newest(bundled: String, stored: String?, type: ListType) -> VerifiedList? {
        let candidates = [bundled, stored].compactMap { $0 }.compactMap { text -> VerifiedList? in
            if case let .valid(list) = verifyBundledList(envelopeJson: text, listType: type, signerSet: signerSet) { return list }
            return nil
        }
        return candidates.max { $0.version < $1.version }
    }

    public func current(_ type: ListType) -> VerifiedList? {
        lock.withLock { lists[type] }
    }

    /// `true` when the server name is a valid operator of the current list (bipe-core `bipeServerStatus`, stale rule
    /// included). Before any valid list, and while the list is stale, only the default server counts.
    public func isBipeServer(_ serverName: String, now: Date = Date()) -> Bool {
        let seconds = Int64(now.timeIntervalSince1970)
        return lock.withLock {
            if seconds >= memoUntil { memo = [:]; memoUntil = .max }
            if let known = memo[serverName] { return known }
            let current = lists[.operators]
            let answer = bipeServerStatus(current: current, serverName: serverName, nowSeconds: seconds) == .valid
            memo[serverName] = answer
            if let expires = current?.expires, seconds < expires { memoUntil = min(memoUntil, expires) }
            return answer
        }
    }

    /// Call when the app comes to the foreground. Starts the 6-hour loop once, and fetches when the last fetch is old.
    public func appBecameActive() {
        lock.withLock {
            if loop == nil {
                loop = Task { [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .seconds(Self.runningInterval))
                        await self?.refresh()
                    }
                }
            }
        }
        Task { await refreshIfDue() }
    }

    public func refreshIfDue(now: Date = Date()) async {
        let due = lock.withLock { lastFetch.map { now.timeIntervalSince($0) >= Self.foregroundInterval } ?? true }
        if due { await refresh(now: now) }
    }

    /// Fetches both lists from the core and keeps each valid one. Errors keep the current list.
    public func refresh(now: Date = Date()) async {
        lock.withLock { lastFetch = now }
        for type in [ListType.operators, .issuers] {
            guard let base = current(.operators).flatMap({ URL(string: $0.operators?.region.coreApi ?? "") }),
                  base.scheme == "https" else { return }
            guard let text = await fetch(base.appendingPathComponent(Self.path(type))) else { continue }
            _ = accept(text, type: type, now: now)
        }
    }

    /// Verifies one fetched envelope against the current list and keeps it when valid. Returns the result code.
    @discardableResult
    func accept(_ text: String, type: ListType, now: Date) -> String {
        lock.withLock {
            let result = verifySignedList(envelopeJson: text, listType: type, signerSet: Self.signerSet,
                                          nowSeconds: Int64(now.timeIntervalSince1970), stored: lists[type]?.stored)
            if case let .valid(list) = result {
                if let directory {
                    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                    try? Data(text.utf8).write(to: Self.file(type, in: directory), options: .atomic)
                }
                lists[type] = list
                if type == .operators { memo = [:]; memoUntil = .max }
            }
            return signedListResultCode(result: result)
        }
    }

    private func fetch(_ url: URL) async -> String? {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await session.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200, data.count <= Self.maxBytes else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
