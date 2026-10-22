// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// A deadline that returns even when a blocking SDK operation ignores task cancellation.
/// Work runs away from the caller's actor; the timer uses a dispatch queue, not the blocked cooperative executor.
public nonisolated enum BipeDeadline {
    public struct Expired: Error, Sendable { public init() {} }

    public static func run<Value: Sendable>(seconds: TimeInterval,
                                            operation: @escaping @Sendable () async throws -> Value) async throws -> Value {
        let state = DeadlineState<Value>()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                state.install(continuation)
                let work = Task.detached(priority: .userInitiated) {
                    do {
                        try Task.checkCancellation()
                        state.finish(.success(try await operation()))
                    } catch { state.finish(.failure(error)) }
                }
                let timer = DispatchWorkItem { [weak state] in state?.finish(.failure(Expired())) }
                state.install(work: work, timer: timer)
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + max(0, seconds), execute: timer)
            }
        } onCancel: {
            state.finish(.failure(CancellationError()))
        }
    }
}

/// Continuation, cancellation and late completion may arrive on different threads. Exactly one result wins.
private final class DeadlineState<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var completion: Result<Value, Error>?
    private var continuation: CheckedContinuation<Value, Error>?
    private var work: Task<Void, Never>?
    private var timer: DispatchWorkItem?

    func install(_ continuation: CheckedContinuation<Value, Error>) {
        lock.lock()
        let result = completion
        if result == nil { self.continuation = continuation }
        lock.unlock()
        if let result { continuation.resume(with: result) }
    }

    func install(work: Task<Void, Never>, timer: DispatchWorkItem) {
        lock.lock()
        let finished = completion != nil
        if !finished { self.work = work; self.timer = timer }
        lock.unlock()
        if finished { work.cancel(); timer.cancel() }
    }

    func finish(_ result: Result<Value, Error>) {
        lock.lock()
        guard completion == nil else { lock.unlock(); return }
        completion = result
        let continuation = continuation
        let work = work
        let timer = timer
        self.continuation = nil
        self.work = nil
        self.timer = nil
        lock.unlock()
        timer?.cancel()
        work?.cancel()
        continuation?.resume(with: result)
    }
}
