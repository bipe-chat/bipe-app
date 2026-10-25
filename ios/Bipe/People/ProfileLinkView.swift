// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreImage.CIFilterBuiltins
import Phosphor
import SwiftUI
import UIKit

/// Spec profile-link: the user's link (bipe.chat/@handle) and its QR code, to share or show in person.
struct ProfileLinkView: View {
    let people: PeopleService
    /// Action pickHandle: opens handle-pick.
    let pickHandle: () -> Void
    var initiallyShowsQr = false

    @State private var handle: String?
    @State private var loaded = false
    @State private var publicProfileOn = false
    @State private var showingQr = false
    @State private var notice: String?

    private var link: URL? {
        handle.flatMap(BipeProfileLink.url(handle:))
    }

    var body: some View {
        List {
            if let link {
                Section {
                    Text(link.absoluteString)
                        .font(.body.monospaced())
                        .foregroundStyle(BipeColor.inkPrimary)
                        .textSelection(.enabled)
                } header: {
                    Text(BipeStrings.appProfileLink)
                }
                Section {
                    ShareLink(item: link) {
                        IconLabel(icon: .shareNetwork, text: BipeStrings.profileShare)
                    }
                    Button {
                        UIPasteboard.general.url = link
                        notice = BipeStrings.appCopied
                    } label: {
                        IconLabel(icon: .copy, text: BipeStrings.appCopy)
                    }
                    Button {
                        withBipeAnimation { showingQr.toggle() }
                    } label: {
                        IconLabel(icon: .qrCode, text: BipeStrings.profileQrAction)
                    }
                }
                if showingQr {
                    Section {
                        VStack(spacing: 12) {
                            Text(BipeStrings.profileQrTitle)
                                .font(.headline)
                                .foregroundStyle(BipeColor.inkPrimary)
                            QRCodeImage(text: link.absoluteString)
                                .frame(width: 240, height: 240)
                            Text(BipeStrings.profileQrFooter)
                                .font(.footnote)
                                .foregroundStyle(BipeColor.inkSecondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                }
            } else if loaded {
                Section {
                    Text(BipeStrings.publicProfileNeedsHandle)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Button(BipeStrings.handlePromptAction, action: pickHandle)
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(BipeStrings.appProfileLink)
        .navigationBarTitleDisplayMode(.inline)
        .toast($notice)
        .accessibilityIdentifier(BipeScreen.profileLink.rawValue)
        .task { showingQr = initiallyShowsQr; await appear() }
    }

    /// GET /v1/me/handle and MyProfile.enabled.
    private func appear() async {
        let mine = try? await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyHandle in
            try await people.api.myHandle(accessToken: token)
        }
        handle = mine?.handle
        loaded = true
        let profile = try? await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyProfile in
            try await people.api.myProfile(accessToken: token)
        }
        publicProfileOn = profile?.enabled ?? false
    }
}

/// A QR code that holds exactly `text`, dark on light in both themes, drawn on the device.
struct QRCodeImage: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
                // Scanners need dark modules on a light ground in both themes (spec profile-link); no token is fixed light.
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel(text)
        }
    }

    static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
