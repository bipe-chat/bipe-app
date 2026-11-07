// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI

/// Desktop layout values. Colors and rounded system type stay in Bipe's shared design system.
enum MacDesign {
    static let gap: CGFloat = 16
    static let sectionGap: CGFloat = 24
    static let pageInset: CGFloat = 32
    static let corner: CGFloat = 16
}

struct MacBrand: View {
    var compact = false
    var body: some View {
        HStack(spacing: 12) {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable().scaledToFit().frame(width: compact ? 36 : 64, height: compact ? 36 : 64)
                .accessibilityHidden(true)
            Text(BipeStrings.appName).macFont(compact ? .headline : .largeTitle).fontWeight(.bold)
        }
    }
}

struct MacCard<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        content.padding(MacDesign.sectionGap)
            .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: MacDesign.corner))
            .overlay(RoundedRectangle(cornerRadius: MacDesign.corner).strokeBorder(BipeColor.lineSubtle))
    }
}

struct MacNotice: View {
    let text: String
    var danger = false
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(decorative: danger ? "warning-circle" : "warning").resizable().frame(width: 20, height: 20)
            Text(text).macFont(.callout).fixedSize(horizontal: false, vertical: true)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(danger ? BipeColor.trustDangerInk : BipeColor.trustCautionInk)
        .background(danger ? BipeColor.trustDangerWash : BipeColor.trustCautionWash,
                    in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(danger ? BipeColor.trustDangerLine : BipeColor.trustCautionLine))
    }
}

/// The sender line of a group row (spec timeline-rows, chat-timeline senderRole): the name, the @ when another member
/// shows the same name, the role, and the seal from the signed badge. Never the Matrix ID.
struct MacSenderLine: View {
    let message: MacMessage
    @Environment(\.bipeHandles) private var handles
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        let name = message.senderNameAmbiguous
            ? MemberNames.label(name: message.sender, userId: message.senderID, handles: handles)
            : message.sender
        HStack(spacing: 4) {
            Text(message.senderRole?.senderBadge.map { name + " · " + $0 } ?? name)
                .macFont(.subheadline).fontWeight(.semibold).foregroundStyle(BipeColor.accentInk)
            if let mark = seals?.mark(for: message.senderID) {
                Image(mark.isSuspended ? "seal-warning" : "seal-check")
                    .renderingMode(.template).resizable().scaledToFit().frame(width: 14, height: 14)
                    .foregroundStyle(mark.tint).accessibilityLabel(mark.label)
            }
        }
    }
}

struct MacMessageView: View {
    let message: MacMessage
    let room: Room
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    @ObservedObject var media: MacMedia
    let isPrivateRoom: Bool
    let fromRequest: Bool
    let pauseSecondsLeft: (Date) -> Int
    let menuItems: [BipeMessageMenuItem]
    let choose: (BipeMessageMenuItem) -> Void
    let react: (String) -> Void
    let reactionPending: (String) -> Bool
    @State private var showingEmoji = false
    @State private var senders: MacReaction?

    var body: some View {
        Group {
            if let warning = message.warning, BipeMoneyPause.applies(to: warning) {
                TimelineView(.periodic(from: .now, by: 1)) { context in content(secondsLeft: pauseSecondsLeft(context.date)) }
            } else { content(secondsLeft: 0) }
        }
        .sheet(isPresented: $showingEmoji) { MacReactionPicker(pick: react) }
        .sheet(item: $senders) { MacReactionSenders(reaction: $0, room: room, media: media) }
    }

    private func content(secondsLeft: Int) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            if message.isOwn { Spacer(minLength: 48) }
            VStack(alignment: message.isOwn ? .trailing : .leading, spacing: 4) {
                BubbleStack(spacing: 4, trailingLast: true) {
                    if !message.isOwn, message.showsSender {
                        MacSenderLine(message: message)
                    }
                    if let photo = message.photo {
                        MacPhotoView(photo: photo, eventID: message.id, isOwn: message.isOwn,
                                     isPrivate: isPrivateRoom, fromRequest: fromRequest, richText: message.richText,
                                     pauseSecondsLeft: pauseSecondsLeft, media: media)
                    }
                    if !message.richText.text.isEmpty {
                        if secondsLeft > 0 { richText } else { richText.textSelection(.enabled) }
                    }
                    if let warning = message.warning { MacNotice(text: warning.message) }
                    if secondsLeft > 0 {
                        Text(BipeStrings.pauseCountdown(String(secondsLeft))).macFont(.caption).monospacedDigit()
                            .foregroundStyle(BipeColor.trustCautionInk)
                    }
                    HStack(spacing: 8) {
                        Text(message.timestamp, style: .time).macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
                        if message.pending { Text(BipeStrings.appTimelineSending).macFont(.caption).foregroundStyle(BipeColor.inkSecondary) }
                        if message.failed { Text(BipeStrings.appTimelineFailed).macFont(.caption).foregroundStyle(BipeColor.trustDangerInk) }
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .foregroundStyle(BipeColor.inkPrimary)
                .background(message.isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther,
                            in: RoundedRectangle(cornerRadius: MacDesign.corner))
                if !message.reactions.isEmpty {
                    MacReactionPills(reactions: message.reactions, pending: reactionPending, toggle: react, showSenders: { senders = $0 })
                }
            }.frame(maxWidth: 560, alignment: message.isOwn ? .trailing : .leading)
            if !message.isOwn { Spacer(minLength: 48) }
        }
        .environment(\.openURL, OpenURLAction { url in pauseSecondsLeft(.now) > 0 ? .handled : .systemAction })
        .contextMenu {
            if message.canReact {
                ControlGroup {
                    ForEach(BipeReactions.quickKeys, id: \.self) { key in
                        Button { react(key) } label: {
                            Label { Text(verbatim: key) } icon: {
                                Image(nsImage: MacReactionArtwork.image(for: key)).renderingMode(.original)
                            }
                        }.accessibilityLabel(BipeStrings.a11yReact + " " + key)
                            .disabled(reactionPending(key))
                    }
                }.controlGroupStyle(.palette)
                Button { showingEmoji = true } label: { Label(BipeStrings.appMessageActionMoreReactions, systemImage: "face.smiling") }
                Divider()
            }
            ForEach(menuItems, id: \.self) { item in
                Button(role: item.isDestructive ? .destructive : nil) { choose(item) } label: {
                    Label(item.title, systemImage: item.symbol)
                }.disabled(item == .copy && secondsLeft > 0)
            }
        }
        .accessibilityActions {
            if message.canReact { Button(BipeStrings.a11yReact) { showingEmoji = true } }
            ForEach(menuItems, id: \.self) { item in Button(item.title) { choose(item) }.disabled(item == .copy && pauseSecondsLeft(.now) > 0) }
        }
    }

    private var richText: some View {
        Text(MacRichText.attributed(message.richText, emoteSender: message.isEmote ? message.sender : nil, bodySize: bodySize))
            .macFont(.body).tint(BipeColor.accentInk).fixedSize(horizontal: false, vertical: true)
    }
}
