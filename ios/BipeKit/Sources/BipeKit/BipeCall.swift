// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// An OpenID token of `Client.requestOpenidToken()`, for the SFU token request (MSC4195).
public struct BipeCallOpenId: Equatable, Sendable {
    public var accessToken: String
    public var tokenType: String
    public var matrixServerName: String
    public var expiresInSeconds: UInt64

    public init(accessToken: String, tokenType: String, matrixServerName: String, expiresInSeconds: UInt64) {
        self.accessToken = accessToken
        self.tokenType = tokenType
        self.matrixServerName = matrixServerName
        self.expiresInSeconds = expiresInSeconds
    }
}

/// One media key of one call member, for the LiveKit per-participant key provider.
public struct BipeCallKey: Equatable, Sendable {
    public var identity: String
    public var index: Int32
    public var key: Data
}

/// One active member device of a room call (MSC4143 membership).
public struct BipeCallMember: Equatable, Sendable {
    public var userId: String
    public var deviceId: String
    public var createdTs: Int64
    public var focusUrl: String?

    public var identity: String { BipeCall.livekitIdentity(userId: userId, deviceId: deviceId) }
}

/// MatrixRTC formats of spec call (bipe-chat/bipe#758, ADR 0186). The vectors are bipe-shared
/// test-vectors/calls/matrixrtc.json; Android has the same rules in BipeCall.kt.
public enum BipeCall {
    public static let memberEventType = "org.matrix.msc3401.call.member"
    public static let keyEventType = "io.element.call.encryption_keys"
    public static let fociKey = "org.matrix.msc4143.rtc_foci"
    /// LiveKit key provider settings. Element Web uses the same key ring.
    public static let keyRingSize: Int32 = 16
    public static let ratchetWindowSize: Int32 = 10
    /// The delayed leave (MSC4140): sent before the join, restarted while in the call.
    public static let leaveDelayMs: UInt64 = 20_000
    public static let leaveRestartSeconds: UInt64 = 5
    /// The own key index. The LiveKit SDKs give the app no way to change the index of a published track.
    public static let ownKeyIndex: Int32 = 0
    /// A new own key is used this long after it was sent, so the others have it first.
    public static let keyUseDelaySeconds: Double = 1
    /// MSC4143: a membership without `expires` lasts 4 hours.
    public static let defaultExpiresMs: Int64 = 14_400_000
    public static let keyLength = 16

    public static func stateKey(userId: String, deviceId: String) -> String { "_\(userId)_\(deviceId)" }

    public static func livekitIdentity(userId: String, deviceId: String) -> String { "\(userId):\(deviceId)" }

    public static func membershipContent(deviceId: String, roomId: String, focusUrl: String, createdTs: Int64,
                                         video: Bool) -> [String: Any] {
        [
            "application": "m.call",
            "call_id": "",
            "scope": "m.room",
            "device_id": deviceId,
            "focus_active": ["type": "livekit", "focus_selection": "oldest_membership"],
            "foci_preferred": [["type": "livekit", "livekit_service_url": focusUrl, "livekit_alias": roomId]],
            "created_ts": createdTs,
            "m.call.intent": video ? "video" : "audio",
        ]
    }

    public static func sfuRequestBody(roomId: String, deviceId: String, openId: BipeCallOpenId) -> [String: Any] {
        [
            "room": roomId,
            "openid_token": [
                "access_token": openId.accessToken,
                "token_type": openId.tokenType,
                "matrix_server_name": openId.matrixServerName,
                "expires_in": openId.expiresInSeconds,
            ],
            "device_id": deviceId,
        ]
    }

    /// The `url` and `jwt` of a `/sfu/get` answer.
    public static func sfuAnswer(_ json: [String: Any]) -> (url: String, jwt: String)? {
        guard let url = json["url"] as? String, !url.isEmpty, let jwt = json["jwt"] as? String, !jwt.isEmpty else { return nil }
        return (url, jwt)
    }

    public static func keyEventContent(index: Int32, key: Data, deviceId: String, sentTs: Int64) -> [String: Any] {
        [
            "keys": [["index": index, "key": base64Unpadded(key)]],
            "device_id": deviceId,
            "call_id": "",
            "sent_ts": sentTs,
        ]
    }

    /// The keys of a room event or of a to-device message of type `keyEventType`.
    public static func readKeys(sender: String, content: [String: Any]) -> [BipeCallKey] {
        let deviceId: String?
        let rawKeys: [[String: Any]]
        if let list = content["keys"] as? [[String: Any]] {
            guard (content["call_id"] as? String ?? "") == "" else { return [] }
            deviceId = content["device_id"] as? String
            rawKeys = list
        } else if let single = content["keys"] as? [String: Any] {
            let session = content["session"] as? [String: Any]
            guard (session?["call_id"] as? String ?? "") == "" else { return [] }
            deviceId = (content["member"] as? [String: Any])?["claimed_device_id"] as? String
            rawKeys = [single]
        } else {
            return []
        }
        guard let deviceId, !deviceId.isEmpty else { return [] }
        let identity = livekitIdentity(userId: sender, deviceId: deviceId)
        return rawKeys.compactMap { item in
            guard let index = (item["index"] as? NSNumber)?.int32Value, index >= 0, index < keyRingSize,
                  let text = item["key"] as? String, let key = decodeBase64(text), key.count == keyLength else { return nil }
            return BipeCallKey(identity: identity, index: index, key: key)
        }
    }

    /// The first LiveKit focus of the well-known, https only.
    public static func focusUrl(wellKnown: [String: Any]) -> String? {
        guard let foci = wellKnown[fociKey] as? [[String: Any]] else { return nil }
        return foci.lazy.compactMap(livekitUrl).first
    }

    /// The member of one `memberEventType` state event, or nil when the event is no active membership.
    public static func member(sender: String, stateKey: String, originServerTs: Int64, content: [String: Any],
                              now: Int64) -> BipeCallMember? {
        guard content["application"] as? String == "m.call", (content["call_id"] as? String ?? "") == "",
              let deviceId = content["device_id"] as? String, !deviceId.isEmpty,
              stateKey == Self.stateKey(userId: sender, deviceId: deviceId) || stateKey == "\(sender)_\(deviceId)" else { return nil }
        let created = (content["created_ts"] as? NSNumber)?.int64Value ?? originServerTs
        let expires = (content["expires"] as? NSNumber)?.int64Value ?? defaultExpiresMs
        guard created + expires > now else { return nil }
        let focus = (content["foci_preferred"] as? [[String: Any]])?.lazy.compactMap(livekitUrl).first
        return BipeCallMember(userId: sender, deviceId: deviceId, createdTs: created, focusUrl: focus)
    }

    /// focus_selection oldest_membership: the focus of the oldest member, else the own one.
    public static func oldestFocus(members: [BipeCallMember], own: String) -> String {
        members.filter { $0.focusUrl != nil }.min { $0.createdTs < $1.createdTs }?.focusUrl ?? own
    }

    public static func base64Unpadded(_ data: Data) -> String {
        var text = data.base64EncodedString()
        while text.hasSuffix("=") { text.removeLast() }
        return text
    }

    public static func decodeBase64(_ text: String) -> Data? {
        var standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while standard.hasSuffix("=") { standard.removeLast() }
        let pad = (4 - standard.count % 4) % 4
        guard pad != 3 else { return nil }
        return Data(base64Encoded: standard + String(repeating: "=", count: pad))
    }

    private static func livekitUrl(_ focus: [String: Any]) -> String? {
        guard focus["type"] as? String == "livekit", let url = focus["livekit_service_url"] as? String,
              url.hasPrefix("https://") else { return nil }
        return url
    }
}
