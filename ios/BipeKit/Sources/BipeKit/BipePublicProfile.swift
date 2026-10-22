// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written from the reference `profileProblem()` in bipe-shared tools/lib/registries.mjs and registries/profiles.json
// (ADR 0041). The server checks the same rules and is the authority.

import Foundation
import UIKit

/// Same ids as `profileProblem()` and the API's `ProfileProblem`.
public enum BipeProfileProblem: String, Sendable, Equatable {
    case nameEmpty = "name_empty"
    case nameTooLong = "name_too_long"
    case nameReserved = "name_reserved"
    case bioTooLong = "bio_too_long"
    case bioTooManyLines = "bio_too_many_lines"
    /// Server only: turning the profile on needs a handle.
    case noHandle = "no_handle"

    public var localizedMessage: String {
        switch self {
        case .nameEmpty: BipeStrings.publicProfileNameEmpty
        case .nameTooLong: BipeStrings.publicProfileNameTooLong(String(BipeRegistries.profileNameMaxLength))
        case .nameReserved: BipeStrings.appProfileNameReserved
        case .bioTooLong: BipeStrings.publicProfileBioTooLong(String(BipeRegistries.profileBioMaxLength))
        case .bioTooManyLines: BipeStrings.publicProfileBioTooManyLines(String(BipeRegistries.profileBioMaxLines))
        case .noHandle: BipeStrings.publicProfileNeedsHandle
        }
    }
}

public nonisolated enum BipePublicProfileRules {
    /// `nil` when the name and bio pass. Lengths count code points, like the API.
    public static func profileProblem(name: String, bio: String) -> BipeProfileProblem? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .nameEmpty
        }
        if trimmed.unicodeScalars.count > BipeRegistries.profileNameMaxLength {
            return .nameTooLong
        }
        if NameNormalizer.reservedCategory(trimmed) != nil {
            return .nameReserved
        }
        let trimmedBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedBio.unicodeScalars.count > BipeRegistries.profileBioMaxLength {
            return .bioTooLong
        }
        if trimmedBio.split(separator: "\n", omittingEmptySubsequences: false).count > BipeRegistries.profileBioMaxLines {
            return .bioTooManyLines
        }
        return nil
    }

    /// The name rules alone (the Matrix display name of profile-edit has no bio).
    public static func nameProblem(_ name: String) -> BipeProfileProblem? {
        profileProblem(name: name, bio: "")
    }
}

/// Square JPEG for profile photos: at most `profilePhotoMaxPixels` on each side and `profilePhotoMaxBytes`.
public enum BipeProfilePhoto {
    /// Crops the centre square, scales it down and re-encodes it as JPEG with no metadata.
    /// `nil` when the image cannot be encoded within the limits.
    public static func jpegData(from image: UIImage, maxPixels: Int = BipeRegistries.profilePhotoMaxPixels,
                                maxBytes: Int = BipeRegistries.profilePhotoMaxBytes) -> Data? {
        guard let square = squareImage(image, side: maxPixels) else { return nil }
        for quality in [0.85, 0.75, 0.6, 0.45, 0.3] {
            if let data = square.jpegData(compressionQuality: quality), data.count <= maxBytes {
                return data
            }
        }
        return nil
    }

    /// The centre square of `image`, at most `side` pixels, drawn upright at scale 1.
    public static func squareImage(_ image: UIImage, side: Int) -> UIImage? {
        let size = image.size
        let length = min(size.width, size.height)
        guard length > 0 else { return nil }
        let target = min(CGFloat(side), length * image.scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: target, height: target), format: format)
        let factor = target / length
        return renderer.image { _ in
            let origin = CGPoint(x: -(size.width - length) / 2 * factor, y: -(size.height - length) / 2 * factor)
            image.draw(in: CGRect(origin: origin, size: CGSize(width: size.width * factor, height: size.height * factor)))
        }
    }
}
