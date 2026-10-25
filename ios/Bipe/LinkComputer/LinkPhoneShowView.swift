// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Spec link-phone-show: the old phone shows the code that the new phone scans (ADR 0059), then takes the
/// 2-digit number that the new phone shows.
struct LinkPhoneShowView: View {
    @ObservedObject var model: LinkComputerModel
    @State private var copied = false
    /// Grows with the system text size (bipe-chat/bipe#498).
    @ScaledMetric(relativeTo: .largeTitle) private var codeSize: CGFloat = 48
    @FocusState private var checkCodeFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                switch model.support {
                case .checking:
                    ProgressView().padding(.top, 48)
                case .needsPin:
                    message(BipeStrings.appLinkPhoneNeedsPin(String(BipeRegistries.pinLength)))
                case .notSupported:
                    message(BipeStrings.appLinkPhoneNotSupported)
                case .supported:
                    content
                }
            }
            .padding(16)
        }
        .accessibilityIdentifier(BipeScreen.linkPhoneShow.rawValue)
        // The code must stay on screen while the new phone scans it.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    @ViewBuilder
    private var content: some View {
        switch model.showPhase {
        case .preparing:
            title(BipeStrings.appLinkPhoneTitle)
            ProgressView()
            Text(BipeStrings.appLinkPhonePreparing)
                .foregroundStyle(BipeColor.inkSecondary)
        case .showingCode:
            title(BipeStrings.appLinkPhoneTitle)
            Text(BipeStrings.appLinkPhoneBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if let bytes = model.qrBytes, let image = Self.render(bytes) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    // A white field with a quiet zone, in light and dark mode.
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 16).fill(.white))
                    .frame(maxWidth: 320)
                    .accessibilityHidden(true)
                if LinkComputerModel.canCopyCode {
                    Button {
                        UIPasteboard.general.string = bytes.base64EncodedString()
                        copied = true
                    } label: {
                        Text(copied ? BipeStrings.appLinkPhoneCopied : BipeStrings.appLinkPhoneCopy)
                    }
                    .buttonStyle(.bordered)
                }
            }
            Text(BipeStrings.appLinkPhoneNeverShare)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
        case .enterCheckCode, .checking:
            title(BipeStrings.appLinkPhoneCheckCodeTitle)
            Text(BipeStrings.appLinkPhoneCheckCodeBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            TextField("", text: Binding(get: { model.typedCheckCode }, set: { model.editCheckCode($0) }))
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .font(.system(size: codeSize, weight: .bold, design: .monospaced))
                .multilineTextAlignment(.center)
                .frame(maxWidth: 160 * codeSize / 48)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
                .focused($checkCodeFocused)
                .disabled(model.showPhase == .checking)
                .accessibilityLabel(BipeStrings.appLinkPhoneCheckCodeTitle)
                .onAppear { checkCodeFocused = true }
            if model.showPhase == .checking {
                ProgressView()
            }
        case .failed:
            Text(BipeStrings.appLinkPhoneErrorTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.trustDangerInk)
            Text((model.showError ?? .generic).message(model.mode))
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Button {
                copied = false
                model.newCode()
            } label: {
                Text(BipeStrings.appLinkPhoneNewCode)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .font(.title2.bold())
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
            .padding(.top, 48)
    }

    /// The MSC4108 code is binary: CoreImage encodes the bytes as they are.
    static func render(_ bytes: Data) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = bytes
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
