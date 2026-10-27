// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.AppConfig
import chat.bipe.shared.BipeHandleChangeStore
import chat.bipe.shared.api.BipeApiClient

/** One BipeHandleChangeStore per account (spec handle-change-line notes: the cache lives on the device per account). */
object HandleChangeStores {
    private val stores = mutableMapOf<String, BipeHandleChangeStore>()

    @Synchronized
    fun store(context: Context, session: AccountSession): BipeHandleChangeStore = stores.getOrPut(session.userId) {
        BipeHandleChangeStore(BipeApiClient(AppConfig.API_BASE_URL), session.userId, context.applicationContext.cacheDir, session.openId)
    }
}
