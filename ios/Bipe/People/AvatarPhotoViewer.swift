// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec avatar-viewer (bipe-chat/bipe#829): a person's or a group's photo full screen, with zoom. No save and no share.
struct AvatarPhotoViewer: View {
    let name: String
    let mxcUrl: String?
    let publicPhotoUrl: URL?
    let loader: MediaLoader
    let close: () -> Void

    private enum Phase { case loading, shown(UIImage), failed }
    @State private var phase: Phase = .loading

    var body: some View {
        ZStack {
            BipeColor.surfaceBase.ignoresSafeArea()
            switch phase {
            case .loading:
                ProgressView()
            case .shown(let image):
                ZoomableImage(image: image)
                    .ignoresSafeArea()
                    .accessibilityLabel(name)
            case .failed:
                Text(name)
                    .font(.title2.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            VStack {
                HStack {
                    Button(action: close) {
                        PhosphorIcon.x.image
                            .resizable()
                            .frame(width: 22, height: 22)
                            .padding(10)
                            .bipeTapTarget()
                    }
                    .accessibilityLabel(BipeStrings.appClose)
                    Text(name)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Spacer()
                }
                .foregroundStyle(BipeColor.inkPrimary)
                .padding(.horizontal, 8)
                Spacer()
            }
        }
        .environment(\.colorScheme, .dark)
        .gesture(DragGesture(minimumDistance: 40).onEnded { value in
            // Swipe down closes (action close).
            if value.translation.height > 120, abs(value.translation.width) < 80 { close() }
        })
        .accessibilityIdentifier(BipeScreen.avatarViewer.rawValue)
        .task { await load() }
    }

    private func load() async {
        var image: UIImage?
        if let mxcUrl { image = await loader.thumbnail(mxcUrl: mxcUrl, size: 1600) }
        if image == nil, let publicPhotoUrl { image = await loader.publicPhoto(url: publicPhotoUrl) }
        phase = image.map { .shown($0) } ?? .failed
    }
}
