// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeHandle.swift).

import Foundation

/// Same ids as `handleProblem()` in bipe-shared/tools/lib/registries.mjs and the API's `HandleProblem`.
public enum BipeHandleProblem: String, Sendable, Equatable {
    case tooShort = "too_short"
    case tooLong = "too_long"
    case invalidCharacters = "invalid_characters"
    case reserved
    /// Also covers held and lookalike handles (server only).
    case taken
    
    public var localizedMessage: String {
        switch self {
        case .tooShort: BipeStrings.handleTooShort(String(BipeRegistries.handleMinLength))
        case .tooLong: BipeStrings.handleTooLong(String(BipeRegistries.handleMaxLength))
        case .invalidCharacters: BipeStrings.handleInvalidCharacters
        case .reserved: BipeStrings.handleReserved
        case .taken: BipeStrings.handleTaken
        }
    }
}

/// Client-side handle checks for early feedback. The server is the authority:
/// reserved names and lookalikes are checked there through the availability endpoint.
public nonisolated enum BipeHandle {
    /// Trims whitespace and a leading "@" the user may type.
    public static func sanitize(_ input: String) -> String {
        var handle = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if handle.hasPrefix("@") {
            handle.removeFirst()
        }
        return handle
    }
    
    /// Length and character checks only, the same as a lookup. `nil` means the server must decide.
    /// An owned handle may be shorter than `handleMinLength` (ADR 0065); the server decides who may claim it.
    public static func localProblem(for handle: String) -> BipeHandleProblem? {
        // JS `length` counts UTF-16 code units; the pattern is ASCII-only, so any non-ASCII input fails it anyway.
        let length = handle.utf16.count
        if length < BipeRegistries.handleOwnerMinLength {
            return .tooShort
        }
        if length > BipeRegistries.handleMaxLength {
            return .tooLong
        }
        if length < BipeRegistries.handleMinLength, !BipeRegistries.ownedHandles.contains(handle) {
            return .tooShort
        }
        if handle.range(of: BipeRegistries.handlePattern, options: .regularExpression) == nil {
            return .invalidCharacters
        }
        return nil
    }
}

/// Client-side checks for the sign-up form. Limits come from the API contract (`SignupRequest`).
public nonisolated enum BipeSignupRules {
    public static let inviteCodeMaxLength = 64
    public static let emailMaxLength = 254
    public static let displayNameMaxLength = 100
    
    /// A loose shape check. MAS and the API validate the address for real.
    public static func isPlausibleEmail(_ email: String) -> Bool {
        guard email.count <= emailMaxLength else { return false }
        return email.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil
    }
}

/// Result of checking a handle the user typed.
public enum BipeHandleStatus: Sendable, Equatable {
    case empty
    case checking
    case available
    case problem(BipeHandleProblem)
    /// The check failed (offline or server error). The server decides on submit.
    case unknown
    
    public var isBlocking: Bool {
        if case .problem = self {
            true
        } else {
            false
        }
    }
    
    /// Footer text for the handle field. `defaultText` shows when there is nothing to report.
    public func footer(defaultText: String) -> String {
        switch self {
        case .empty, .unknown: defaultText
        case .checking: BipeStrings.handleChecking
        case .available: BipeStrings.handleAvailable
        case .problem(let problem): problem.localizedMessage
        }
    }
}

/// Debounced availability check: local rules at once, then the API after a pause in typing.
@MainActor
public final class BipeHandleAvailabilityChecker {
    private let apiClient: BipeAPIClient
    private let debounce: Duration
    private var task: Task<Void, Never>?
    
    public init(apiClient: BipeAPIClient, debounce: Duration = .milliseconds(500)) {
        self.apiClient = apiClient
        self.debounce = debounce
    }
    
    /// Cancels any check in flight. `update` gets every status change for `input`, on the main actor.
    public func check(_ input: String, update: @escaping @MainActor (BipeHandleStatus) -> Void) {
        task?.cancel()
        
        let handle = BipeHandle.sanitize(input)
        guard !handle.isEmpty else {
            update(.empty)
            return
        }
        if let problem = BipeHandle.localProblem(for: handle) {
            update(.problem(problem))
            return
        }
        
        update(.checking)
        task = Task { [apiClient, debounce] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            
            let status: BipeHandleStatus
            do {
                let availability = try await apiClient.handleAvailability(handle)
                status = availability.available ? .available : .problem(availability.reason ?? .taken)
            } catch {
                status = .unknown
            }
            
            guard !Task.isCancelled else { return }
            update(status)
        }
    }
    
    public func cancel() {
        task?.cancel()
    }
}
