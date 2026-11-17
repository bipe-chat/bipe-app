// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The MAS device-grant approval of specs link-computer-approve and link-phone-approve without a web view
// (ADR 0224). Written from the specs and the MAS form fields they name: login form csrf, username, password;
// consent form csrf, confirm_device, action=consent; reject form csrf, action=reject.

import Foundation

/// One approval: an ephemeral HTTP session (own cookies, no cache) that loads only https pages on the MAS host.
/// Call `close()` when the flow ends.
public final class MASDeviceApproval: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    /// The consent page, as the native consent screen shows it.
    public struct Consent: Sendable, Equatable {
        public let pageURL: URL
        /// The device card text of MAS (model, OS, browser), or nil.
        public let device: String?
        public let ip: String?
        let csrf: String
        /// The raw user agent of the device that asks (the title of the MAS device card), for the approval entry of
        /// ADR 0291 (BipeDevices.userAgentInfo). nil when MAS shows none.
        public var userAgent: String? = nil
    }

    /// Spec error codes. The log names the code, the status and the page path; never the query or a secret.
    public enum Failure: Error, Equatable, Sendable {
        case offHost
        case status(Int)
        case secondLogin
        case notConsentPage(path: String)
        case consentNotGranted
        case network

        public var code: String {
            switch self {
            case .offHost: "consent-host"
            case .status(let status): "consent-status-\(status)"
            case .secondLogin: "consent-login"
            case .notConsentPage: "consent-page"
            case .consentNotGranted: "consent-post"
            case .network: "consent-network"
            }
        }
    }

    private let host: String
    private var leftHost = false
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldSetCookies = true
        configuration.timeoutIntervalForRequest = 20
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    public init(host: String) {
        self.host = host
    }

    /// Action open: the verification page, the login form once, then the consent page.
    public func open(_ verificationURL: URL, username: String, password: String) async throws(Failure) -> Consent {
        var page = try await load(URLRequest(url: verificationURL))
        if let login = page.forms.first(where: \.hasPassword) {
            var fields = login.hiddenFields
            fields["username"] = username
            fields["password"] = password
            page = try await load(Self.post(login.target(on: page.url), fields: fields))
            if page.forms.contains(where: \.hasPassword) { throw .secondLogin }
        }
        guard BipeWebLink.isDeviceConsentPath(page.url),
              let form = page.forms.first(where: { $0.button(name: "action", value: "consent") }),
              let csrf = form.hiddenFields["csrf"] else {
            throw .notConsentPage(path: page.url.path())
        }
        return Consent(pageURL: form.target(on: page.url), device: page.deviceText,
                       ip: page.metadataText.flatMap(BipeWebLink.firstIPAddress(in:)), csrf: csrf,
                       userAgent: page.userAgent)
    }

    /// Action approve: posts the consent form. MAS answers with its granted page, which has no consent form.
    public func approve(_ consent: Consent) async throws(Failure) {
        let page = try await load(Self.post(consent.pageURL,
                                            fields: ["csrf": consent.csrf, "confirm_device": "on", "action": "consent"]))
        if page.forms.contains(where: { $0.button(name: "action", value: "consent") }) || page.forms.contains(where: \.hasPassword) {
            throw .consentNotGranted
        }
    }

    /// Action reject: posts the reject form. A failure changes nothing for the user.
    public func reject(_ consent: Consent) async {
        _ = try? await load(Self.post(consent.pageURL, fields: ["csrf": consent.csrf, "action": "reject"]))
    }

    public func close() {
        session.invalidateAndCancel()
    }

    // MARK: HTTP

    private func load(_ request: URLRequest) async throws(Failure) -> MASPage {
        guard let url = request.url, BipeWebLink.isAllowed(url, host: host) else { throw .offHost }
        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: request)
        } catch {
            throw leftHost ? .offHost : .network
        }
        let (data, response) = result
        guard !leftHost, let http = response as? HTTPURLResponse, let final = http.url,
              BipeWebLink.isAllowed(final, host: host) else { throw .offHost }
        guard http.statusCode < 400 else { throw .status(http.statusCode) }
        return MASPage(url: final, html: String(decoding: data, as: UTF8.self))
    }

    /// A redirect off the MAS host stops the flow. The completion-handler form: Swift 6.4 crashes on the Objective-C
    /// thunk of the async form (SILGen emitNativeToForeignThunk, bipe-app#363).
    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        guard let url = request.url, BipeWebLink.isAllowed(url, host: host) else {
            leftHost = true
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }

    private static func post(_ url: URL, fields: [String: String]) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        request.httpBody = fields
            .map { key, value in
                "\(key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key)=\(value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
            }
            .joined(separator: "&")
            .data(using: .utf8)
        return request
    }
}

/// The parts of a MAS page that the approval reads: forms with their inputs and buttons, and the device card.
/// It runs no script.
struct MASPage {
    struct Form {
        /// The action attribute, nil when the form posts to its own page.
        var action: String?
        var inputs: [[String: String]]
        var buttons: [[String: String]]

        var hasPassword: Bool { inputs.contains { $0["type"]?.lowercased() == "password" } }

        var hiddenFields: [String: String] {
            var fields: [String: String] = [:]
            for input in inputs where input["type"]?.lowercased() == "hidden" {
                if let name = input["name"] { fields[name] = input["value"] ?? "" }
            }
            return fields
        }

        func button(name: String, value: String) -> Bool {
            buttons.contains { $0["name"] == name && $0["value"] == value }
        }

        func target(on page: URL) -> URL {
            guard let action, !action.isEmpty, let url = URL(string: action, relativeTo: page) else { return page }
            return url.absoluteURL
        }
    }

    let url: URL
    let forms: [Form]
    /// The text of the device card header, one line per part, joined with " · ".
    let deviceText: String?
    /// The text of the device card metadata (IP, time, code).
    let metadataText: String?
    /// The title attribute of the device card header: MAS puts the raw user agent there.
    let userAgent: String?

    init(url: URL, html: String) {
        self.url = url
        forms = Self.matches(#"<form\b([^>]*)>(.*?)</form>"#, in: html).map { groups in
            Form(action: Self.attributes(groups[0])["action"],
                 inputs: Self.matches(#"<input\b([^>]*)>"#, in: groups[1]).map { Self.attributes($0[0]) },
                 buttons: Self.matches(#"<button\b([^>]*)>"#, in: groups[1]).map { Self.attributes($0[0]) })
        }
        let header = Self.slice(html, from: "class=\"card-header\"", to: "class=\"metadata\"")
        let parts = header.map { Self.matches(#"<div>\s*([^<]+?)\s*</div>"#, in: $0).map { Self.text($0[0]) } } ?? []
        deviceText = parts.isEmpty ? nil : parts.joined(separator: " · ")
        metadataText = Self.slice(html, from: "class=\"metadata\"", to: "</header>").map(Self.text)
        userAgent = Self.matches(#"<div\b([^>]*class="card-header"[^>]*)>"#, in: html).first
            .flatMap { Self.attributes($0[0])["title"] }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
    }

    private static func slice(_ html: String, from start: String, to end: String) -> String? {
        guard let lower = html.range(of: start)?.upperBound else { return nil }
        let upper = html.range(of: end, range: lower..<html.endIndex)?.lowerBound ?? html.endIndex
        return String(html[lower..<upper])
    }

    /// Visible text: tags removed, entities decoded, white space collapsed.
    private static func text(_ html: String) -> String {
        let stripped = html.replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
        return decode(stripped).split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    private static func attributes(_ tag: String) -> [String: String] {
        var result: [String: String] = [:]
        for groups in matches(#"([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?"#, in: tag) {
            let value = groups[1].isEmpty ? (groups[2].isEmpty ? groups[3] : groups[2]) : groups[1]
            result[groups[0].lowercased()] = decode(value)
        }
        return result
    }

    private static func decode(_ value: String) -> String {
        value.replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#34;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#x27;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    /// The capture groups of each match; a group that did not take part is "".
    private static func matches(_ pattern: String, in text: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return regex.matches(in: text, range: range).map { match in
            (1..<match.numberOfRanges).map { index in
                Range(match.range(at: index), in: text).map { String(text[$0]) } ?? ""
            }
        }
    }
}
