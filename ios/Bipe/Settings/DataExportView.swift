// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Spec data-export: a copy of what the Bipe API stores about the user, as one JSON file (LGPD art. 18, ADR 0112).
@MainActor
final class DataExportModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case confirmIdentity
        case enterPin
        case preparing
        case failed(String)
    }

    struct SharedFile: Identifiable {
        let url: URL
        var id: URL { url }
    }

    @Published var phase: Phase = .idle
    @Published var file: SharedFile?
    private let model: SettingsModel

    init(model: SettingsModel) {
        self.model = model
    }

    /// Action start: biometrics or the phone's passcode; else the app lock PIN when the lock is on; else failed.
    func start() async {
        guard phase == .idle else { return }
        if OwnerCheck.canCheckOwner {
            phase = .confirmIdentity
            if await OwnerCheck.owner(reason: BipeStrings.privacyExportBiometric) {
                await export()
            } else {
                phase = .idle
            }
        } else if AppLockModel.shared.store.isOn {
            phase = .enterPin
        } else {
            phase = .failed(BipeStrings.privacyExportError)
        }
    }

    /// Action export: GET /v1/me/export, saved as it is in a cache folder that holds only the latest export.
    func export() async {
        phase = .preparing
        let api = model.api
        do {
            let result = try await model.authorized { (token: String) async throws(BipeAPIError) -> (data: Data, filename: String) in
                try await api.exportMyData(accessToken: token)
            }
            let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("export", isDirectory: true)
            try? FileManager.default.removeItem(at: folder)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let url = folder.appendingPathComponent(result.filename)
            try result.data.write(to: url, options: [.completeFileProtection])
            phase = .idle
            file = SharedFile(url: url)
        } catch BipeAPIError.server(.rateLimited, _, _) {
            phase = .failed(BipeStrings.errorRateLimited)
        } catch {
            phase = .failed(BipeStrings.privacyExportError)
        }
    }

    /// Action dismiss.
    func dismiss() {
        phase = .idle
    }
}

extension View {
    /// The dialogs of data-export over the privacy group.
    func dataExport(_ export: DataExportModel) -> some View {
        modifier(DataExportDialogs(export: export))
    }
}

private struct DataExportDialogs: ViewModifier {
    @ObservedObject var export: DataExportModel

    func body(content: Content) -> some View {
        content
            .overlay {
                if export.phase == .preparing {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(BipeStrings.appExportPreparing)
                            .foregroundStyle(BipeColor.inkPrimary)
                    }
                    .padding(24)
                    .background(RoundedRectangle(cornerRadius: 16).fill(BipeColor.surfaceRaised))
                    .accessibilityIdentifier(BipeScreen.dataExport.rawValue)
                }
            }
            .alert(BipeStrings.privacyExport, isPresented: Binding(get: { isFailed }, set: { if !$0 { export.dismiss() } })) {
                Button(role: .cancel) {
                    export.dismiss()
                } label: {
                    Text(BipeStrings.appCancel)
                }
            } message: {
                if case .failed(let message) = export.phase {
                    Text(message)
                }
            }
            .sheet(isPresented: Binding(get: { export.phase == .enterPin }, set: { if !$0 && export.phase == .enterPin { export.dismiss() } })) {
                AppLockPinCheckView(title: BipeStrings.privacyExport) {
                    export.phase = .preparing
                    Task { await export.export() }
                }
            }
            .sheet(item: $export.file) { file in
                ShareSheet(items: [file.url])
            }
    }

    private var isFailed: Bool {
        if case .failed = export.phase { return true }
        return false
    }
}

/// The system share sheet.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
