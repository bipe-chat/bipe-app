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
            Text(BipeStrings.appName).font(compact ? .headline : .largeTitle).fontWeight(.bold)
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
            Text(text).font(.callout).fixedSize(horizontal: false, vertical: true)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(danger ? BipeColor.trustDangerInk : BipeColor.trustCautionInk)
        .background(danger ? BipeColor.trustDangerWash : BipeColor.trustCautionWash,
                    in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(danger ? BipeColor.trustDangerLine : BipeColor.trustCautionLine))
    }
}

struct MacAvatar: View {
    let name: String
    var isCompany = false
    var body: some View {
        Text(String(name.trimmingCharacters(in: CharacterSet(charactersIn: "@ ")).prefix(1)).uppercased())
            .font(.headline).foregroundStyle(BipeColor.accentInk)
            .frame(width: 36, height: 36)
            .background(BipeColor.accentWash, in: RoundedRectangle(cornerRadius: isCompany ? 10 : 18))
            .accessibilityHidden(true)
    }
}

struct MacMessageView: View {
    let message: MacMessage
    var body: some View {
        HStack(alignment: .bottom, spacing: 12) {
            if message.isOwn { Spacer(minLength: 64) }
            VStack(alignment: .leading, spacing: 8) {
                if !message.isOwn { Text(message.sender).font(.subheadline).fontWeight(.semibold).foregroundStyle(BipeColor.accentInk) }
                Text(message.body).font(.body).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                if let warning = message.warning { MacNotice(text: warning.message) }
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    Text(message.timestamp, style: .time).font(.caption).foregroundStyle(BipeColor.inkSecondary)
                    if message.pending { Text(BipeStrings.appTimelineSending).font(.caption).foregroundStyle(BipeColor.inkSecondary) }
                    if message.failed { Text(BipeStrings.appTimelineFailed).font(.caption).foregroundStyle(BipeColor.trustDangerInk) }
                }
            }
            .padding(14).frame(maxWidth: 560, alignment: .leading)
            .foregroundStyle(BipeColor.inkPrimary)
            .background(message.isOwn ? BipeColor.bubbleOwn : BipeColor.bubbleOther,
                        in: RoundedRectangle(cornerRadius: MacDesign.corner))
            .overlay(RoundedRectangle(cornerRadius: MacDesign.corner).strokeBorder(BipeColor.lineSubtle))
            if !message.isOwn { Spacer(minLength: 64) }
        }
    }
}
