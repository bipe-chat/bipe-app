// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UniformTypeIdentifiers

/// The open gov.br verification of this app session (spec govbr-verify receiveFile). A signed PDF shared to Bipe goes to
/// the flow that waits for it; with no open verification it shows bipe_govbr_no_open_verification and is not uploaded.
@MainActor
final class GovbrInbox: ObservableObject {
    static let shared = GovbrInbox()

    /// The flow of the sheet that is open, else the last one that waits for a signature.
    @Published private(set) var flow: BipeGovbrFlow?
    /// A PDF arrived with no open verification.
    @Published var showsNoOpenVerification = false

    func open(_ flow: BipeGovbrFlow) {
        self.flow = flow
    }

    /// Action receiveFile, from onOpenURL. Returns false when the URL is not a PDF, so other handlers take it.
    @discardableResult
    func receive(_ url: URL) -> Bool {
        guard url.isFileURL, UTType(filenameExtension: url.pathExtension)?.conforms(to: .pdf) == true else { return false }
        guard BipeRegistries.govbrEnabled, let flow, case .waitingForSignature = flow.phase else {
            showsNoOpenVerification = true
            return true
        }
        Task { await flow.submit(fileURL: url) }
        return true
    }
}

/// Spec govbr-verify (bipe-chat/bipe#544): trust step 5 in two taps. Opens only from the govbr step of trust-profile.
struct GovbrVerifyView: View {
    @State private var flow: BipeGovbrFlow
    @ObservedObject private var inbox = GovbrInbox.shared
    let close: () -> Void

    @State private var exporting = false
    @State private var importing = false

    init(api: BipeAPIClient, session: AccountSession, close: @escaping () -> Void) {
        _flow = State(initialValue: BipeGovbrFlow(apiClient: api, accessToken: { session.accessToken() }))
        self.close = close
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    content
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(BipeStrings.govbrTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose, action: close)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.govbrVerify.rawValue)
        .onAppear { inbox.open(flow) }
        .fileExporter(isPresented: $exporting, document: pdfDocument, contentType: .pdf,
                      defaultFilename: BipeRegistries.govbrFileName) { result in
            // The person saved the file (or cancelled the dialog): gov.br opens next either way, the file can be saved again.
            if case .success = result {
                UIApplication.shared.open(BipeGovbrFlow.govbrAppURL)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf]) { result in
            if case .success(let url) = result {
                Task { await flow.submit(fileURL: url) }
            }
        }
        .onChange(of: isWaiting) { _, waiting in
            if waiting { exporting = true }
        }
    }

    private var isWaiting: Bool {
        if case .waitingForSignature = flow.phase { return true }
        return false
    }

    private var pdfDocument: GovbrPDF? {
        if case .waitingForSignature(let pdf, _) = flow.phase { return GovbrPDF(data: pdf) }
        return nil
    }

    @ViewBuilder
    private var content: some View {
        switch flow.phase {
        case .ready:
            intro
            startButton(BipeStrings.govbrStart)
        case .preparing, .checking:
            HStack(spacing: 12) {
                ProgressView()
                if case .checking = flow.phase {
                    Text(BipeStrings.govbrChecking).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            .frame(maxWidth: .infinity)
        case .waitingForSignature:
            intro
            Button { importing = true } label: {
                Text(BipeStrings.govbrAlreadySigned).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            Button(BipeStrings.govbrGetApp) {
                UIApplication.shared.open(BipeGovbrFlow.govbrAppURL)
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(BipeColor.accentInk)
        case .verified(let name):
            Text(BipeStrings.govbrVerified(name ?? ""))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            Button { close() } label: {
                Text(BipeStrings.appClose).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
        case .rejected(let reason):
            Text(Self.text(reason))
                .foregroundStyle(BipeColor.trustDangerInk)
            startButton(BipeStrings.govbrRetry)
        case .failed:
            startButton(BipeStrings.govbrRetry)
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(BipeStrings.govbrIntro).foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.govbrNeedsLevel).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
            ForEach(Array([BipeStrings.govbrStepSave, BipeStrings.govbrStepSign, BipeStrings.govbrStepShare].enumerated()), id: \.offset) { index, line in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(String(index + 1)).monospacedDigit().foregroundStyle(BipeColor.inkSecondary)
                    Text(line).foregroundStyle(BipeColor.inkPrimary)
                }
            }
            HStack(alignment: .top, spacing: 8) {
                PhosphorIcon.lockSimple.image.resizable().frame(width: 18, height: 18)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.govbrSafety).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
            }
        }
    }

    private func startButton(_ title: String) -> some View {
        Button {
            Task { await flow.start() }
        } label: {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bipeProminent)
    }

    static func text(_ reason: BipeGovbrReason) -> String {
        switch reason {
        case .notSigned: BipeStrings.govbrReasonNotSigned
        case .notGovbr: BipeStrings.govbrReasonNotGovbr
        case .wrongDocument: BipeStrings.govbrReasonWrongDocument
        case .expired: BipeStrings.govbrReasonExpired
        case .cpfInUse: BipeStrings.govbrReasonCpfInUse
        case .changedAfterSigning: BipeStrings.govbrReasonChangedAfterSigning
        }
    }
}

/// The challenge PDF for the system save dialog.
private struct GovbrPDF: FileDocument {
    static let readableContentTypes: [UTType] = [.pdf]
    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
