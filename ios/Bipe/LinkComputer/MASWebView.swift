// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import WebKit

/// The private web view of link-computer-approve: a non-persistent data store, https pages on the MAS host only,
/// no new windows and no link previews. Never ASWebAuthenticationSession or SFSafariViewController: they share
/// cookies with the system browser (spec notes).
struct MASWebView: UIViewRepresentable {
    @ObservedObject var model: LinkComputerModel

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsLinkPreview = false
        if let url = model.verificationURL {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    /// Action finish: the web view stops and drops its data.
    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
        let store = webView.configuration.websiteDataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let model: LinkComputerModel

        init(model: LinkComputerModel) {
            self.model = model
        }

        /// A navigation or a form post off the MAS host stops the load.
        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url, let host = model.masHost,
                  BipeWebLinkAllowed.check(url, host: host) else {
                model.blockedNavigation()
                return .cancel
            }
            return .allow
        }

        /// No new windows.
        func webView(_ webView: WKWebView,
                     createWebViewWith configuration: WKWebViewConfiguration,
                     for navigationAction: WKNavigationAction,
                     windowFeatures: WKWindowFeatures) -> WKWebView? {
            nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let probe = """
            JSON.stringify({
              password: document.querySelector('input[type=password]') !== null,
              form: document.querySelector('form') !== null,
              text: document.body ? document.body.innerText : ''
            })
            """
            webView.evaluateJavaScript(probe) { [weak self, weak webView] result, _ in
                guard let self, let webView, let url = webView.url,
                      let json = (result as? String)?.data(using: .utf8),
                      let page = try? JSONDecoder().decode(Probe.self, from: json) else { return }
                Task { @MainActor in
                    let fill = self.model.pageLoaded(.init(url: url, hasPasswordField: page.password, hasForm: page.form, text: page.text))
                    if let fill {
                        webView.evaluateJavaScript(fill, completionHandler: nil)
                    }
                }
            }
        }

        private struct Probe: Decodable {
            let password: Bool
            let form: Bool
            let text: String
        }
    }
}

/// The MAS host check of BipeWebLink, also for `about:blank` that WebKit loads first.
private enum BipeWebLinkAllowed {
    static func check(_ url: URL, host: String) -> Bool {
        url.absoluteString == "about:blank" || BipeWebLink.isAllowed(url, host: host)
    }
}
