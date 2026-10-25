// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI
import WebKit

/// Spec linked-devices: the other devices of the account, and signing one out on the MAS page (ADR 0116).
@MainActor
final class LinkedDevicesModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed
    }

    enum SignOutPhase: Equatable {
        case idle
        case confirmIdentity
        case loading
        case page(URL, host: String)
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var devices: [BipeMatrixDevice] = []
    @Published var signOutCandidate: BipeMatrixDevice?
    @Published private(set) var signOutPhase: SignOutPhase = .idle
    @Published var notice: String?
    private let model: SettingsModel
    private var startedAt: Date?
    private var password: String?

    init(model: SettingsModel) {
        self.model = model
    }

    private var matrix: BipeMatrixAccountAPI? {
        URL(string: model.session.client.homeserver()).map { BipeMatrixAccountAPI(homeserver: $0) }
    }

    /// Action appear: the devices of the account without this phone, newest use first.
    func load() async {
        guard let matrix, let token = model.session.accessToken() else {
            phase = .failed
            return
        }
        do {
            let ownID = try? model.session.client.deviceId()
            devices = try await matrix.devices(accessToken: token)
                .filter { $0.deviceId != ownID }
                .sorted { ($0.lastSeenTs ?? 0) > ($1.lastSeenTs ?? 0) }
            phase = .loaded
        } catch {
            phase = .failed
        }
    }

    /// Action retry.
    func retry() async {
        phase = .loading
        await load()
    }

    /// Action confirmSignOut: the biometric check, then the MAS page that ends the device's session.
    func confirmSignOut() async {
        guard let device = signOutCandidate else { return }
        signOutPhase = .confirmIdentity
        guard await OwnerCheck.owner(reason: BipeStrings.linkedDevicesSignOutBiometric) else {
            signOutPhase = .idle
            return
        }
        signOutPhase = .loading
        // The hidden password is read only after the check passes.
        guard let matrix,
              let password = BipeCredentialStore().password(for: model.session.userId)?.secret,
              let metadata = try? await matrix.authMetadata(),
              let host = metadata.host,
              let accountURI = metadata.accountManagementUri.flatMap(URL.init(string:)),
              let url = BipeWebLink.sessionEndURL(accountManagementURI: accountURI, deviceID: device.deviceId),
              BipeWebLink.isAllowed(url, host: host) else {
            signOutPhase = .failed
            return
        }
        self.password = password
        startedAt = .now
        signOutPhase = .page(url, host: host)
    }

    /// The MAS login name: the localpart of the Matrix ID.
    var username: String { BipeWebLink.localpart(of: model.session.userId) }

    /// The login form: the hidden password, once. The second time the sign-in failed.
    func takePassword() -> String? {
        defer { password = nil }
        return password
    }

    func pageFailed() {
        password = nil
        signOutPhase = .failed
    }

    /// Action pageClosed: clears the web view, ends the MAS browser session, reloads the list.
    func pageClosed() async {
        password = nil
        let device = signOutCandidate
        signOutCandidate = nil
        signOutPhase = .idle
        // The web view's data store is non-persistent: its cookies and storage go with the web view.
        if let since = startedAt {
            startedAt = nil
            let api = model.api
            // A failure is not shown.
            try? await model.authorized { (token: String) async throws(BipeAPIError) in
                try await api.finishWebLink(since: since, accessToken: token)
            }
        }
        await load()
        if let device, !devices.contains(where: { $0.deviceId == device.deviceId }) {
            notice = BipeStrings.appLinkedDevicesSignedOut
        }
    }

    func cancelSignOut() {
        signOutCandidate = nil
        signOutPhase = .idle
    }
}

struct LinkedDevicesView: View {
    @StateObject private var devices: LinkedDevicesModel
    private let settings: SettingsModel
    @State private var showsLinkComputer = false

    init(model: SettingsModel) {
        _devices = StateObject(wrappedValue: LinkedDevicesModel(model: model))
        settings = model
    }

    var body: some View {
        List {
            Section {
                // Action linkComputer: opens link-computer-scan, the only entry to a web sign-in (ADR 0116, rule 5).
                Button {
                    showsLinkComputer = true
                } label: {
                    Label {
                        Text(BipeStrings.linkedDevicesLinkComputer)
                            .foregroundStyle(BipeColor.inkPrimary)
                    } icon: {
                        PhosphorIconView(icon: .desktop, size: 22)
                    }
                }
            } footer: {
                Text(BipeStrings.linkedDevicesFooter)
            }
            Section {
                content
            }
        }
        .navigationTitle(BipeStrings.linkedDevicesTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.linkedDevices.rawValue)
        .task { await devices.load() }
        .refreshable { await devices.load() }
        .fullScreenCover(isPresented: $showsLinkComputer, onDismiss: { Task { await devices.load() } }) {
            LinkComputerView(settings: settings)
        }
        .confirmationDialog(signOutTitle,
                            isPresented: Binding(get: { devices.signOutCandidate != nil && devices.signOutPhase == .idle },
                                                 set: { if !$0 && devices.signOutPhase == .idle { devices.cancelSignOut() } }),
                            titleVisibility: .visible) {
            Button(role: .destructive) {
                Task { await devices.confirmSignOut() }
            } label: {
                Text(BipeStrings.linkedDevicesSignOut)
            }
            Button(role: .cancel) {
                devices.cancelSignOut()
            } label: {
                Text(BipeStrings.appCancel)
            }
        } message: {
            Text(BipeStrings.linkedDevicesSignOutBody)
        }
        .fullScreenCover(isPresented: Binding(get: { isOnPage }, set: { _ in })) {
            MasPageView(devices: devices)
        }
        // Closing the error also ends the MAS browser session that the web view may have made.
        .alert(BipeStrings.linkedDevicesTitle, isPresented: Binding(get: { devices.signOutPhase == .failed }, set: { _ in })) {
            Button(role: .cancel) {
                Task { await devices.pageClosed() }
            } label: {
                Text(BipeStrings.appClose)
            }
        } message: {
            Text(BipeStrings.appLinkedDevicesSignOutError)
        }
        .overlay(alignment: .bottom) {
            if let notice = devices.notice {
                NoticeView(text: notice)
                    .task {
                        try? await Task.sleep(for: .seconds(2))
                        devices.notice = nil
                    }
            }
        }
    }

    private var isOnPage: Bool {
        switch devices.signOutPhase {
        case .loading, .page: true
        default: false
        }
    }

    private var signOutTitle: String {
        BipeStrings.linkedDevicesSignOutTitle(devices.signOutCandidate.map(Self.name) ?? "")
    }

    @ViewBuilder
    private var content: some View {
        switch devices.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.linkedDevicesError)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button {
                    Task { await devices.retry() }
                } label: {
                    Text(BipeStrings.appRetry)
                }
            }
            .frame(maxWidth: .infinity)
        case .loaded:
            if devices.devices.isEmpty {
                Text(BipeStrings.linkedDevicesEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                ForEach(devices.devices) { device in
                    Button {
                        // Action askSignOut.
                        devices.signOutCandidate = device
                    } label: {
                        HStack(spacing: 12) {
                            PhosphorIconView(icon: .desktop, size: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.name(device))
                                    .foregroundStyle(BipeColor.inkPrimary)
                                if let lastUsed = device.lastUsed {
                                    Text(BipeStrings.linkedDevicesLastUsed(lastUsed.formatted(.relative(presentation: .named))))
                                        .font(.subheadline)
                                        .foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private static func name(_ device: BipeMatrixDevice) -> String {
        guard let name = device.displayName, !name.isEmpty else { return BipeStrings.linkedDevicesUnknownName }
        return name
    }
}

/// The MAS page in a private web view (link-computer-approve notes): a non-persistent data store, only https pages
/// on the MAS host, no new windows, the login form filled once with the hidden password.
private struct MasPageView: View {
    @ObservedObject var devices: LinkedDevicesModel

    var body: some View {
        NavigationStack {
            Group {
                if case .page(let url, let host) = devices.signOutPhase {
                    PrivateWebView(url: url,
                                   host: host,
                                   username: devices.username,
                                   takePassword: devices.takePassword,
                                   onSignInFailed: devices.pageFailed)
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(BipeStrings.appLinkedDevicesSignOutLoading)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        Task { await devices.pageClosed() }
                    } label: {
                        Text(BipeStrings.appClose)
                    }
                }
            }
        }
    }
}

struct PrivateWebView: UIViewRepresentable {
    let url: URL
    let host: String
    let username: String
    let takePassword: () -> String?
    let onSignInFailed: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(host: host, username: username, takePassword: takePassword, onSignInFailed: onSignInFailed)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let host: String
        private let username: String
        private let takePassword: () -> String?
        private let onSignInFailed: () -> Void
        private var filledLogin = false

        init(host: String, username: String, takePassword: @escaping () -> String?, onSignInFailed: @escaping () -> Void) {
            self.host = host
            self.username = username
            self.takePassword = takePassword
            self.onSignInFailed = onSignInFailed
        }

        /// A navigation or a form post off the MAS host stops the load.
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url else { return .cancel }
            return BipeWebLink.isAllowed(url, host: host) || url.absoluteString == "about:blank" ? .allow : .cancel
        }

        /// No new windows.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor in
                let isLoginForm = (try? await webView.evaluateJavaScript(BipeWebLink.isLoginFormScript)) as? Bool ?? false
                guard isLoginForm else { return }
                guard !filledLogin, let password = takePassword(),
                      let script = BipeWebLink.loginScript(username: username, password: password) else {
                    // The login form a second time: the sign-in failed.
                    onSignInFailed()
                    return
                }
                filledLogin = true
                _ = try? await webView.evaluateJavaScript(script)
            }
        }
    }
}
