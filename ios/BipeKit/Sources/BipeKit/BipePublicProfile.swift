// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
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
    /// `socialHandleProblem()` (ADR 0228). The server does not name the network: `BipeSocialHandles` finds the field.
    case socialInvalid = "social_invalid"
    case socialReserved = "social_reserved"
    
    public var isNameProblem: Bool {
        switch self {
        case .nameEmpty, .nameTooLong, .nameReserved, .nameLookalikeEmoji: true
        case .bioTooLong, .bioTooManyLines, .noHandle, .socialInvalid, .socialReserved: false
        }
    }
    
    public var isSocialProblem: Bool {
        self == .socialInvalid || self == .socialReserved
    }
    
    /// Without `network`, socialInvalid has no text of its own and shows the generic error.
    public var localizedMessage: String {
        localizedMessage(network: nil)
    }
    
    /// `network`: the registry name of the social field that has the problem.
    public func localizedMessage(network: String?) -> String {
        switch self {
        case .nameEmpty: BipeStrings.publicProfileNameEmpty
        case .nameTooLong: BipeStrings.publicProfileNameTooLong(String(BipeRegistries.profileNameMaxLength))
        case .nameReserved: BipeStrings.warningReservedName
        case .bioTooLong: BipeStrings.publicProfileBioTooLong(String(BipeRegistries.profileBioMaxLength))
        case .bioTooManyLines: BipeStrings.publicProfileBioTooManyLines(String(BipeRegistries.profileBioMaxLines))
        case .noHandle: BipeStrings.publicProfileNeedsHandle
        case .nameLookalikeEmoji: BipeStrings.warningNameLookalikeEmoji
        case .socialInvalid: network.map(BipeStrings.publicProfileSocialInvalid) ?? BipeStrings.errorGeneric
        case .socialReserved: BipeStrings.publicProfileSocialReserved
        }
    }
}

/// Client-side profile checks for early feedback (ADR 0041). The server is the authority.
public nonisolated enum BipePublicProfileRules {
    /// `profileProblem()` in bipe-core. Lengths count code points, like the API.
    public static func profileProblem(name: String, bio: String) -> BipeProfileProblem? {
        BipeCore.profileProblem(name: name, bio: bio).map { problem in
            switch problem {
            case .nameEmpty: .nameEmpty
            case .nameTooLong: .nameTooLong
            case .nameReserved: .nameReserved
            case .bioTooLong: .bioTooLong
            case .bioTooManyLines: .bioTooManyLines
            }
        }
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
