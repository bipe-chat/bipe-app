// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI
import UIKit

/// Loads avatar thumbnails through the SDK media API and keeps them in memory.
final class MediaLoader {
    let client: Client
    private let cache = NSCache<NSString, UIImage>()

    init(client: Client) {
        self.client = client
    }

    func thumbnail(mxcUrl: String, size: Int) async -> UIImage? {
        let key = "\(mxcUrl)#\(size)" as NSString
        if let cached = cache.object(forKey: key) {
            return cached
        }
        guard let source = try? MediaSource.fromUrl(url: mxcUrl),
              let data = try? await client.getMediaThumbnail(mediaSource: source, width: UInt64(size), height: UInt64(size)),
              let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }
}

/// A round avatar: the thumbnail when there is one, else the first letter of the name.
struct AvatarView: View {
    let name: String
    let mxcUrl: String?
    let loader: MediaLoader
    var size: CGFloat = 48

    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Circle().fill(BipeColor.accentWash)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text(initial)
                    .font(.system(size: size * 0.42, weight: .semibold))
                    .foregroundStyle(BipeColor.accentInk)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
        .task(id: mxcUrl) {
            guard let mxcUrl else {
                image = nil
                return
            }
            image = await loader.thumbnail(mxcUrl: mxcUrl, size: Int(size * 3))
        }
    }

    private var initial: String {
        let letter = name.trimmingCharacters(in: CharacterSet(charactersIn: "@#!+ ")).first
        return letter.map { String($0).uppercased() } ?? ""
    }
}
