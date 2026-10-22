// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeAppAttest.swift).

import CryptoKit
import DeviceCheck
import Foundation

/// Apple App Attest for sign-up: proves the request comes from the genuine app on a real Apple device.
/// Server side: bipe-api/src/attest.ts.
public enum BipeAppAttest {
    /// False on the simulator and on devices without the Secure Enclave support App Attest needs.
    public static var isSupported: Bool {
        DCAppAttestService.shared.isSupported
    }
    
    /// Like ``attestation(api:)``, but `nil` when Apple's attestation service is down after one more try.
    /// The server then decides (`SIGNUP_ATTEST_REQUIRED`), so an Apple outage does not stop sign-up when it is off.
    public static func attestationUnlessServiceDown(api: BipeAPIClient) async throws(BipeAPIError) -> BipeAttestation? {
        for attempt in 1...2 {
            do {
                return try await attestation(api: api)
            } catch .attestationServiceDown {
                if attempt == 2 {
                    return nil
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
        return nil
    }
    
    /// Fetches a challenge and attests a new key against it. Sign-up is rare, so every attempt uses a fresh key
    /// and nothing goes to the keychain.
    public static func attestation(api: BipeAPIClient) async throws(BipeAPIError) -> BipeAttestation {
        let challenge = try await api.attestChallenge()
        let clientDataHash = Data(SHA256.hash(data: Data(challenge.utf8)))
        let service = DCAppAttestService.shared
        
        do {
            let keyID = try await service.generateKey()
            let attestationObject = try await service.attestKey(keyID, clientDataHash: clientDataHash)
            return BipeAttestation(keyId: keyID,
                                   attestationObject: attestationObject.base64EncodedString(),
                                   challenge: challenge)
        } catch let error as DCError where error.code == .serverUnavailable {
            throw .attestationServiceDown
        } catch {
            throw .attestationFailed
        }
    }
}
