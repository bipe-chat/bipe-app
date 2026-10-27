// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// One BipeHandleChangeStore per account (spec handle-change-line notes: the cache lives on the device per account).
@MainActor
enum HandleChangeStores {
    private static var stores: [String: BipeHandleChangeStore] = [:]

    static func store(for session: AccountSession) -> BipeHandleChangeStore {
        if let store = stores[session.userId] {
            return store
        }
        let store = BipeHandleChangeStore(apiClient: BipeAPIClient(baseURL: AppConfig.apiBaseURL),
                                          userID: session.userId,
                                          cacheDirectory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first,
                                          openID: session.openID)
        stores[session.userId] = store
        return store
    }
}
