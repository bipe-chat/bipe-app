// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec govbr-verify, guide (bipe-chat/bipe#1007): five example gov.br screens that the user swipes, each above its
/// caption. The app draws them in Bipe colours with gov.br's own labels. They never show a name, a CPF, a photo or a code.
struct GovbrGuide: View {
    /// The challenge file name, as the gov.br file picker shows it.
    let fileName: String

    @State private var page = 0

    var body: some View {
        TabView(selection: $page) {
            ForEach(GovbrGuidePage.allCases, id: \.self) { item in
                VStack(spacing: 16) {
                    GovbrMockScreen(page: item, fileName: fileName)
                        .frame(width: 200, height: 260)
                        .accessibilityHidden(true)
                    HStack(alignment: .top, spacing: 10) {
                        Text(verbatim: "\(item.rawValue + 1)")
                            .font(.subheadline.monospacedDigit().bold())
                            .foregroundStyle(BipeColor.inkSecondary)
                        Text(item.caption(fileName: fileName))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 36)
                .tag(item.rawValue)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .always))
        .indexViewStyle(.page(backgroundDisplayMode: .always))
        .frame(height: 420)
    }
}

enum GovbrGuidePage: Int, CaseIterable {
    case open, choose, sign, authorize, save

    func caption(fileName: String) -> String {
        switch self {
        case .open: BipeStrings.govbrGuideOpen
        case .choose: BipeStrings.govbrGuideChoose(fileName)
        case .sign: BipeStrings.govbrGuideSign
        case .authorize: BipeStrings.govbrGuideAuthorize
        case .save: BipeStrings.govbrGuideSave
        }
    }
}

/// One example screen. The highlighted part is what the user taps.
private struct GovbrMockScreen: View {
    let page: GovbrGuidePage
    let fileName: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(BipeColor.lineStrong, lineWidth: 1))
        .font(.caption)
        .foregroundStyle(BipeColor.inkPrimary)
    }

    @ViewBuilder
    private var content: some View {
        switch page {
        case .open:
            RoundedRectangle(cornerRadius: 8).fill(BipeColor.accentFill).frame(height: 44)
                .overlay(alignment: .leading) {
                    VStack(alignment: .leading, spacing: 5) {
                        MockLine(width: 60, color: BipeColor.inkOnAccent)
                        MockLine(width: 90, color: BipeColor.inkOnAccent.opacity(0.6))
                    }
                    .padding(.horizontal, 10)
                }
            Capsule().stroke(BipeColor.lineStrong, lineWidth: 1).frame(height: 20)
            MockRow()
            MockRow()
            Highlight {
                HStack(spacing: 6) {
                    Text(BipeStrings.govbrMockService).font(.caption2.bold()).lineLimit(2)
                    Spacer(minLength: 0)
                    PhosphorIcon.caretRight.image.resizable().frame(width: 10, height: 10)
                }
            }
            MockRow()
        case .choose:
            Text(BipeStrings.govbrMockSignFile).font(.caption.bold())
            MockSteps(active: 1)
            Highlight {
                Text(BipeStrings.govbrMockChoose).font(.caption2.bold()).frame(maxWidth: .infinity)
            }
            MockFile(name: fileName, icon: .file)
        case .sign:
            VStack(alignment: .leading, spacing: 6) {
                MockLine(width: 110)
                MockLine(width: 140)
                MockLine(width: 90)
                MockLine(width: 120)
                Spacer(minLength: 0)
                RoundedRectangle(cornerRadius: 4)
                    .strokeBorder(BipeColor.accentFill, style: StrokeStyle(lineWidth: 2, dash: [4, 3]))
                    .background(BipeColor.accentWash, in: RoundedRectangle(cornerRadius: 4))
                    .frame(width: 90, height: 30)
                    .frame(maxWidth: .infinity)
            }
            .padding(10)
            .frame(height: 150)
            .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(BipeColor.lineSubtle, lineWidth: 1))
            MockButton(title: BipeStrings.govbrMockSign)
        case .authorize:
            MockLine(width: 140)
            MockLine(width: 110)
            Text(BipeStrings.govbrMockCode).font(.caption2.bold()).padding(.top, 8)
            HStack(spacing: 8) {
                ForEach(0..<6, id: \.self) { _ in Circle().fill(BipeColor.inkSecondary).frame(width: 6, height: 6) }
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: 28, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(BipeColor.lineStrong, lineWidth: 1))
            MockButton(title: BipeStrings.govbrMockAuthorize)
        case .save:
            Text(BipeStrings.govbrMockSignFile).font(.caption.bold())
            MockSteps(active: 3)
            MockLine(width: 140)
            MockLine(width: 100)
            MockFile(name: BipeStrings.govbrMockSignedName(fileName.replacingOccurrences(of: ".pdf", with: "")), icon: .file)
            Highlight {
                HStack(spacing: 6) {
                    PhosphorIcon.downloadSimple.image.resizable().frame(width: 12, height: 12)
                    Text(BipeStrings.govbrMockDownload).font(.caption2.bold())
                }
                .foregroundStyle(BipeColor.accentInk)
                .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct MockLine: View {
    let width: CGFloat
    var color: Color = BipeColor.lineStrong

    var body: some View {
        Capsule().fill(color).frame(width: width, height: 6)
    }
}

private struct MockRow: View {
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(BipeColor.lineSubtle).frame(width: 14, height: 14)
            MockLine(width: 100)
        }
        .frame(height: 22)
    }
}

/// The part to tap: accent wash with an accent outline.
private struct Highlight<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(.horizontal, 8)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(BipeColor.accentWash, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(BipeColor.accentFill, lineWidth: 2))
    }
}

private struct MockSteps: View {
    let active: Int

    var body: some View {
        HStack(spacing: 0) {
            ForEach(1...3, id: \.self) { step in
                ZStack {
                    Circle().fill(step == active ? BipeColor.accentFill : BipeColor.surfaceBase)
                    Circle().stroke(step == active ? BipeColor.accentFill : BipeColor.lineStrong, lineWidth: 1)
                    Text(verbatim: "\(step)").font(.caption2.bold())
                        .foregroundStyle(step == active ? BipeColor.inkOnAccent : BipeColor.inkSecondary)
                }
                .frame(width: 20, height: 20)
                if step < 3 {
                    Rectangle().fill(BipeColor.lineStrong).frame(height: 1)
                }
            }
        }
    }
}

private struct MockFile: View {
    let name: String
    let icon: PhosphorIcon

    var body: some View {
        HStack(spacing: 6) {
            icon.image.resizable().frame(width: 14, height: 14).foregroundStyle(BipeColor.trustDangerInk)
            Text(name).font(.caption2).lineLimit(1).truncationMode(.middle)
        }
    }
}

private struct MockButton: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.caption2.bold())
            .foregroundStyle(BipeColor.inkOnAccent)
            .frame(maxWidth: .infinity, minHeight: 26)
            .background(BipeColor.accentFill, in: Capsule())
            .overlay(Capsule().inset(by: -3).stroke(BipeColor.accentFill.opacity(0.5), lineWidth: 2))
    }
}
