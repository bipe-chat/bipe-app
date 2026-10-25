// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec device-verification: approve a new phone with a device that is already signed in.
struct DeviceVerificationView: View {
    @ObservedObject var model: DeviceVerificationModel
    let close: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            content
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .presentationDetents([.large])
        .interactiveDismissDisabled(model.phase == .confirmIdentity)
        .accessibilityIdentifier(BipeScreen.deviceVerification.rawValue)
        // The flow ends on its own: the screen closes a moment after done.
        .task(id: model.phase) {
            guard model.phase == .done else { return }
            try? await Task.sleep(for: .seconds(2))
            if !Task.isCancelled { close() }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .requesting, .waitingForOther:
            if model.role == .newPhone {
                TitleText(text: BipeStrings.appVerifyTitle)
                BodyText(text: BipeStrings.appVerifyBody)
            }
            ProgressView()
            BodyText(text: BipeStrings.appVerifyWaiting)
            Spacer()
            cancelButton
        case .incoming, .confirmIdentity:
            TitleText(text: BipeStrings.appVerifyIncomingTitle)
            BodyText(text: BipeStrings.appVerifyIncomingBody(deviceName, requestTime))
            BodyText(text: BipeStrings.deviceApprovalNeverForOthers)
            Spacer()
            Button {
                Task { await model.accept() }
            } label: {
                Text(BipeStrings.appVerifyApprove).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.phase == .confirmIdentity)
            Button {
                Task { await model.decline() }
            } label: {
                Text(BipeStrings.appCancel).frame(maxWidth: .infinity)
            }
            .controlSize(.large)
        case .compare, .waitingForMatch:
            TitleText(text: BipeStrings.appVerifyCompare)
            SymbolGrid(symbols: model.symbols)
            Spacer()
            if model.phase == .waitingForMatch {
                ProgressView()
                BodyText(text: BipeStrings.appVerifyWaiting)
            } else {
                Button {
                    Task { await model.match() }
                } label: {
                    Text(BipeStrings.appVerifyMatch).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                Button {
                    Task { await model.noMatch() }
                } label: {
                    Text(BipeStrings.appVerifyNoMatch).frame(maxWidth: .infinity)
                }
                .controlSize(.large)
            }
        case .done:
            Outcome(icon: .checkCircle, color: BipeColor.statusSuccess, text: BipeStrings.appVerifyDone)
        case .cancelled:
            Outcome(icon: .x, color: BipeColor.glyphSecondary,
                    text: model.mismatch ? BipeStrings.appVerifyMismatch : BipeStrings.appVerifyCancelled)
        case .failed:
            Outcome(icon: .warningCircle, color: BipeColor.trustCautionInk, text: BipeStrings.errorGeneric)
        }
    }

    private var cancelButton: some View {
        Button {
            Task { await model.cancel() }
            close()
        } label: {
            Text(BipeStrings.appCancel).frame(maxWidth: .infinity)
        }
        .controlSize(.large)
    }

    private var deviceName: String {
        guard let device = model.otherDevice else { return "" }
        return device.displayName ?? device.deviceId
    }

    private var requestTime: String {
        let date = model.otherDevice?.firstSeenAt ?? Date()
        return Calendar.current.isDateInToday(date)
            ? date.formatted(date: .omitted, time: .shortened)
            : date.formatted(date: .abbreviated, time: .shortened)
    }
}

private struct TitleText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.title2.bold())
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
    }
}

private struct BodyText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.body)
            .foregroundStyle(BipeColor.inkSecondary)
            .multilineTextAlignment(.center)
    }
}

/// The 7 SAS symbols: the symbol large, its label small (spec notes).
private struct SymbolGrid: View {
    let symbols: [VerificationSymbol]
    /// Grows with the system text size (bipe-chat/bipe#498).
    @ScaledMetric(relativeTo: .largeTitle) private var symbolSize: CGFloat = 40
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // Two columns at the accessibility text sizes, so that every name shows in full.
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: typeSize.isAccessibilitySize ? 2 : 4), spacing: 16) {
            ForEach(symbols) { symbol in
                VStack(spacing: 4) {
                    Text(symbol.symbol)
                        .font(.system(size: symbolSize))
                    Text(symbol.label)
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct Outcome: View {
    let icon: PhosphorIcon
    let color: Color
    let text: String

    var body: some View {
        Spacer()
        icon.image
            .resizable()
            .frame(width: 56, height: 56)
            .foregroundStyle(color)
            .accessibilityHidden(true)
        Text(text)
            .font(.title3)
            .foregroundStyle(BipeColor.inkPrimary)
            .multilineTextAlignment(.center)
        Spacer()
    }
}
