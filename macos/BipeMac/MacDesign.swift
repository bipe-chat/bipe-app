// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
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

struct MacMessageView: View {
    let message: MacMessage
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    @ObservedObject var media: MacMedia
    let isPrivateRoom: Bool
    let fromRequest: Bool
    let pauseSecondsLeft: (Date) -> Int
    let onReply: () -> Void
    var body: some View {
        if let warning = message.warning, BipeMoneyPause.applies(to: warning) {
            TimelineView(.periodic(from: .now, by: 1)) { context in content(secondsLeft: pauseSecondsLeft(context.date)) }
        } else { content(secondsLeft: 0) }
    }

    private func content(secondsLeft: Int) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            if message.isOwn { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 4) {
                if !message.isOwn { Text(message.sender).macFont(.subheadline).fontWeight(.semibold).foregroundStyle(BipeColor.accentInk) }
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
                    Spacer(minLength: 0)
                    Text(message.timestamp, style: .time).macFont(.caption).foregroundStyle(BipeColor.inkSecondary)
                    if message.pending { Text(BipeStrings.appTimelineSending).macFont(.caption).foregroundStyle(BipeColor.inkSecondary) }
                    if message.failed { Text(BipeStrings.appTimelineFailed).macFont(.caption).foregroundStyle(BipeColor.trustDangerInk) }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8).frame(maxWidth: 560, alignment: .leading)
            .foregroundStyle(BipeColor.inkPrimary)
            .background(message.isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther,
                        in: RoundedRectangle(cornerRadius: MacDesign.corner))
            if !message.isOwn { Spacer(minLength: 48) }
        }
        .environment(\.openURL, OpenURLAction { url in pauseSecondsLeft(.now) > 0 ? .handled : .systemAction })
        .contextMenu {
            Button(BipeStrings.appMessageActionReply, action: onReply).disabled(!message.canReply || message.eventID == nil)
            Button(BipeStrings.appCopy) {
                guard pauseSecondsLeft(.now) == 0 else { return }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(message.body, forType: .string)
            }.disabled(secondsLeft > 0 || message.body.isEmpty)
        }
        .accessibilityAction(named: BipeStrings.appMessageActionReply) { onReply() }
    }

    private var richText: some View {
        Text(MacRichText.attributed(message.richText, emoteSender: message.isEmote ? message.sender : nil, bodySize: bodySize))
            .macFont(.body).tint(BipeColor.accentInk).fixedSize(horizontal: false, vertical: true)
    }
}
