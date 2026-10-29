// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UniformTypeIdentifiers

/// Spec govbr-verify, action receiveDocument: the open gov.br check of this app run. A signed PDF that reaches the app
/// (share sheet, open with) finds the check here. Only the step 5 row of trust-profile opens a check (bipe-chat/bipe#277).
@MainActor
final class GovbrInbox: ObservableObject {
    static let shared = GovbrInbox()

    /// The flow of the open govbr-verify sheet. `nil` while no sheet is open.
    private(set) var flow: BipeGovbrFlow?
    private var flowUserId: String?
    /// A PDF came while no sheet was open: the app shows bipe_govbr_no_open_verification and uploads nothing.
    @Published var showsNoOpenVerification = false

    /// The flow for the sheet of `session`. A sheet that opens again in the same app run keeps the open check.
    func begin(session: AccountSession, api: BipeAPIClient) -> BipeGovbrFlow {
        if let flow, flowUserId == session.userId { return flow }
        let flow = BipeGovbrFlow(apiClient: api, accessToken: { session.accessToken() })
        self.flow = flow
        flowUserId = session.userId
        return flow
    }

    /// The sheet closed: a PDF that comes later finds no open check.
    func end() {
        flow = nil
        flowUserId = nil
    }

    /// onOpenURL: `true` when `url` is a PDF, which this method then handles. With the build flag off it handles nothing.
    func open(_ url: URL) -> Bool {
        guard AppConfig.govbrEnabled, url.isFileURL,
              UTType(filenameExtension: url.pathExtension)?.conforms(to: .pdf) == true else { return false }
        let pdf = Self.read(url)
        guard let flow else {
            showsNoOpenVerification = true
            return true
        }
        Task { await flow.receive(pdf: pdf) }
        return true
    }

    /// Reads a received or picked file. A file over the API limit reads as empty (rule notSigned). The copy that iOS puts in
    /// Documents/Inbox is deleted at once: the app keeps no copy of the signed file (spec govbr-verify notes).
    static func read(_ url: URL) -> Data {
        let isScoped = url.startAccessingSecurityScopedResource()
        defer {
            if isScoped { url.stopAccessingSecurityScopedResource() }
        }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let data = size > BipeRegistries.govbrMaxDocumentBytes ? Data() : (try? Data(contentsOf: url)) ?? Data()
        if let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let inbox = documents.appending(path: "Inbox").standardizedFileURL.path
            if url.standardizedFileURL.path.hasPrefix(inbox + "/") {
                try? FileManager.default.removeItem(at: url)
            }
        }
        return data
    }
}

/// Spec govbr-verify: two taps in Bipe. Start saves the challenge PDF and opens the gov.br app; the signed file comes back
/// through the share sheet, or through "Já assinei".
struct GovbrVerifyView: View {
    let flow: BipeGovbrFlow
    /// After verified: the level of the new badge, to rebuild the trust card.
    let onVerified: (BadgeLevel?) -> Void
    let close: () -> Void

    @Environment(\.openURL) private var openURL
    @State private var exportsPDF = false
    @State private var picksPDF = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(BipeStrings.govbrIntro).foregroundStyle(BipeColor.inkPrimary)
                    StepLine(number: 1, text: BipeStrings.govbrStepSave)
                    StepLine(number: 2, text: BipeStrings.govbrStepSign)
                    StepLine(number: 3, text: BipeStrings.govbrStepShare)
                } footer: {
                    Text(BipeStrings.govbrNeedsLevel)
                }
                Section {
                    phaseContent
                    if flow.showsNoOpenVerification {
                        Text(BipeStrings.govbrNoOpenVerification).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                } footer: {
                    Text(BipeStrings.govbrSafety)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.govbrTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose, action: close)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.govbrVerify.rawValue)
        // Tap 1: the system save dialog puts bipe-verificacao.pdf where the gov.br file picker shows it, then gov.br opens.
        .fileExporter(isPresented: $exportsPDF, document: pdfDocument, contentType: .pdf, defaultFilename: pdfFileName) { result in
            if case .success = result { openURL(BipeGovbrFlow.govbrAppURL) }
        }
        // Action alreadySigned: the picker of the system (UIDocumentPickerViewController) for .pdf.
        .fileImporter(isPresented: $picksPDF, allowedContentTypes: [.pdf]) { result in
            if case .success(let url) = result {
                let pdf = GovbrInbox.read(url)
                Task { await flow.receive(pdf: pdf) }
            }
        }
        .onChange(of: flow.phase) { _, phase in
            switch phase {
            case .waitingForSignature: exportsPDF = true
            case .verified: onVerified(flow.verifiedLevel)
            default: break
            }
        }
    }

    @ViewBuilder
    private var phaseContent: some View {
        switch flow.phase {
        case .ready, .preparing:
            Button { Task { await flow.start() } } label: {
                HStack {
                    if flow.phase == .preparing { ProgressView().tint(BipeColor.inkOnAccent) }
                    Text(BipeStrings.govbrStart)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            .disabled(flow.phase == .preparing)
        case .waitingForSignature:
            Button { picksPDF = true } label: {
                Text(BipeStrings.govbrAlreadySigned).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            Button { Task { await flow.start() } } label: {
                Text(BipeStrings.govbrRetry).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeBordered)
        case .checking:
            HStack(spacing: 12) {
                ProgressView()
                Text(BipeStrings.govbrChecking).foregroundStyle(BipeColor.inkPrimary)
            }
        case .verified(let name):
            HStack(alignment: .top, spacing: 12) {
                PhosphorIcon.checkCircle.image.resizable().frame(width: 22, height: 22)
                    .foregroundStyle(BipeColor.statusSuccess)
                    .accessibilityHidden(true)
                Text(name.map(BipeStrings.govbrVerified) ?? BipeStrings.govbrVerifiedNoName).foregroundStyle(BipeColor.inkPrimary)
            }
            Button(action: close) {
                Text(BipeStrings.appClose).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
        case .rejected(let reason):
            Text(Self.text(reason)).foregroundStyle(BipeColor.inkPrimary)
            retryButton
        case .failed:
            Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.inkPrimary)
            retryButton
        }
    }

    private var retryButton: some View {
        Button { Task { await flow.start() } } label: {
            Text(BipeStrings.govbrRetry).frame(maxWidth: .infinity)
        }
        .buttonStyle(.bipeProminent)
    }

    private var pdfDocument: GovbrPDF? {
        guard case .waitingForSignature(let pdf, _) = flow.phase else { return nil }
        return GovbrPDF(data: pdf)
    }

    private var pdfFileName: String {
        guard case .waitingForSignature(_, let fileName) = flow.phase else { return BipeRegistries.govbrFileName }
        return fileName
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

private struct StepLine: View {
    let number: Int
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: "\(number)")
                .font(.subheadline.monospacedDigit().bold())
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(width: 24, alignment: .center)
            Text(text).font(.subheadline).foregroundStyle(BipeColor.inkPrimary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The challenge PDF for the system save dialog.
private struct GovbrPDF: FileDocument {
    static var readableContentTypes: [UTType] { [.pdf] }

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
