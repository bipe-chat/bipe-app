// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// The MSC4108 login side of spec recovery-old-phone. The Matrix ID is unknown until the login is done, so the client
/// opens a fresh store folder; at done the folder moves to the account's folder and the session restores from it.
/// App target only: the notification service extension never signs in.
extension AccountSession {
    /// The fresh store of a QR login that has not finished.
    static let qrLoginFolder = "qr-login"

    /// A client for the server in the code (else the Bipe homeserver), in an empty store.
    static func makeQRLoginClient(serverName: String?) async throws -> Client {
        deleteStore(folder: qrLoginFolder)
        let paths = try storePaths(folder: qrLoginFolder)
        return try await AccountStep.run("build client") {
            try await builder(paths: paths)
                .serverNameOrHomeserverUrl(serverNameOrUrl: serverName ?? AppConfig.homeserver)
                // The MAS consent page on the old phone shows the user agent: it names the phone model (ADR 0059).
                .userAgent(userAgent: PhoneModel.userAgent)
                .build()
        }
    }

    /// The OAuth client of this phone (MAS dynamic client registration). The device code grant never opens the
    /// redirect URI; MAS needs one, as the reverse domain of clientUri.
    static var qrLoginOAuthConfiguration: OAuthConfiguration {
        OAuthConfiguration(clientName: "Bipe (\(PhoneModel.identifier))",
                           redirectUri: "chat.bipe:/callback",
                           clientUri: AppConfig.publicSite.absoluteString,
                           logoUri: nil,
                           tosUri: nil,
                           policyUri: nil,
                           staticRegistrations: [:])
    }

    /// After LoginWithQrCodeHandler.scan finished: saves the session, moves the fresh store to the account's folder
    /// and restores the session from it. The caller drops the login client first. Never signs in again: the crypto
    /// store belongs to this device.
    static func adoptQRLogin(_ session: Session) async throws -> AccountSession {
        SessionStore.save(session)
        let fileManager = FileManager.default
        let fresh = try storePaths(folder: qrLoginFolder)
        let target = try storePaths(folder: folderName(userId: session.userId))
        for (from, to) in [(fresh.data, target.data), (fresh.cache, target.cache)] {
            try? fileManager.removeItem(at: to)
            try fileManager.moveItem(at: from, to: to)
        }
        return try await restore(session)
    }
}

/// The phone model for the OAuth client name and the user agent, never for anything else.
enum PhoneModel {
    /// The hardware model, for example iPhone16,1. On the Simulator, the simulated model.
    static var identifier: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulated
        }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { buffer in
            String(decoding: buffer.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    static var userAgent: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let system = ProcessInfo.processInfo.operatingSystemVersion
        return "Bipe/\(version) (\(identifier); iOS \(system.majorVersion).\(system.minorVersion))"
    }
}
