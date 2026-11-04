// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Intents
import MatrixRustSDK
import UserNotifications

/// Spec notification-service notes, sender photo (bipe-chat/bipe#771): a message notification as a communication
/// notification, with the sender's photo, so Focus can let one person through. Without the communication entitlement
/// (testflight.yml, BIPE_COMM_NOTIFICATIONS) iOS keeps the content as it was.
enum CommunicationNotification {
    /// The longest wait for one photo.
    static let photoTimeout: Duration = .seconds(3)

    static func updated(_ content: UNMutableNotificationContent, text: String, senderId: String, senderName: String,
                        senderAvatarUrl: String?, roomId: String, groupName: String?, groupAvatarUrl: String?,
                        ownUserId: String, client: Client?) async -> UNNotificationContent {
        async let senderPhoto = photo(senderAvatarUrl, client: client)
        async let groupPhoto = groupName == nil ? nil : photo(groupAvatarUrl, client: client)
        let sender = INPerson(personHandle: INPersonHandle(value: senderId, type: .unknown), nameComponents: nil,
                              displayName: senderName, image: await senderPhoto.map { INImage(imageData: $0) },
                              contactIdentifier: nil, customIdentifier: senderId)
        let me = INPerson(personHandle: INPersonHandle(value: ownUserId, type: .unknown), nameComponents: nil, displayName: nil,
                          image: nil, contactIdentifier: nil, customIdentifier: ownUserId, isMe: true)
        // A group needs two recipients and the group name to show as a group.
        let intent = INSendMessageIntent(recipients: groupName == nil ? nil : [me, sender],
                                         outgoingMessageType: .outgoingMessageText,
                                         content: text,
                                         speakableGroupName: groupName.map { INSpeakableString(spokenPhrase: $0) },
                                         conversationIdentifier: roomId,
                                         serviceName: nil,
                                         sender: sender,
                                         attachments: nil)
        if let photo = await groupPhoto {
            intent.setImage(INImage(imageData: photo), forParameterNamed: \.speakableGroupName)
        }
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.direction = .incoming
        try? await interaction.donate()
        return (try? content.updating(from: intent)) ?? content
    }

    private static func photo(_ mxcUrl: String?, client: Client?) async -> Data? {
        guard let mxcUrl, let client, let source = try? MediaSource.fromUrl(url: mxcUrl) else { return nil }
        let task = Task { try? await client.getMediaThumbnail(mediaSource: source, width: 96, height: 96) }
        let timeout = Task {
            try? await Task.sleep(for: photoTimeout)
            task.cancel()
        }
        defer { timeout.cancel() }
        return await task.value
    }
}
