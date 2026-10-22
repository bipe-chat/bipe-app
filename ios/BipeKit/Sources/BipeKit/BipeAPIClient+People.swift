// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Bipe API calls of people and profiles (bipe-chat/bipe#568): account type, badges, public profiles,
// who can message me, contact discovery. Contract: bipe-shared/api/openapi.yaml.

import Foundation

extension BipeAPIClient {
    // MARK: - Accounts and badges

    /// `GET /v1/accounts/{userId}/type`. No auth. Fail closed: any failure is a personal account (ADR 0132).
    public func accountType(userID: String) async -> BipeAccountType {
        let response: AccountTypeResponse? = try? await send(method: "GET", path: ["v1", "accounts", userID, "type"], body: Empty?.none)
        return response?.accountType ?? .personal
    }

    /// `GET /v1/badges/{userId}`. No auth. 200 is an envelope to verify on the device, 410 a revoked claim, 404 no badge.
    public func badge(userID: String) async throws(BipeAPIError) -> BipeBadgeLookup {
        let (data, response) = try await exchange(method: "GET", path: ["v1", "badges", userID], rawBody: nil, accessToken: nil)
        switch response.statusCode {
        case 200:
            guard let envelope = try? Self.decoder.decode(BipeBadgeEnvelope.self, from: data) else {
                throw .unexpectedResponse(statusCode: 200)
            }
            return .envelope(envelope)
        case 404:
            return .noBadge
        case 410:
            let gone = try? Self.decoder.decode(BadgeGoneBody.self, from: data)
            return .gone(gone.flatMap { BipeBadgeGoneReason(rawValue: $0.reason) } ?? .revoked)
        case 429:
            throw .server(.rateLimited, reason: nil, statusCode: 429)
        default:
            throw .unexpectedResponse(statusCode: response.statusCode)
        }
    }

    // MARK: - Public profiles (ADR 0041)

    /// `GET /v1/profiles/{handle}`. No auth. `nil` on 404: no such handle, or the profile is off.
    public func publicProfile(handle: String) async throws(BipeAPIError) -> BipePublicProfile? {
        do throws(BipeAPIError) {
            return try await send(method: "GET", path: ["v1", "profiles", handle], body: Empty?.none)
        } catch {
            if case .server(_, _, 404) = error { return nil }
            throw error
        }
    }

    /// `GET /v1/me/profile`. A user who never saved one gets `enabled` false and an empty name.
    public func myProfile(accessToken: String) async throws(BipeAPIError) -> BipeMyProfile {
        try await send(method: "GET", path: ["v1", "me", "profile"], body: Empty?.none, accessToken: accessToken)
    }

    /// `PUT /v1/me/profile`. The server checks the same rules and answers `profile_invalid` with a ``BipeProfileProblem`` id.
    public func saveMyProfile(enabled: Bool, name: String, bio: String, accessToken: String) async throws(BipeAPIError) -> BipeMyProfile {
        try await send(method: "PUT", path: ["v1", "me", "profile"],
                       body: ProfileUpdate(enabled: enabled, name: name, bio: bio),
                       accessToken: accessToken)
    }

    /// `PUT /v1/me/profile/photo` with a JPEG from ``BipeProfilePhoto/jpegData(from:)``.
    public func setMyProfilePhoto(jpeg: Data, accessToken: String) async throws(BipeAPIError) -> BipeMyProfile {
        try await send(method: "PUT", path: ["v1", "me", "profile", "photo"],
                       rawBody: (data: jpeg, contentType: "image/jpeg"),
                       accessToken: accessToken)
    }

    /// `DELETE /v1/me/profile/photo`.
    public func removeMyProfilePhoto(accessToken: String) async throws(BipeAPIError) -> BipeMyProfile {
        try await send(method: "DELETE", path: ["v1", "me", "profile", "photo"], body: Empty?.none, accessToken: accessToken)
    }

    // MARK: - Who can message me (ADR 0038)

    /// `GET /v1/me/messaging`.
    public func messagingSetting(accessToken: String) async throws(BipeAPIError) -> BipeWhoCanMessage {
        let setting: MessagingSetting = try await send(method: "GET", path: ["v1", "me", "messaging"], body: Empty?.none, accessToken: accessToken)
        return setting.whoCanMessage
    }

    /// `PUT /v1/me/messaging`. Returns the saved value.
    public func setMessagingSetting(_ value: BipeWhoCanMessage, accessToken: String) async throws(BipeAPIError) -> BipeWhoCanMessage {
        let setting: MessagingSetting = try await send(method: "PUT", path: ["v1", "me", "messaging"],
                                                       body: MessagingSetting(whoCanMessage: value),
                                                       accessToken: accessToken)
        return setting.whoCanMessage
    }

    // MARK: - Contact discovery (ADR 0015)

    /// Most numbers per `POST /v1/contacts/lookup`.
    public static let contactLookupChunk = 500

    /// `POST /v1/contacts/lookup` with at most ``contactLookupChunk`` E.164 numbers. Returns E.164 to Matrix ID.
    /// The server keeps none of the numbers; the app keeps no copy of what it sent.
    public func lookupContacts(phones: [String], accessToken: String) async throws(BipeAPIError) -> [String: String] {
        let response: ContactLookupResponse = try await send(method: "POST", path: ["v1", "contacts", "lookup"],
                                                             body: ContactLookupRequest(phones: phones),
                                                             accessToken: accessToken)
        return Dictionary(response.matches.map { ($0.phone, $0.userId) }, uniquingKeysWith: { first, _ in first })
    }

    /// `GET /v1/me/invite-connections`. The people the user invited who joined, and whoever invited the user (ADR 0034).
    public func inviteConnections(accessToken: String) async throws(BipeAPIError) -> Set<String> {
        let response: InviteConnections = try await send(method: "GET", path: ["v1", "me", "invite-connections"], body: Empty?.none, accessToken: accessToken)
        return Set(response.userIds)
    }

    // MARK: - Private

    private struct AccountTypeResponse: Decodable {
        let accountType: BipeAccountType
    }

    private struct BadgeGoneBody: Decodable {
        let reason: String
    }

    private struct ProfileUpdate: Encodable {
        let enabled: Bool
        let name: String
        let bio: String
    }

    private struct MessagingSetting: Codable {
        let whoCanMessage: BipeWhoCanMessage
    }

    private struct ContactLookupRequest: Encodable {
        let phones: [String]
    }

    private struct ContactLookupResponse: Decodable {
        struct Match: Decodable {
            let phone: String
            let userId: String
        }

        let matches: [Match]
    }

    private struct InviteConnections: Decodable {
        let userIds: [String]
    }
}

// MARK: - Models

/// Contract `MyProfile`.
public struct BipeMyProfile: Decodable, Sendable, Equatable {
    public let enabled: Bool
    public let name: String
    public let bio: String
    public let hasPhoto: Bool
    /// Absent when the user has no handle.
    public let handle: String?
    /// Absent without a photo.
    public let photoUrl: String?
}

/// Contract `PublicProfile`. Never carries the Matrix ID.
public struct BipePublicProfile: Decodable, Sendable, Equatable {
    public let handle: String
    public let name: String
    /// Plain text. Absent when empty.
    public let bio: String?
    public let photoUrl: String?
    /// The same envelope as `GET /v1/badges/{userId}`.
    public let badge: BipeBadgeEnvelope?
}

/// Contract `MessagingSetting.who_can_message`.
public enum BipeWhoCanMessage: String, Codable, Sendable, CaseIterable {
    case everyone
    case verified
    case contacts
}

/// What `GET /v1/badges/{userId}` answered.
public enum BipeBadgeLookup: Sendable, Equatable {
    case envelope(BipeBadgeEnvelope)
    case gone(BipeBadgeGoneReason)
    case noBadge
}

/// Contract `BadgeGone.reason`.
public enum BipeBadgeGoneReason: String, Sendable, Equatable {
    case revoked
    case notActive = "not_active"
    case notFound = "not_found"
    case staff
}
