// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

struct MacPhotoView: View {
    let photo: MacPhoto
    let eventID: String
    let isOwn: Bool
    let isPrivate: Bool
    let fromRequest: Bool
    let richText: BipeMessageText
    let pauseSecondsLeft: (Date) -> Int
    @ObservedObject var media: MacMedia
    @State private var revealed = false
    @State private var image: NSImage?
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    @State private var failed = false
    @State private var viewer = false
    @State private var retry = 0

    private var shows: Bool { revealed || media.showsAutomatically(isOwn: isOwn, isPrivate: isPrivate, fromRequest: fromRequest) }
    private var label: String { photo.description.map(BipeStrings.appMediaImageDescribed) ?? BipeStrings.appMediaImage }

    var body: some View {
        Group {
            if shows {
                if let image {
                    Button { viewer = true } label: {
                        MacAnimatedImage(image: image).frame(width: previewWidth, height: previewHeight).bipeTapTarget()
                    }.buttonStyle(.plain).accessibilityLabel(label)
                } else if failed {
                    Button(BipeStrings.appMediaFailed) { failed = false; retry += 1 }.buttonStyle(.bipeBordered)
                } else {
                    ProgressView().frame(width: 220, height: 160).accessibilityLabel(BipeStrings.appMediaImage)
                }
            } else {
                Button(BipeStrings.appMediaTapToShow) { revealed = true }.buttonStyle(.bipeBordered)
            }
        }
        .task(id: "\(shows)-\(retry)") {
            guard shows else { image = nil; return }
            do { image = try await media.image(photo, eventID: eventID) }
            catch { if !Task.isCancelled { failed = true } }
        }
        .sheet(isPresented: $viewer) { MacPhotoViewer(photo: photo, eventID: eventID, richText: richText, pauseSecondsLeft: pauseSecondsLeft, media: media) }
    }

    private var previewWidth: CGFloat {
        let width = CGFloat(photo.content.info?.width ?? 320), height = CGFloat(photo.content.info?.height ?? 240)
        return min(260, 300 * max(0.2, min(5, width / max(1, height))))
    }
    private var previewHeight: CGFloat {
        let width = CGFloat(photo.content.info?.width ?? 320), height = CGFloat(photo.content.info?.height ?? 240)
        return min(300, previewWidth * max(0.2, min(5, height / max(1, width))))
    }
}

private struct MacPhotoViewer: View {
    let photo: MacPhoto
    let eventID: String
    let richText: BipeMessageText
    let pauseSecondsLeft: (Date) -> Int
    @ObservedObject var media: MacMedia
    @Environment(\.dismiss) private var dismiss
    @State private var image: NSImage?
    @ScaledMetric(relativeTo: .body) private var bodySize: CGFloat = 17
    @State private var failed = false
    @State private var saving = false
    @State private var saveFailed = false
    @State private var retry = 0

    var body: some View {
        VStack(spacing: MacDesign.gap) {
            HStack {
                Text(photo.content.filename).macFont(.headline).lineLimit(1)
                Spacer()
                Button(BipeStrings.appMediaSave) {
                    Task {
                        saving = true; saveFailed = false
                        defer { saving = false }
                        do { try await media.save(photo) } catch { saveFailed = true }
                    }
                }.disabled(saving || image == nil)
                Button(BipeStrings.appClose) { dismiss() }.keyboardShortcut(.cancelAction)
            }
            if let image {
                MacAnimatedImage(image: image).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(photo.description.map(BipeStrings.appMediaImageDescribed) ?? BipeStrings.appMediaImage)
            } else if failed {
                Button(BipeStrings.appMediaFailed) { failed = false; retry += 1 }.buttonStyle(.bipeBordered)
            } else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
            // The same money pause as the timeline: no copying and no links until it ends.
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if pauseSecondsLeft(context.date) > 0 { captionText } else { captionText.textSelection(.enabled) }
            }
            if saveFailed { MacNotice(text: BipeStrings.appMediaSaveFailed, danger: true) }
        }.padding(MacDesign.sectionGap).frame(minWidth: 640, idealWidth: 860, minHeight: 480, idealHeight: 680)
            .background(BipeColor.surfaceBase)
            .environment(\.openURL, OpenURLAction { _ in pauseSecondsLeft(.now) > 0 ? .handled : .systemAction })
            .task(id: retry) {
                do { image = try await media.image(photo, eventID: eventID, fullSize: true) }
                catch { if !Task.isCancelled { failed = true } }
            }
    }

    private var captionText: some View {
        VStack(spacing: MacDesign.gap) {
            if let description = photo.description { Text(description).macFont(.callout) }
            if !richText.text.isEmpty { Text(MacRichText.attributed(richText, bodySize: bodySize)) }
        }
    }
}

/// AppKit animates GIFs without a web view. Reduce Motion leaves the first frame still.
private struct MacAnimatedImage: NSViewRepresentable {
    let image: NSImage
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeNSView(context: Context) -> NSImageView {
        let view = NSImageView()
        view.imageScaling = .scaleProportionallyUpOrDown
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }
    func updateNSView(_ view: NSImageView, context: Context) {
        view.image = image
        view.animates = !reduceMotion
    }
}

enum MacRichText {
    static func attributed(_ text: BipeMessageText, emoteSender: String? = nil, bodySize: CGFloat = 17) -> AttributedString {
        var result = AttributedString(emoteSender.map { "* \($0) " } ?? "")
        for run in text.runs {
            var part = AttributedString(run.text)
            var font = Font.system(size: bodySize, design: run.code ? .monospaced : .rounded)
            if run.bold { font = font.bold() }
            if run.italic { font = font.italic() }
            part.font = font
            if run.strike { part.strikethroughStyle = Text.LineStyle(pattern: .solid) }
            if let link = run.link, let url = URL(string: link) {
                part.link = url
                if BipeProfileLink.matrixUserID(fromPermalink: url) != nil {
                    part.backgroundColor = BipeColor.surfaceSunken
                    part.foregroundColor = BipeColor.accentInk
                }
            }
            result += part
        }
        return result
    }
}
