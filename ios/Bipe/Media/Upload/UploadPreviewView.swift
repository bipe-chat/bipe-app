// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import ImageIO
import Phosphor
import SwiftUI

/// Spec upload-preview: the picked photos, videos or file full size, a caption field and Send.
struct UploadPreviewView: View {
    @StateObject var model: UploadPreviewModel
    let close: () -> Void

    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(BipeStrings.appCancel) { model.cancel() }
                    .foregroundStyle(BipeColor.accentInk)
                Spacer()
                if model.items.indices.contains(page) {
                    Button {
                        model.remove(index: page)
                        page = min(page, max(0, model.items.count - 1))
                    } label: {
                        PhosphorIcon.trash.image
                            .resizable()
                            .frame(width: 20, height: 20)
                            .foregroundStyle(BipeColor.glyphPrimary)
                            .padding(8)
                            .bipeTapTarget()
                    }
                    .accessibilityLabel(BipeStrings.catalogRemove)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            TabView(selection: $page) {
                ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                    PickedMediaPage(item: item, maxUploadLabel: model.maxUploadLabel)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: model.items.count > 1 ? .always : .never))

            if model.items.indices.contains(page), model.items[page].kind == .image {
                ImageDescriptionField(text: Binding(get: { model.items.indices.contains(page) ? model.items[page].description : "" },
                                                    set: { model.editDescription(index: page, text: $0) }))
            }

            HStack(alignment: .bottom, spacing: 8) {
                TextField(BipeStrings.appUploadCaptionPlaceholder,
                          text: Binding(get: { model.caption }, set: { model.editCaption($0) }),
                          axis: .vertical)
                    .lineLimit(1...4)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 20).fill(BipeColor.surfaceSunken))
                    .foregroundStyle(BipeColor.inkPrimary)
                Button {
                    Task { await model.send() }
                } label: {
                    Group {
                        if model.isPreparing {
                            ProgressView().tint(BipeColor.inkOnAccent)
                        } else {
                            PhosphorIcon.paperPlaneRight.image
                                .resizable()
                                .frame(width: 22, height: 22)
                        }
                    }
                    .frame(width: 22, height: 22)
                    .padding(9)
                    .foregroundStyle(model.canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                    .background(Circle().fill(model.canSend ? BipeColor.accentFill : BipeColor.surfaceSunken))
                    .bipeTapTarget()
                }
                .disabled(!model.canSend)
                .accessibilityLabel(BipeStrings.appUploadSend)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            if model.isPreparing {
                Text(BipeStrings.appUploadPreparing)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .padding(.bottom, 8)
            }
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.uploadPreview.rawValue)
        .task { await model.appear() }
        .onChange(of: model.isClosed) { _, closed in
            if closed { close() }
        }
    }
}

/// The description of the image on screen (spec upload-preview, bipe-chat/bipe#501).
private struct ImageDescriptionField: View {
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(BipeStrings.appUploadDescription)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(BipeColor.inkSecondary)
            TextField(BipeStrings.appUploadDescriptionPlaceholder, text: $text, axis: .vertical)
                .lineLimit(1...3)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                .foregroundStyle(BipeColor.inkPrimary)
                .accessibilityLabel(BipeStrings.appUploadDescription)
                .accessibilityHint(BipeStrings.appUploadDescriptionFooter)
            Text(BipeStrings.appUploadDescriptionFooter)
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}

private struct PickedMediaPage: View {
    let item: PickedMedia
    let maxUploadLabel: String?

    @State private var image: UIImage?

    var body: some View {
        VStack(spacing: 12) {
            Spacer(minLength: 0)
            switch item.kind {
            case .image, .video:
                ZStack {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                    } else {
                        ProgressView()
                    }
                    if item.kind == .video, image != nil {
                        PhosphorIcon.play.image
                            .resizable()
                            .frame(width: 26, height: 26)
                            .padding(14)
                            .mediaScrim(Circle())
                    }
                }
                .frame(maxWidth: .infinity)
            case .file:
                VStack(spacing: 8) {
                    PhosphorIcon.file.image
                        .resizable()
                        .frame(width: 56, height: 56)
                        .foregroundStyle(BipeColor.accentInk)
                    Text(item.filename)
                        .font(.headline)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Text(item.sizeLabel)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                .padding(24)
            }
            if item.isTooLarge, let maxUploadLabel {
                Text(BipeStrings.appUploadTooLarge(maxUploadLabel))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            Spacer(minLength: 0)
        }
        .task(id: item.id) { image = await preview() }
    }

    private func preview() async -> UIImage? {
        switch item.kind {
        case .image:
            guard let source = CGImageSourceCreateWithURL(item.localURL as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 1600,
            ]
            return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map(UIImage.init(cgImage:))
        case .video:
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: item.localURL))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 1600, height: 1600)
            return (try? await generator.image(at: .zero).image).map(UIImage.init(cgImage:))
        case .file:
            return nil
        }
    }
}
