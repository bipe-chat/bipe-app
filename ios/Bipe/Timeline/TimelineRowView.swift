// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// One row of spec timeline-rows.
struct TimelineRowView: View {
    let row: TimelineRow
    let isDm: Bool
    let isHighlighted: Bool
    @ObservedObject var model: ChatTimelineModel
    let media: MediaLoader
    @ObservedObject var voicePlayer: VoicePlayer
    var openPoll: (String) -> Void = { _ in }
    /// Action open of spec timeline-media: the row id for media-viewer.
    var openViewer: (String) -> Void = { _ in }
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        switch row.kind {
        case .message:
            if let message = row.message, let details = row.details {
                if !message.isOwn, message.media == nil, message.voice == nil, message.location == nil,
                   let institution = seals?.institution(for: message.senderId) {
                    NoticeCard(message: message, institution: institution)
                        .contentShape(Rectangle())
                        .contextMenu { MessageContextActions(model: model, rowId: row.id) }
                        .accessibilityAction(named: BipeStrings.messageActions) { model.openActions(rowId: row.id) }
                } else {
                    MessageBubbleRow(row: row, message: message, details: details, isDm: isDm,
                                     isHighlighted: isHighlighted, model: model, media: media,
                                     voicePlayer: voicePlayer, openViewer: openViewer)
                }
            }
        case .undecryptable:
            if let message = row.message {
                VStack(alignment: message.isOwn ? .trailing : .leading, spacing: 0) {
                    UndecryptableRow(message: message)
                        .contentShape(Rectangle())
                        .contextMenu { MessageContextActions(model: model, rowId: row.id) }
                        .accessibilityAction(named: BipeStrings.messageActions) { model.openActions(rowId: row.id) }
                }
            }
        case .deleted:
            if let message = row.message {
                DeletedRow(message: message)
            }
        case .poll:
            if let message = row.message, let poll = message.poll,
               poll.isQuestionShape, !message.isOwn, case .verified(_)? = seals?.state(for: message.senderId) {
                // Spec question-card: a company's structured question (bipe#244). Fails closed to the poll card.
                Bubble(isOwn: false, trailingLast: true) {
                    QuestionCard(poll: poll, companyId: message.senderId, companyName: message.senderName,
                                 seal: seals?.state(for: message.senderId) ?? .none, model: model)
                    MessageTimestamp(sentAt: message.sentAt)
                }
            } else if let message = row.message, let poll = message.poll {
                Button {
                    openPoll(poll.eventId)
                } label: {
                    Bubble(isOwn: message.isOwn, trailingLast: true) {
                        if !message.isOwn && !isDm {
                            Text(message.senderName)
                                .font(.caption.bold())
                                .foregroundStyle(BipeColor.accentInk)
                        }
                        PollCard(poll: poll)
                        MessageTimestamp(sentAt: message.sentAt)
                    }
                }
                .buttonStyle(.plain)
            }
        case .dateSeparator:
            if let day = row.day {
                SystemLine(text: daySeparatorText(day))
                    .accessibilityAddTraits(.isHeader)
            }
        case .readMarker:
            HStack(spacing: 8) {
                Rectangle().fill(BipeColor.accentFill).frame(height: 1)
                Text(BipeStrings.appTimelineNewMessages)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.accentInk)
                Rectangle().fill(BipeColor.accentFill).frame(height: 1)
            }
            .padding(.vertical, 6)
        case .handleChange:
            if let line = row.handleChange, let text = line.change.line(name: line.name) {
                SystemLine(text: text)
                    .accessibilityIdentifier(BipeScreen.handleChangeLine.rawValue)
            }
        case .notEncrypted:
            SystemLine(text: BipeStrings.appTimelineNotEncrypted)
        case .chatStart:
            SystemLine(text: BipeStrings.appTimelineStart)
        case .call:
            if let text = row.callText { SystemLine(text: text) }
        }
    }
}

/// Spec timeline-rows notes: today, yesterday, else the system date in the app language, with the year only when it is not this year.
func daySeparatorText(_ day: Date) -> String {
    let calendar = Calendar.current
    if calendar.isDateInToday(day) { return BipeStrings.appTimelineToday }
    if calendar.isDateInYesterday(day) { return BipeStrings.appTimelineYesterday }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "pt-BR")
    let sameYear = calendar.component(.year, from: day) == calendar.component(.year, from: Date())
    formatter.setLocalizedDateFormatFromTemplate(sameYear ? "EEEEdMMMM" : "dMMMMyyyy")
    return formatter.string(from: day)
}

/// A quiet centred line: date separators, the chat start and the not-encrypted notice.
private struct SystemLine: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(BipeColor.inkSecondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(BipeColor.surfaceSunken))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
    }
}

/// The bubble shape and alignment of a message row.
private struct Bubble<Content: View>: View {
    let isOwn: Bool
    var isHighlighted = false
    /// The last view (the footer of a message) sits at the trailing edge.
    var trailingLast = false
    /// A sticker has no bubble (spec timeline-media).
    var isPlain = false
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack {
            if isOwn { Spacer(minLength: 48) }
            BubbleStack(spacing: 4, trailingLast: trailingLast) {
                content()
            }
            .padding(.horizontal, isPlain ? 0 : 12)
            .padding(.vertical, isPlain ? 0 : 8)
            .background(RoundedRectangle(cornerRadius: 16).fill(isPlain ? .clear : isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(BipeColor.accentFill, lineWidth: isHighlighted ? 2 : 0))
            if !isOwn { Spacer(minLength: 48) }
        }
    }
}

/// kind message: sender, reply quote, text, time, edited label, receipt, reactions. Long press opens
/// message-actions; a swipe right starts a reply.
private struct MessageBubbleRow: View {
    let row: TimelineRow
    let message: TimelineMessage
    let details: MessageDetails
    let isDm: Bool
    let isHighlighted: Bool
    @ObservedObject var model: ChatTimelineModel
    let media: MediaLoader
    @ObservedObject var voicePlayer: VoicePlayer
    let openViewer: (String) -> Void

    @State private var swipe: CGFloat = 0
    private let swipeThreshold: CGFloat = 60

    var body: some View {
        VStack(alignment: message.isOwn ? .trailing : .leading, spacing: 4) {
            ZStack(alignment: .leading) {
                PhosphorIcon.arrowBendUpLeft.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .opacity(Double(min(1, swipe / swipeThreshold)))
                    .accessibilityHidden(true)
                Bubble(isOwn: message.isOwn, isHighlighted: isHighlighted, trailingLast: true,
                       isPlain: message.media?.kind == .sticker) {
                    if !message.isOwn && !isDm {
                        Text(message.senderName)
                            .font(.caption.bold())
                            .foregroundStyle(BipeColor.accentInk)
                    }
                    if let reply = details.reply {
                        ReplyQuote(reply: reply)
                            // a11y: the row has the action "Show the original message" for VoiceOver.
                            .onTapGesture { Task { await model.openOriginal(eventId: reply.eventId) } }
                            .task { await model.loadReply(rowId: row.id) }
                    }
                    content
                    if let warning = details.scamWarning {
                        ScamWarningCard(kind: warning, pauseSecondsLeft: { model.pauseSecondsLeft(rowId: row.id, now: $0) })
                            .onAppear { model.startMoneyPause(rowId: row.id, kind: warning) }
                    }
                    footer
                }
                .offset(x: swipe)
            }
            .contentShape(Rectangle())
            .contextMenu { MessageContextActions(model: model, rowId: row.id) }
            .simultaneousGesture(swipeGesture)
            .accessibilityElement(children: .combine)
            .accessibilityAction(named: BipeStrings.appMessageActionReply) { model.startReply(rowId: row.id) }
            .accessibilityAction(named: BipeStrings.messageActions) { model.openActions(rowId: row.id) }
            .accessibilityActions {
                // React without the long press menu (bipe#499).
                if model.showsReactions(row) {
                    Button(BipeStrings.a11yReact) { model.moreReactions(rowId: row.id) }
                }
                if let reply = details.reply {
                    Button(BipeStrings.a11yOpenOriginal) { Task { await model.openOriginal(eventId: reply.eventId) } }
                }
            }
            if model.showsTheftMarker(message) {
                TheftMessageMarker()
            }
            if !details.reactions.isEmpty {
                ReactionPills(reactions: details.reactions,
                              onToggle: { key in Task { await model.toggleReaction(rowId: row.id, key: key) } },
                              onLongPress: { key in model.openSenders(rowId: row.id, key: key) })
            }
            if let receipt = details.receipt, !isDm, !receipt.readers.isEmpty {
                ReaderAvatars(readers: receipt.readers, media: media)
                    // a11y: ReaderAvatars is one button for VoiceOver, with its own label.
                    .onTapGesture { model.openReaders(rowId: row.id) }
            }
        }
    }

    /// Text, or the media, location and voice content of specs timeline-media, location-view and voice-message.
    @ViewBuilder
    private var content: some View {
        if let mediaMessage = message.media {
            TimelineMediaRow(message: message, media: mediaMessage, loader: media,
                             isPrivateRoom: model.isPrivateRoom,
                             openViewer: { openViewer(row.id) },
                             cancelUpload: { Task { await model.cancelUpload(id: row.id) } })
        } else if let location = message.location {
            // Spec location-view: the tap opens the full map (ChatTimelineView shows it for a location row).
            TimelineLocationRow(location: location, openFull: { openViewer(row.id) })
        } else if let voice = message.voice {
            VoiceMessageRow(message: message, voice: voice, loader: media, player: voicePlayer,
                            voiceMedia: model.voiceMedia, hasScamWarning: row.details?.scamWarning != nil)
        } else {
            Text(message.body ?? "")
                .foregroundStyle(BipeColor.inkPrimary)
        }
    }

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 20)
            .onChanged { value in
                guard details.canReply, value.translation.width > 0,
                      abs(value.translation.width) > abs(value.translation.height) else { return }
                let next = min(value.translation.width, swipeThreshold * 1.4)
                if (swipe < swipeThreshold) != (next < swipeThreshold) {
                    BipeHaptics.play(next >= swipeThreshold ? .threshold : .release)
                }
                swipe = next
            }
            .onEnded { _ in
                if swipe >= swipeThreshold { model.startReply(rowId: row.id) }
                withBipeAnimation(.spring(duration: 0.25)) { swipe = 0 }
            }
    }

    /// Time, edited label (message-edit) and the tick of an own message (read-receipts).
    private var footer: some View {
        HStack(spacing: 4) {
            MessageTimestamp(sentAt: message.sentAt)
            if details.isEdited {
                Text(BipeStrings.appMessageEdited)
            }
            if let receipt = details.receipt {
                ReceiptTick(receipt: receipt) { model.openActions(rowId: row.id) }
            }
        }
        .font(.caption2)
        .foregroundStyle(BipeColor.inkSecondary)
    }
}

/// Every event message keeps its clock time; accessibility also exposes the date.
private struct MessageTimestamp: View {
    let sentAt: Date

    var body: some View {
        Text(sentAt.formatted(date: .omitted, time: .shortened))
            .font(.caption2)
            .monospacedDigit()
            .foregroundStyle(BipeColor.inkSecondary)
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityLabel(sentAt.formatted(date: .complete, time: .shortened))
    }
}

/// Spec scam-warning: a calm caution card. Title then the kind text, one element for screen readers.
private struct ScamWarningCard: View {
    let kind: ScamWarningKind
    /// Seconds left of the money pause at a date (0: none).
    let pauseSecondsLeft: (Date) -> Int

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 16, height: 16)
                .foregroundStyle(BipeColor.trustCautionInk)
            VStack(alignment: .leading, spacing: 2) {
                Text(BipeStrings.scamWarningTitle)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.trustCautionInk)
                Text(kind.message)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if pauseSecondsLeft(.now) > 0 {
                    // The number changes once a second, with no animation (reduced motion or not).
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let left = pauseSecondsLeft(context.date)
                        if left > 0 {
                            Text(BipeStrings.pauseCountdown(String(left)))
                                .font(.caption.monospacedDigit().bold())
                                .foregroundStyle(BipeColor.trustCautionInk)
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.trustCautionWash))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(BipeColor.trustCautionLine, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(BipeScreen.scamWarning.rawValue)
    }
}

/// Spec read-receipts: the tick of an own message.
private struct ReceiptTick: View {
    let receipt: MessageReceipt
    let onFailedTap: () -> Void

    var body: some View {
        switch receipt.tick {
        case .sending:
            icon(.clock)
            Text(BipeStrings.appTimelineSending)
        case .sent:
            icon(.check)
                .accessibilityLabel(BipeStrings.appReceiptSent)
            if receipt.showsLabel { Text(BipeStrings.appReceiptSent).accessibilityHidden(true) }
        case .read:
            icon(.checks)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityLabel(BipeStrings.appReceiptRead)
            if receipt.showsLabel { Text(BipeStrings.appReceiptRead).accessibilityHidden(true) }
        case .failed:
            Button(action: onFailedTap) {
                HStack(spacing: 4) {
                    icon(.warningCircle)
                    Text(BipeStrings.appTimelineFailed)
                }
                .foregroundStyle(BipeColor.trustDangerInk)
            }
            .buttonStyle(.plain)
        }
    }

    private func icon(_ icon: PhosphorIcon) -> some View {
        icon.image
            .resizable()
            .frame(width: 14, height: 14)
    }
}

/// Spec read-receipts, group: at most 3 avatars and a count.
private struct ReaderAvatars: View {
    let readers: [ReceiptReader]
    let media: MediaLoader

    var body: some View {
        HStack(spacing: -4) {
            ForEach(readers.prefix(3)) { reader in
                AvatarView(name: reader.name, mxcUrl: reader.avatarUrl, loader: media, size: 16, userId: reader.userId)
                    .overlay(Circle().stroke(BipeColor.surfaceBase, lineWidth: 1))
            }
            if readers.count > 3 {
                Text("+\(readers.count - 3)")
                    .font(.caption2)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .padding(.leading, 6)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(BipeStrings.appReceiptReadBy(readers.count.formatted()))
        .accessibilityAddTraits(.isButton)
    }
}

/// Spec message-reply: the quote of the original inside the reply.
struct ReplyQuote: View {
    let reply: MessageReply

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(BipeColor.accentFill)
                .frame(width: 3)
            VStack(alignment: .leading, spacing: 2) {
                switch reply.state {
                case .loading:
                    Text(BipeStrings.appMessageReplyLoading)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .unavailable:
                    Text(BipeStrings.appMessageReplyUnavailable)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .ready:
                    Text(reply.senderName ?? "")
                        .bold()
                        .foregroundStyle(BipeColor.accentInk)
                    Text(reply.preview ?? "")
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(1)
                }
            }
            .font(.caption)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 8).fill(BipeColor.surfaceSunken.opacity(0.6)))
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Spec message-reactions: the pills under a message.
private struct ReactionPills: View {
    let reactions: [MessageReaction]
    let onToggle: (String) -> Void
    let onLongPress: (String) -> Void

    var body: some View {
        FlowRow(spacing: 4) {
            ForEach(reactions) { reaction in
                HStack(spacing: 4) {
                    Text(reactionKeyText(reaction.key))
                    Text(reaction.count.formatted())
                        .font(.caption)
                        .foregroundStyle(reaction.includesOwn ? BipeColor.accentInk : BipeColor.inkSecondary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Capsule().fill(reaction.includesOwn ? BipeColor.accentWash : BipeColor.surfaceSunken))
                .overlay(Capsule().stroke(reaction.includesOwn ? BipeColor.accentFill : BipeColor.lineSubtle, lineWidth: 1))
                .opacity(reaction.isSending ? 0.6 : 1)
                .onTapGesture { onToggle(reaction.key) }
                .onLongPressGesture(minimumDuration: 0.4) { onLongPress(reaction.key) }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(BipeStrings.appReactionLabel(reaction.key, reaction.count.formatted()))
                .accessibilityAddTraits(reaction.includesOwn ? [.isButton, .isSelected] : .isButton)
                .accessibilityAction(named: BipeStrings.appReactionsTitle) { onLongPress(reaction.key) }
            }
        }
    }
}

/// Spec message-reactions notes: keys longer than one grapheme show in full up to 16 characters, then cut.
func reactionKeyText(_ key: String) -> String {
    key.count > 16 ? String(key.prefix(16)) + "…" : key
}

/// The column of a bubble: as wide as its widest view, views at the leading edge, the last one at the trailing edge when asked.
private struct BubbleStack: Layout {
    var spacing: CGFloat
    var trailingLast: Bool

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) }
        let width = sizes.map(\.width).max() ?? 0
        let height = sizes.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            let isTrailing = trailingLast && index == subviews.count - 1
            subview.place(at: CGPoint(x: isTrailing ? bounds.maxX - size.width : bounds.minX, y: y),
                          proposal: ProposedViewSize(size))
            y += size.height + spacing
        }
    }
}

/// A row that wraps its items onto new lines.
private struct FlowRow: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: maxX, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// Spec message-undecryptable: a quiet line in the sender's bubble position.
private struct UndecryptableRow: View {
    let message: TimelineMessage
    @Environment(\.openHistoryHelp) private var openHistoryHelp

    var body: some View {
        Bubble(isOwn: message.isOwn, trailingLast: true) {
            if !message.isOwn {
                Text(message.senderName)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            HStack(alignment: .top, spacing: 6) {
                PhosphorIcon.lockSimple.image
                    .resizable()
                    .frame(width: 14, height: 14)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .accessibilityHidden(true)
                Text(text)
                    .font(.subheadline.italic())
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            // Action openHelp: only historyNotOnThisPhone has a way to help.
            if message.cause == .historyNotOnThisPhone {
                Button(action: openHistoryHelp) {
                    Text(BipeStrings.appUtdHelp)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.accentInk)
                }
                .buttonStyle(.plain)
            }
            MessageTimestamp(sentAt: message.sentAt)
        }
        .accessibilityIdentifier(BipeScreen.messageUndecryptable.rawValue)
    }

    private var text: String {
        switch message.cause {
        case .beforeJoined: BipeStrings.appUtdBeforeJoined
        case .historyNotOnThisPhone: BipeStrings.appUtdHistory
        case .notSharedWithThisPhone: BipeStrings.appUtdNotShared
        case .unapprovedSenderDevice: BipeStrings.appUtdUnapprovedDevice(message.senderName)
        case .waiting, .none: BipeStrings.appTimelineUndecryptable
        }
    }
}

/// Spec timeline-rows kind deleted: no reactions, quote, receipt or actions.
private struct DeletedRow: View {
    let message: TimelineMessage

    var body: some View {
        Bubble(isOwn: message.isOwn, trailingLast: true) {
            HStack(spacing: 6) {
                PhosphorIcon.trash.image
                    .resizable()
                    .frame(width: 14, height: 14)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .accessibilityHidden(true)
                Text(message.isOwn ? BipeStrings.appMessageDeletedOwn : BipeStrings.appMessageDeleted)
                    .font(.subheadline.italic())
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            MessageTimestamp(sentAt: message.sentAt)
        }
    }
}
