// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import PhotosUI
import SwiftUI
import UIKit

/// Spec problem-report: a report the user starts, with an optional screenshot and, only when the user turns it on, the
/// app logs (ADR 0114). No shake gesture, no automatic report.
struct ProblemReportView: View {
    enum Phase: Equatable {
        case editing
        case sending
        case sent
        case failed(String)
    }

    @ObservedObject var model: SettingsModel
    @State private var text = ""
    /// Off by default, for this report only (ADR 0114).
    @State private var includeLogs = false
    @State private var screenshots: [UIImage] = []
    @State private var picked: PhotosPickerItem?
    @State private var phase: Phase = .editing
    @Environment(\.dismiss) private var dismiss

    private static let maxScreenshots = 10
    private static let maxText = 5000

    var body: some View {
        Group {
            if phase == .sent {
                sent
            } else {
                form
            }
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.helpReportProblem)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.problemReport.rawValue)
    }

    private var form: some View {
        Form {
            Section {
                TextField(BipeStrings.helpProblemDescription, text: $text, axis: .vertical)
                    .lineLimit(5...12)
                    .onChange(of: text) { _, value in
                        if value.count > Self.maxText { text = String(value.prefix(Self.maxText)) }
                    }
            } footer: {
                Text(BipeStrings.helpProblemLanguage)
            }
            Section {
                ForEach(screenshots.indices, id: \.self) { index in
                    HStack {
                        Image(uiImage: screenshots[index])
                            .resizable()
                            .scaledToFill()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        Spacer()
                        // Action removeScreenshot.
                        Button {
                            screenshots.remove(at: index)
                        } label: {
                            PhosphorIcon.x.image
                                .resizable()
                                .frame(width: 18, height: 18)
                                .foregroundStyle(BipeColor.glyphSecondary)
                                .bipeTapTarget()
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(BipeStrings.a11yRemoveScreenshot)
                    }
                }
                // Action addScreenshot: the system photo picker, one image. Never takes a screenshot by itself.
                if screenshots.count < Self.maxScreenshots {
                    PhotosPicker(selection: $picked, matching: .images) {
                        Label {
                            Text(BipeStrings.appReportScreenshot)
                        } icon: {
                            PhosphorIcon.image.image.resizable().frame(width: 20, height: 20)
                        }
                    }
                }
            }
            Section {
                Toggle(isOn: $includeLogs) {
                    Text(BipeStrings.appReportLogs)
                }
            } footer: {
                Text(BipeStrings.appReportLogsFooter)
            }
            if case .failed(let message) = phase {
                Text(message)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            Section {
                Button {
                    Task { await send() }
                } label: {
                    HStack {
                        if phase == .sending {
                            ProgressView()
                            Text(BipeStrings.appTimelineSending)
                        } else {
                            Text(BipeStrings.appReportSend)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || phase == .sending)
            }
        }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
                   screenshots.count < Self.maxScreenshots {
                    screenshots.append(image)
                }
                picked = nil
            }
        }
    }

    private var sent: some View {
        VStack(spacing: 16) {
            PhosphorIconView(icon: .check, size: 48)
            Text(BipeStrings.helpReportSentTitle)
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(BipeStrings.helpReportSentBody)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            // Action openSupport.
            NavigationLink(value: SettingsRoute.support) {
                Text(BipeStrings.settingsSupport)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            // Action close.
            Button {
                dismiss()
            } label: {
                Text(BipeStrings.appClose)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// Action send: POST /v1/bug-reports/submit as multipart.
    private func send() async {
        phase = .sending
        let info = Bundle.main.infoDictionary
        let report = BipeProblemReport(
            text: text.trimmingCharacters(in: .whitespacesAndNewlines),
            app: Bundle.main.bundleIdentifier ?? "",
            version: info?["CFBundleShortVersionString"] as? String ?? "",
            build: info?["CFBundleVersion"] as? String ?? "",
            os: "iOS \(UIDevice.current.systemVersion)",
            screenshots: screenshots.enumerated().compactMap { index, image in
                image.jpegData(compressionQuality: 0.8).map {
                    BipeProblemReport.File(name: "screenshot-\(index + 1).jpg", contentType: "image/jpeg", data: $0)
                }
            },
            // Spec problem-report notes: legacy-data.json (structure only) when the earlier app's data is here.
            logs: includeLogs ? Array(AppLogs.compressedFiles().prefix(19)) + [LegacyData.reportFile()].compactMap { $0 } : [])
        let api = model.api
        do {
            try await model.authorized { (token: String) async throws(BipeAPIError) in
                try await api.submitProblemReport(report, accessToken: token)
            }
            phase = .sent
        } catch {
            switch error {
            case .network: phase = .failed(BipeStrings.errorNetwork)
            case .server(.rateLimited, _, _): phase = .failed(BipeStrings.errorRateLimited)
            default: phase = .failed(BipeStrings.appReportError)
            }
        }
    }
}

/// The app's own log files (spec problem-report notes): no message text, no PIN, no password, no token. The app
/// writes them under Library/Logs/Bipe; this sends the gzip files found there.
enum AppLogs {
    static func compressedFiles() -> [BipeProblemReport.File] {
        let folder = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Logs/Bipe", isDirectory: true)
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "gz" }.prefix(20).compactMap { url in
            (try? Data(contentsOf: url)).map { BipeProblemReport.File(name: url.lastPathComponent, contentType: "application/gzip", data: $0) }
        }
    }
}
