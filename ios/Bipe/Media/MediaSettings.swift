// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import OSLog

/// The setting bipe_chats_show_media (state showMedia of spec timeline-media), as the settings
/// screen stores it (SettingsKeys.showMedia). MediaPreviewSync keeps it equal to the account's value.
extension ShowMedia {
    static var current: ShowMedia {
        UserDefaults.standard.string(forKey: SettingsKeys.showMedia).flatMap(ShowMedia.init) ?? .always
    }

    /// private: media shows in DMs and in groups that need an invite; in a public group it waits for a tap.
    func shows(inPrivateRoom isPrivate: Bool) -> Bool {
        switch self {
        case .always: true
        case .private: isPrivate
        case .never: false
        }
    }

    /// MSC4278 media_previews: always = on, private = private, never = off.
    var policy: MediaPreviews {
        switch self {
        case .always: .on
        case .private: .private
        case .never: .off
        }
    }

    init(policy: MediaPreviews) {
        switch policy {
        case .on: self = .always
        case .private: self = .private
        case .off: self = .never
        }
    }
}

/// Spec timeline-media and chat-requests notes (bipe-chat/bipe#827): showMedia and hidePhotos live in the account,
/// in the standard account data m.media_preview_config (MSC4278), so both phones of one person and other Matrix
/// clients agree. The phone keeps a copy for the rows; this follows the current account's value and writes the
/// person's choice. An account without the setting gets the phone's value once.
@MainActor
final class MediaPreviewSync {
    static let shared = MediaPreviewSync()

    private var client: Client?
    private var handle: TaskHandle?
    private var task: Task<Void, Never>?
    private static let log = Logger(subsystem: "chat.bipe.app", category: "media-preview")

    /// The current account changed: follow its setting.
    func attach(_ session: AccountSession) {
        handle?.cancel()
        handle = nil
        task?.cancel()
        let client = session.client
        self.client = client
        let stream = MediaPreviewConfigStream()
        task = Task { [weak self] in
            let handle = try? await client.subscribeToMediaPreviewConfig(listener: stream)
            guard let self, self.client === client else {
                handle?.cancel()
                return
            }
            self.handle = handle
            do {
                let config = try await client.fetchMediaPreviewConfig()
                await self.apply(config, client: client, writeMissing: true)
            } catch {
                // Offline: keep the phone's copy; the subscription brings the value later.
                Self.log.notice("media preview config not fetched now")
            }
            for await config in stream.updates {
                await self.apply(config, client: client, writeMissing: false)
            }
        }
    }

    /// The person picked showMedia in settings.
    func setShowMedia(_ value: ShowMedia) {
        UserDefaults.standard.set(value.rawValue, forKey: SettingsKeys.showMedia)
        guard let client else { return }
        Task {
            do {
                try await client.setMediaPreviewDisplayPolicy(policy: value.policy)
            } catch {
                Self.log.error("media previews not saved in the account")
            }
        }
    }

    /// The person turned the switch requestPhotos (hidePhotos of spec chat-requests).
    func setHideInvitePhotos(_ hide: Bool) {
        UserDefaults.standard.set(hide, forKey: SettingsKeys.hideRequestPhotos)
        guard let client else { return }
        Task {
            do {
                try await client.setInviteAvatarsDisplayPolicy(policy: hide ? .off : .on)
            } catch {
                Self.log.error("invite avatars not saved in the account")
            }
        }
    }

    private func apply(_ config: MediaPreviewConfig?, client: Client, writeMissing: Bool) async {
        guard self.client === client else { return }
        let defaults = UserDefaults.standard
        if let previews = config?.mediaPreviews {
            defaults.set(ShowMedia(policy: previews).rawValue, forKey: SettingsKeys.showMedia)
        } else if writeMissing {
            try? await client.setMediaPreviewDisplayPolicy(policy: ShowMedia.current.policy)
        }
        if let avatars = config?.inviteAvatars {
            defaults.set(avatars == .off, forKey: SettingsKeys.hideRequestPhotos)
        } else if writeMissing {
            let hide = defaults.object(forKey: SettingsKeys.hideRequestPhotos) as? Bool ?? true
            try? await client.setInviteAvatarsDisplayPolicy(policy: hide ? .off : .on)
        }
    }
}

final class MediaPreviewConfigStream: MediaPreviewConfigListener {
    let updates: AsyncStream<MediaPreviewConfig?>
    private let continuation: AsyncStream<MediaPreviewConfig?>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onChange(mediaPreviewConfig: MediaPreviewConfig?) {
        continuation.yield(mediaPreviewConfig)
    }
}

/// Spec timeline-media fromStranger (bipe-chat/bipe#827): the DMs the person accepted from chat-requests on this
/// phone. Room IDs only, on the phone.
enum RequestRooms {
    private static let key = "bipe.media.requestRooms"
    /// The newest this many rooms are kept.
    private static let limit = 500

    static func remember(_ roomId: String) {
        var rooms = UserDefaults.standard.stringArray(forKey: key) ?? []
        rooms.removeAll { $0 == roomId }
        rooms.append(roomId)
        UserDefaults.standard.set(Array(rooms.suffix(limit)), forKey: key)
    }

    static func contains(_ roomId: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: key) ?? []).contains(roomId)
    }
}
