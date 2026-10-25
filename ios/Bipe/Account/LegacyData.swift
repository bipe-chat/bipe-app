// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// The earlier Bipe app's data on this iPhone (bipe-chat/bipe#603; spec account-start legacyKept). Read only.
enum LegacyData {
    private static var data: BipeLegacyData { BipeLegacyData(ownServices: KeychainMove.services) }

    /// Spec account-start legacyKept.
    static var isPresent: Bool { data.isPresent }

    /// legacy-data.json for problem-report (structure only), or nil when the phone holds no legacy data.
    static func reportFile() -> BipeProblemReport.File? {
        guard isPresent else { return nil }
        let manager = FileManager.default
        var folders: [String: URL] = [:]
        if let group = manager.containerURL(forSecurityApplicationGroupIdentifier: AppConfig.appGroup) {
            folders["appGroup"] = group
        }
        let library = manager.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        folders["applicationSupport"] = library.appendingPathComponent("Application Support", isDirectory: true)
        folders["caches"] = library.appendingPathComponent("Caches", isDirectory: true)
        folders["documents"] = manager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        var defaults: [String: UserDefaults] = [:]
        if let bundle = Bundle.main.bundleIdentifier { defaults[bundle] = .standard }
        if let suite = UserDefaults(suiteName: AppConfig.appGroup) { defaults[AppConfig.appGroup] = suite }
        let report = data.report(folders: folders, defaults: defaults)
        return BipeProblemReport.File(name: "legacy-data.json", contentType: "application/json", data: report)
    }
}
