// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import QuickLook
import SwiftUI

/// Spec timeline-media: a file downloads only on demand. Quick Look owns a temporary SDK lease, never an external launch.
struct MacFileView: View {
    let file: MacFile
    @ObservedObject var media: MacMedia
    @State private var download: MacDownloadedFile?
    @State private var previewURL: URL?
    @State private var task: Task<Void, Never>?
    @State private var busy = false
    @State private var failed = false
    @State private var saveFailed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { open() } label: {
                HStack(spacing: 12) {
                    Image(decorative: "file").resizable().scaledToFit().frame(width: 28, height: 28)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(file.name.filename).macFont(.body).lineLimit(2)
                        if let size = file.sizeLabel { Text(size).macFont(.caption).foregroundStyle(BipeColor.inkSecondary) }
                    }
                    if busy { ProgressView() }
                }.bipeTapTarget()
            }.buttonStyle(.plain).disabled(busy)
                .accessibilityLabel(BipeStrings.appMediaFile + ": " + file.name.filename)
                .accessibilityValue(file.sizeLabel ?? "")
            Button(BipeStrings.appMediaSave) { save() }.disabled(busy)
            if failed { MacNotice(text: BipeStrings.appMediaFailed, danger: true) }
            if saveFailed { MacNotice(text: BipeStrings.appMediaSaveFailed, danger: true) }
        }
        .quickLookPreview($previewURL)
        .onChange(of: previewURL) { _, url in if url == nil { download = nil } }
        .onDisappear {
            task?.cancel(); task = nil; previewURL = nil; download = nil
        }
    }

    private func open() {
        busy = true; failed = false
        task = Task { @MainActor in
            defer { busy = false }
            do {
                let downloaded = try await media.download(file)
                try Task.checkCancellation()
                download = downloaded
                previewURL = downloaded.file
            } catch { if !Task.isCancelled { failed = true } }
        }
    }

    private func save() {
        busy = true; saveFailed = false
        task = Task { @MainActor in
            defer { busy = false }
            do { try await media.save(file) }
            catch { if !Task.isCancelled { saveFailed = true } }
        }
    }
}

struct MacFileComposer: View {
    let file: MacPreparedFile
    @ObservedObject var model: MacTimeline
    let maxUploadSize: () async -> UInt64?

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            HStack {
                Text(BipeStrings.appMediaFile).macFont(.title2).fontWeight(.semibold)
                Spacer()
                Button(BipeStrings.appCancel) { model.cancelAttachment() }.keyboardShortcut(.cancelAction)
            }
            Text(file.file.lastPathComponent).macFont(.headline)
            Text(ByteCountFormatter.string(fromByteCount: Int64(clamping: file.info.size ?? 0), countStyle: .file))
                .macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
            TextField(BipeStrings.appUploadCaptionPlaceholder, text: $model.imageCaption, axis: .vertical)
                .lineLimit(2...5).disabled(model.sending)
            if let error = model.uploadError { MacNotice(text: error, danger: true) }
            HStack {
                if model.sending { ProgressView() }
                Spacer()
                Button(BipeStrings.appUploadSend) { Task { await model.sendFile(maxUploadSize: await maxUploadSize()) } }
                    .buttonStyle(.bipeProminent).disabled(model.sending || !model.ready)
                    .keyboardShortcut(.return, modifiers: .command)
            }
        }.padding(MacDesign.sectionGap).frame(width: 560).background(BipeColor.surfaceBase)
    }
}
