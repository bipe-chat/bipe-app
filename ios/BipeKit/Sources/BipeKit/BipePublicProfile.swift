// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// Same ids as `profileProblem()` in bipe-shared/tools/lib/registries.mjs and the API's `ProfileProblem`.
public enum BipeProfileProblem: String, Sendable, Equatable {
    case nameEmpty = "name_empty"
    case nameTooLong = "name_too_long"
    case nameReserved = "name_reserved"
    case bioTooLong = "bio_too_long"
    case bioTooManyLines = "bio_too_many_lines"
    /// Server only: turning the profile on needs a handle.
    case noHandle = "no_handle"
    /// App only (profile-edit, security rule 8): a mark that looks like the verified seal.
    case nameLookalikeEmoji = "name_lookalike_emoji"
    
    public var isNameProblem: Bool {
        switch self {
        case .nameEmpty, .nameTooLong, .nameReserved, .nameLookalikeEmoji: true
        case .bioTooLong, .bioTooManyLines, .noHandle: false
        }
    }
    
    public var localizedMessage: String {
        switch self {
        case .nameEmpty: BipeStrings.publicProfileNameEmpty
        case .nameTooLong: BipeStrings.publicProfileNameTooLong(String(BipeRegistries.profileNameMaxLength))
        case .nameReserved: BipeStrings.warningReservedName
        case .bioTooLong: BipeStrings.publicProfileBioTooLong(String(BipeRegistries.profileBioMaxLength))
        case .bioTooManyLines: BipeStrings.publicProfileBioTooManyLines(String(BipeRegistries.profileBioMaxLines))
        case .noHandle: BipeStrings.publicProfileNeedsHandle
        case .nameLookalikeEmoji: BipeStrings.warningNameLookalikeEmoji
        }
    }
}

/// Client-side profile checks for early feedback (ADR 0041). The server is the authority.
public nonisolated enum BipePublicProfileRules {
    /// Port of `profileProblem()`. Lengths count Unicode scalars, like JS `[...s].length`.
    public static func profileProblem(name: String, bio: String) -> BipeProfileProblem? {
        let trimmedName = trim(name)
        if trimmedName.unicodeScalars.isEmpty {
            return .nameEmpty
        }
        if trimmedName.unicodeScalars.count > BipeRegistries.profileNameMaxLength {
            return .nameTooLong
        }
        if NameNormalizer.reservedCategory(for: trimmedName) != nil {
            return .nameReserved
        }
        let trimmedBio = trim(bio)
        if trimmedBio.unicodeScalars.count > BipeRegistries.profileBioMaxLength {
            return .bioTooLong
        }
        // JS `split("\n")`: count scalars, because Swift treats "\r\n" as one Character.
        if trimmedBio.unicodeScalars.count(where: { $0 == "\n" }) + 1 > BipeRegistries.profileBioMaxLines {
            return .bioTooManyLines
        }
        return nil
    }
    
    /// JS `String.prototype.trim()`.
    public static func trim(_ input: String) -> String {
        let scalars = input.unicodeScalars
        guard let start = scalars.firstIndex(where: { !NameNormalizer.isJavaScriptWhitespace($0) }),
              let end = scalars.lastIndex(where: { !NameNormalizer.isJavaScriptWhitespace($0) }) else {
            return ""
        }
        return String(scalars[start...end])
    }
}

/// The bio from another user's public profile. Shows nothing when the user has none.
public struct BipePublicProfileBioView: View {
    private let userID: String
    @Environment(\.bipeHandleResolver) private var handleResolver
    @State private var bio: String?
    
    public init(userID: String) {
        self.userID = userID
    }
    
    public var body: some View {
        // A stack, not a bare `if`: `.task` must run while the bio is still nil.
        VStack(spacing: 0) {
            if let bio {
                Text(bio)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
        }
        .task(id: userID) {
            bio = await handleResolver?.publicProfile(for: userID)?.bio
        }
    }
}

// MARK: - Photo

#if canImport(UIKit)

/// Re-encodes a picked image for `PUT /v1/me/profile/photo`: sRGB JPEG, at most
/// `profilePhotoMaxPixels` on each side and `profilePhotoMaxBytes`. Drawing again drops all metadata.
public nonisolated enum BipeProfilePhoto {
    private static let qualities: [CGFloat] = [0.85, 0.75, 0.65, 0.55, 0.45, 0.35, 0.25]
    
    /// `nil` when the data is not an image or does not fit even at the lowest quality.
    public static func jpegData(from imageData: Data) -> Data? {
        guard let image = UIImage(data: imageData) else { return nil }
        return jpegData(from: image)
    }
    
    public static func jpegData(from image: UIImage) -> Data? {
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        guard pixelWidth > 0, pixelHeight > 0 else { return nil }
        
        let ratio = min(1, CGFloat(BipeRegistries.profilePhotoMaxPixels) / max(pixelWidth, pixelHeight))
        let size = CGSize(width: max(1, (pixelWidth * ratio).rounded(.down)),
                          height: max(1, (pixelHeight * ratio).rounded(.down)))
        
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { context in
            // JPEG has no alpha: paint transparent areas white, not black.
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        
        for quality in qualities {
            if let data = rendered.jpegData(compressionQuality: quality), data.count <= BipeRegistries.profilePhotoMaxBytes {
                return data
            }
        }
        return nil
    }
}
#endif
