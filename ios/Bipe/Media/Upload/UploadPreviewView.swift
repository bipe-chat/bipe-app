// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import ImageIO
import Phosphor
import SwiftUI

/// Spec upload-preview: the picked photos, videos or file full size, a caption field and Send.
/// Every control keeps at least 16 pt from the screen edge and sits in from the rounded corners.
struct UploadPreviewView: View {
    @StateObject var model: UploadPreviewModel
    let close: () -> Void

    @State private var page = 0

    var body: some View {
        VStack(spacing: 0) {
            topBar

            TabView(selection: $page) {
                ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                    PickedMediaPage(item: item, maxUploadLabel: model.maxUploadLabel)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            if model.items.count > 1 {
                ThumbnailStrip(items: model.items, page: $page)
                    .padding(.top, 8)
            }

            bottomPanel
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.uploadPreview.rawValue)
        .task { await model.appear() }
        .onChange(of: model.isClosed) { _, closed in
            if closed { close() }
        }
    }

    private var topBar: some View {
        HStack {
            RoundIconButton(icon: .x, label: BipeStrings.appCancel) { model.cancel() }
            Spacer()
            if model.items.indices.contains(page) {
                RoundIconButton(icon: .trash, label: BipeStrings.catalogRemove) {
                    model.remove(index: page)
                    page = min(page, max(0, model.items.count - 1))
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private var bottomPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if model.items.indices.contains(page), model.items[page].kind == .image {
                ImageDescriptionField(text: Binding(get: { model.items.indices.contains(page) ? model.items[page].description : "" },
                                                    set: { model.editDescription(index: page, text: $0) }))
                    .id(model.items[page].id)
            }

            HStack(alignment: .bottom, spacing: 8) {
                TextField(BipeStrings.appUploadCaptionPlaceholder,
                          text: Binding(get: { model.caption }, set: { model.editCaption($0) }),
                          axis: .vertical)
                    .lineLimit(1...4)
                    // 22 pt line + 2 x 11 = 44 pt, the send button's height, so one line sits in the middle.
                    .padding(.vertical, 11)
                    .padding(.horizontal, 16)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
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
                    .padding(11)
                    .foregroundStyle(model.canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                    .bipeTapTarget()
                    .bipeGlass(in: Circle(), tint: model.canSend ? BipeColor.accentFill : nil)
                }
                .disabled(!model.canSend)
                .accessibilityLabel(BipeStrings.appUploadSend)
            }

            if model.isPreparing {
                Text(BipeStrings.appUploadPreparing)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 12)
    }
}

/// A 44 pt round glass button with one icon, for the top bar.
private struct RoundIconButton: View {
    let icon: PhosphorIcon
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            icon.image
                .resizable()
                .frame(width: 20, height: 20)
                .padding(12)
                .foregroundStyle(BipeColor.glyphPrimary)
                .bipeTapTarget()
                .bipeGlass(in: Circle())
        }
        .accessibilityLabel(label)
    }
}

/// One thumbnail per picked item when there is more than one; a tap shows that item.
private struct ThumbnailStrip: View {
    let items: [PickedMedia]
    @Binding var page: Int

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                        Button {
                            withBipeAnimation { page = index }
                        } label: {
                            PickedMediaThumbnail(item: item, isCurrent: index == page)
                        }
                        .accessibilityLabel(item.filename)
                        .accessibilityAddTraits(index == page ? .isSelected : [])
                        .id(index)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 2)
            }
            .onChange(of: page) { _, page in
                withBipeAnimation { proxy.scrollTo(page, anchor: .center) }
            }
        }
    }
}

private struct PickedMediaThumbnail: View {
    let item: PickedMedia
    let isCurrent: Bool

    @State private var image: UIImage?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        ZStack {
            BipeColor.surfaceSunken
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if item.kind == .file {
                PhosphorIcon.file.image
                    .resizable()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(BipeColor.accentInk)
            }
            if item.isTooLarge {
                PhosphorIcon.warningCircle.image
                    .resizable()
                    .frame(width: 18, height: 18)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        }
        .frame(width: 56, height: 56)
        .clipShape(shape)
        .overlay(shape.strokeBorder(isCurrent ? BipeColor.accentFill : BipeColor.lineSubtle, lineWidth: isCurrent ? 3 : 1))
        .opacity(isCurrent ? 1 : 0.7)
        .task(id: item.id) { image = await pickedMediaPreview(item, maxPixelSize: 168) }
    }
}

/// The description of the image on screen (spec upload-preview, bipe-chat/bipe#501).
/// Folded to one chip until the person taps it or the image has a description.
private struct ImageDescriptionField: View {
    @Binding var text: String

    @State private var isOpen = false
    @FocusState private var focused: Bool

    var body: some View {
        if isOpen || !text.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                TextField(BipeStrings.appUploadDescriptionPlaceholder, text: $text, axis: .vertical)
                    .focused($focused)
                    .lineLimit(1...3)
                    .padding(.vertical, 11)
                    .padding(.horizontal, 16)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .accessibilityLabel(BipeStrings.appUploadDescription)
                    .accessibilityHint(BipeStrings.appUploadDescriptionFooter)
                Text(BipeStrings.appUploadDescriptionFooter)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .padding(.horizontal, 16)
                    .accessibilityHidden(true)
            }
        } else {
            Button {
                isOpen = true
                focused = true
            } label: {
                HStack(spacing: 6) {
                    PhosphorIcon.plus.image
                        .resizable()
                        .frame(width: 16, height: 16)
                    Text(BipeStrings.appUploadDescription)
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(BipeColor.inkPrimary)
                .padding(.horizontal, 16)
                .frame(minHeight: BipeTapTarget.minimum)
                .bipeGlass(in: Capsule())
            }
            .accessibilityHint(BipeStrings.appUploadDescriptionFooter)
        }
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
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
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
            if item.sendFailed {
                Text(BipeStrings.appUploadFailed)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .task(id: item.id) { image = await pickedMediaPreview(item, maxPixelSize: 1600) }
    }
}

/// A preview image of a picked photo or video, at most `maxPixelSize` on its long side; nil for a file.
private func pickedMediaPreview(_ item: PickedMedia, maxPixelSize: CGFloat) async -> UIImage? {
    switch item.kind {
    case .image:
        guard let source = CGImageSourceCreateWithURL(item.localURL as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map(UIImage.init(cgImage:))
    case .video:
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: item.localURL))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixelSize, height: maxPixelSize)
        return (try? await generator.image(at: .zero).image).map(UIImage.init(cgImage:))
    case .file:
        return nil
    }
}
