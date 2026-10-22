// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct DeadlineTests {
    private enum Failure: Error { case example }

    @Test
    func returnsValueAndError() async throws {
        let value = try await BipeDeadline.run(seconds: 2) { 42 }
        #expect(value == 42)
        do {
            _ = try await BipeDeadline.run(seconds: 2) { () throws -> Int in throw Failure.example }
            Issue.record("The operation's error was lost")
        } catch Failure.example { }
    }

    @Test
    func timeoutDoesNotWaitForBlockingWork() async throws {
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        do {
            _ = try await BipeDeadline.run(seconds: 0.1) {
                // Models an FFI call that blocks a thread and ignores task cancellation.
                Self.simulateBlockingFFI(release)
                return 42
            }
            Issue.record("Blocking work prevented the timeout")
        } catch is BipeDeadline.Expired { }
        // A late result from the first operation cannot complete a later operation.
        #expect(try await BipeDeadline.run(seconds: 2) { 7 } == 7)
    }

    // A synchronous FFI boundary is allowed to block its worker thread. The caller must still time out.
    private static func simulateBlockingFFI(_ release: DispatchSemaphore) { release.wait() }

    @Test
    func cancellationReturnsBeforeUncooperativeWorkFinishes() async throws {
        let started = DeadlineLatch()
        let release = DeadlineLatch()
        let task = Task {
            try await BipeDeadline.run(seconds: 10) {
                await started.open()
                // A checked continuation does not react to cancellation on its own.
                await release.wait()
                return 42
            }
        }
        await started.wait()
        task.cancel()
        do {
            _ = try await task.value
            Issue.record("Cancellation was ignored")
        } catch is CancellationError { }
        await release.open()
    }
}

private actor DeadlineLatch {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        guard !isOpen else { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        isOpen = true
        let pending = waiters
        waiters.removeAll()
        for waiter in pending { waiter.resume() }
    }
}
