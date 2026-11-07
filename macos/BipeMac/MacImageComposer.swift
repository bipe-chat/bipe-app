// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

struct MacImageComposer: View {
    let image: MacPreparedImage
    @ObservedObject var model: MacTimeline
    let maxUploadSize: () async -> UInt64?
    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            HStack {
                Text(BipeStrings.appMediaImage).macFont(.title2).fontWeight(.semibold)
                Spacer()
                Button(BipeStrings.appCancel) { model.cancelAttachment() }.keyboardShortcut(.cancelAction)
            }
            if let preview = NSImage(contentsOf: image.thumbnail) {
                Image(nsImage: preview).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 320)
                    .accessibilityLabel(BipeStrings.appMediaImage)
            }
            TextField(BipeStrings.appUploadCaptionPlaceholder, text: $model.imageCaption, axis: .vertical).lineLimit(2...5).disabled(model.sending)
            TextField(BipeStrings.appUploadDescriptionPlaceholder, text: $model.imageDescription, axis: .vertical)
                .lineLimit(2...4).accessibilityLabel(BipeStrings.appUploadDescription)
                .disabled(model.sending)
            Text(BipeStrings.appUploadDescriptionFooter).macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
            if let error = model.uploadError { MacNotice(text: error, danger: true) }
            HStack {
                if model.sending { ProgressView() }
                Spacer()
                Button(BipeStrings.appUploadSend) { Task { await model.sendImage(maxUploadSize: await maxUploadSize()) } }
                    .buttonStyle(.bipeProminent).disabled(model.sending || !model.ready)
                    .keyboardShortcut(.return, modifiers: .command)
            }
        }.padding(MacDesign.sectionGap).frame(width: 560).background(BipeColor.surfaceBase)
    }
}
