// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation
import Synchronization

/// The scam pattern a message looks like (security.md rule 9). Friction, not protection.
public nonisolated enum ScamWarningKind: String, Sendable, Hashable {
    case extortion
    case pixCode
    case boleto
    case remoteApp
    case pinRequest
    case pixKey
    case deliveryFee
    case lookalikeLink
    case bankAlert
    case receipt
    case newNumber
    case urgency
    
    /// The pt-BR first explanation. It never says the message IS a scam.
    public var message: String {
        switch self {
        case .extortion: BipeStrings.scamWarningExtortion
        case .pixCode: BipeStrings.scamWarningPixCode
        case .boleto: BipeStrings.scamWarningBoleto
        case .remoteApp: BipeStrings.scamWarningRemoteApp
        case .pinRequest: BipeStrings.scamWarningPinRequest
        case .pixKey: BipeStrings.scamWarningPixKey
        case .deliveryFee: BipeStrings.scamWarningDeliveryFee
        case .lookalikeLink: BipeStrings.scamWarningLookalikeLink
        case .bankAlert: BipeStrings.scamWarningBankAlert
        case .receipt: BipeStrings.scamWarningReceipt
        case .newNumber: BipeStrings.scamWarningNewNumber
        case .urgency: BipeStrings.scamWarningUrgency
        }
    }
}

public extension ScamDetector {
    /// The one warning to show for a message, or `nil`. Cached by event ID, so timeline rebuilds stay cheap.
    /// The body is part of the key because an edit keeps the event ID.
    static func warningKind(for message: String, eventID: String) -> ScamWarningKind? {
        let bodyHash = message.hashValue
        if let cached = warningCache.withLock({ $0[eventID] }), cached.bodyHash == bodyHash {
            return cached.kind
        }
        
        let kind = warningKind(in: message)
        warningCache.withLock { cache in
            if cache.count >= maxCachedEvents {
                cache.removeAll(keepingCapacity: true)
            }
            cache[eventID] = CachedWarning(bodyHash: bodyHash, kind: kind)
        }
        return kind
    }
    
    /// The one warning to show for a message, or `nil`. Kinds in `skipping` are passed over.
    /// Must match bipe-shared/test-vectors/scam-warnings.json.
    static func warningKind(in message: String, skipping: Set<ScamWarningKind> = []) -> ScamWarningKind? {
        scamWarningKind(message: message, skipping: skipping.map(\.coreKind)).map(ScamWarningKind.init(core:))
    }
    
    // MARK: - Private
    
    private struct CachedWarning: Sendable {
        let bodyHash: Int
        let kind: ScamWarningKind?
    }
    
    private static let maxCachedEvents = 2000
    private static let warningCache = Mutex<[String: CachedWarning]>([:])
}

nonisolated extension ScamWarningKind {
    /// The bipe-core kind of the same name.
    init(core: ScamKind) {
        self = switch core {
        case .extortion: .extortion
        case .pixCode: .pixCode
        case .boleto: .boleto
        case .remoteApp: .remoteApp
        case .pinRequest: .pinRequest
        case .pixKey: .pixKey
        case .deliveryFee: .deliveryFee
        case .lookalikeLink: .lookalikeLink
        case .bankAlert: .bankAlert
        case .receipt: .receipt
        case .newNumber: .newNumber
        case .urgency: .urgency
        }
    }
    
    var coreKind: ScamKind {
        switch self {
        case .extortion: .extortion
        case .pixCode: .pixCode
        case .boleto: .boleto
        case .remoteApp: .remoteApp
        case .pinRequest: .pinRequest
        case .pixKey: .pixKey
        case .deliveryFee: .deliveryFee
        case .lookalikeLink: .lookalikeLink
        case .bankAlert: .bankAlert
        case .receipt: .receipt
        case .newNumber: .newNumber
        case .urgency: .urgency
        }
    }
}
